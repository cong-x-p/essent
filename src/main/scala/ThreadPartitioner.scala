package essent

import essent.Util._
import essent.Graph.NodeID
import essent.Extract._
import essent.ir._
import firrtl.ir._
import _root_.logger._
import firrtl.PrimOps._
import firrtl._

import java.io.{File, FileWriter}
import collection.mutable.{ArrayBuffer, Map}
import scala.collection.{BitSet, mutable}
import scala.io.Source


class PartGraph extends StatementGraph {

    val idToTreeID = ArrayBuffer[mutable.Set[NodeID]]()

    val idToPieceID = ArrayBuffer[NodeID]()

    val idToNodeWeight = ArrayBuffer[NodeID]()

    val sinkNodes = ArrayBuffer[NodeID]()
    var trees = mutable.ArrayBuffer[BitSet]()
    val pieces = mutable.ArrayBuffer[BitSet]()


    val hg = new HyperGraph()


    def buildFromGraph(sg: StatementGraph): Unit = {
        idToTreeID.appendAll(ArrayBuffer.fill(sg.numNodes())(mutable.Set[NodeID]()))
        idToPieceID ++= ArrayBuffer.fill(sg.numNodes())(-1)

        idToNodeWeight.appendAll(ArrayBuffer.fill(sg.numNodes())(-1))

        // -1 => unvisited
        // -2 => invalid
        sg.idToStmt.indices.filterNot(sg.validNodes.contains).foreach { id => idToPieceID(id) = -2 }


        // Deep copy
        outNeigh.appendAll(ArrayBuffer.fill(sg.numNodes())(ArrayBuffer[NodeID]()))
        inNeigh.appendAll(ArrayBuffer.fill(sg.numNodes())(ArrayBuffer[NodeID]()))
        sg.nodeRange foreach { id => {
            sg.outNeigh(id).copyToBuffer(outNeigh(id))
            sg.inNeigh(id).copyToBuffer(inNeigh(id))
        }
        }

        // Shallow copy (read only)
        nameToID ++= sg.nameToID
        idToName ++= sg.idToName
        idToStmt ++= sg.idToStmt
        validNodes ++= sg.validNodes

    }

    def initTrees(): Unit = {

        val treeCache = mutable.HashMap[NodeID, Set[Int]]()

        def collectTree(seed: NodeID): Set[Int] = {
            if (!treeCache.contains(seed)) {
                val depNodes = idToStmt(seed) match {
                    case inv if (!validNodes.contains(seed)) => Set[Int]() // invalid
                    case d: DefRegister => Set[Int]() // Stop at register read
                    case _ => Set[Int](seed) ++ (inNeigh(seed) flatMap {
                        collectTree
                    })
                }

                if (outNeigh(seed).length > 1) {
                    treeCache(seed) = depNodes
                }

                depNodes
            } else treeCache(seed)
        } //  从seed节点出发，递归地收集与之相关的节点，并将这些节点存储在treeCache中；递归的条件为seed不在validNodes集合里或者seed关联语句为DefRegister类型

        sinkNodes ++= validNodes.filter(outNeigh(_).isEmpty) // 将validNodes中无出边的节点添加到sinkNodes集合中

        val collectedParts = (sinkNodes.map {
            collectTree
        }).map(BitSet() ++ _) //  对sinkNodes中对每个节点调用collectTree函数并将结果集合的元素转换为BitSet，存储在collectedParts中

        trees.clear()
        trees ++= collectedParts //  更新树结构
    }

    def initPieces() = {

        trees.indices.foreach { partID => {
            trees(partID).foreach { nodeID => {
                idToTreeID(nodeID) += partID
            }
            }
        }
        } //  利用partID遍历trees中所有树中的所有节点的nodeID，将partID添加到idToTreeID(nodeID)对应的BitSet中


        // Assuming pieces(pid) is an empty BitSet
        def findPiece(pid: NodeID)(seed: NodeID): Unit = {
            if (idToPieceID(seed) == -1) {
                pieces(pid) += seed
                idToPieceID(seed) = pid
                val connectedVertecies = (inNeigh(seed) ++ outNeigh(seed)).filter(idToPieceID(_) == -1)
                val samePieceVertecies = connectedVertecies.filter(vid => {
                    idToTreeID(vid) == idToTreeID(seed)
                })
                samePieceVertecies foreach findPiece(pid)
            }
        } //  若传入的seed没有被分配，则把seed放入参数中pid代表的piece中，再对与seed相连的不属于任何piece且属于同一个tree的节点调用该函数

        // First, collect all pieces for all sink nodes
        sinkNodes.zipWithIndex.foreach { case (sinkNode, pid) => {
            pieces.append(BitSet())
            findPiece(pid)(sinkNode)
        }
        } //  为每个sinkNode创建一个piece，任何调用findPiece(pid)(sinkNode)查找每个与该sinkNode连接的点并划分到一个piece里

        // Collect pieces for all other nodes
        do {
            val unvisited = idToPieceID.indices.filter(idToPieceID(_) == -1)
            val newPid = pieces.length
            pieces.append(BitSet())
            findPiece(newPid)(unvisited.head)

        } while (idToPieceID.indices.exists(idToPieceID(_) == -1))
        //  把剩余没有归类的节点分配到一个新的piece里面，直到所有节点都归类
    }


    def calculateNodeWeight(id: NodeID): Int = {
        if (idToNodeWeight(id) != -1) {
            return idToNodeWeight(id)
        }


        def exprWeight(e: Expression): Int = e match {

            case r: Reference => r.kind match {
                case firrtl.PortKind => 0
                case firrtl.MemKind => 0
                case firrtl.RegKind => 0
                case firrtl.InstanceKind => 0
                // Connect to other nodes will be handled as dependency, ignore here
                case firrtl.NodeKind => 0
                case _ => 0
            } //  Reference类型表达式以上几种类型权重为0
            case u: UIntLiteral => {
                val width = u.tpe match {
                    case UIntType(IntWidth(w)) => w.toInt
                }
                width
            } //  UIntLiteral类型表达式权重为其宽度
            case s: SIntLiteral => {
                val width = s.tpe match {
                    case SIntType(IntWidth(w)) => w.toInt
                }
                width
            } //  SIntLiteral类型表达式权重为其宽度


            case op: DoPrim => {
                val opWidth = op.args map (_.tpe match {
                    case UIntType(IntWidth(w)) => w.toInt
                    case SIntType(IntWidth(w)) => w.toInt
                    case AsyncResetType => 1
                    case tpe => throw new Exception(s"Unknown type ${tpe}")
                })
                val maxOpWidth = opWidth.max
                val nWords = (maxOpWidth + 63) / 64

                val opWeight = op.op match {
                    case Add | Addw | Sub | Subw => (maxOpWidth + 1) match {
                        case w if (w <= 64) => 2
                        case w if (w <= 128) => 8
                        case w if (w <= 256) => 16
                        case _ => 30
                    }

                    case Mul => maxOpWidth match {
                        case m if (m <= 64) => opWidth.min match {
                            case w if (w <= 8) => 1
                            case w if (w <= 16) => 9
                            case _ => 25
                        }
                        case _ => 25
                    }

                    // Div/Rem only supports less than 64 bits
                    case Div | Rem => 6

                    // Logic
                    case Eq | Geq | Gt | Leq | Lt | Neq => maxOpWidth match {
                        case w if (w <= 64) => 1
                        case w if (w <= 128) => 3
                        case _ => 5
                    }

                    // Shift
                    case Dshl => {
                        val outputWidth = opWidth.head + (1 << opWidth.last) - 1
                        if (outputWidth <= 64) 3 else 24
                    }
                    case Dshlw => {
                        if (maxOpWidth <= 64) 6 else nWords * 20
                    }
                    case Dshr => {
                        if (maxOpWidth <= 64) 5 else nWords * 20
                    }
                    // Shl/Shr are static
                    case Shl | Shr => 2

                    // Conv
                    case Pad => 1
                    case Bits => 2
                    case Cat => if (opWidth.sum <= 64) 2 else nWords * 5
                    case Head => 2
                    case Tail => 1

                    case AsAsyncReset => 0
                    case AsSInt | AsUInt => 1

                    case Cvt => 1
                    case Neg => 1

                    // Bitwise
                    case And | Or | Xor | Not => if (maxOpWidth <= 64) 2 else nWords * 2
                    case Andr | Orr => nWords

                    case Xorr => maxOpWidth match {
                        case w if (w == 1) => 5
                        case w if (w <= 64) => 20
                        case w if (w <= 192) => nWords * 20
                        case w => nWords * 10
                    }

                    case _ => 2
                }

                val argLiterals = op.args.collect { case arg: UIntLiteral => arg } ++ op.args.collect { case arg: SIntLiteral => arg }

                if (argLiterals.nonEmpty) 0 else opWeight + (op.args map {
                    exprWeight
                }).sum

            }


            case m: Mux => {
                val opWidth = m.tval.tpe match {
                    case UIntType(IntWidth(w)) => w.toInt
                    case SIntType(IntWidth(w)) => w.toInt
                    case AsyncResetType => 1
                    case tpe => throw new Exception(s"Unknown type ${tpe}")
                }
                // Assuming condition is either a boolean expr or a reference
                //        val condWeight = exprWeight(m.cond)
                //        val tvalWeight = exprWeight(m.tval)
                //        val fvalWeight = exprWeight(m.fval)
                //        2 + condWeight + ((tvalWeight + fvalWeight) / 2)
                val nWords = (opWidth + 63) / 64
                nWords * 6
            }

            case sf: SubField => 0

            // SubAccess: A field in memory
            case sa: SubAccess => {
                exprWeight(sa.index)
            }

            case _ => throw new Exception("Unknown expression type")
        }


        val currentWeight = idToStmt(id) match {
            case d: DefInstance => throw new Exception("DefInstance should not exists here")
            case d: DefRegister => throw new Exception("DefRegister should not exists here")
            case m: DefMemory => throw new Exception("DefMemory should not exists here")

            case st: Stop => 0
            case pr: Print => 0
            case EmptyStmt => 0

            case mw: MemWrite => 1 + exprWeight(mw.wrEn) + exprWeight(mw.wrData)
            case ru: RegUpdate => ru.expr.tpe match {
                case UIntType(IntWidth(w)) => if (w <= 64) 2 else (w.toInt + 63) / 64 + 1
                case SIntType(IntWidth(w)) => if (w <= 64) 2 else (w.toInt + 63) / 64 + 1
                case AsyncResetType => 2
                case _ => 0
            }

            case c: Connect => {
                val valueWeight = exprWeight(c.expr)
                val declWeight = c.loc.tpe match {
                    case UIntType(IntWidth(w)) => if (w <= 64) 2 else (w.toInt + 63) / 64 + 1
                    case SIntType(IntWidth(w)) => if (w <= 64) 2 else (w.toInt + 63) / 64 + 1
                    case AsyncResetType => 2
                    case _ => 0
                }
                declWeight + valueWeight
            }

            case d: DefNode => {
                val valueWeight = exprWeight(d.value)
                val declWeight = d.value.tpe match {
                    case UIntType(IntWidth(w)) => if (w <= 64) 2 else (w.toInt + 63) / 64 + 1
                    case SIntType(IntWidth(w)) => if (w <= 64) 2 else (w.toInt + 63) / 64 + 1
                    case AsyncResetType => 2
                    case _ => 0
                }
                valueWeight + declWeight
            }

            case _ => throw new Exception("Unknown IR type")
        }

        idToNodeWeight(id) = currentWeight
        currentWeight
    }

    def calculatePieceWeight(piece: BitSet) = {

        // TODO : is this correct?
        val pieceSinkNodes = piece.toSeq.filter { id => {
            (outNeigh(id).toSet intersect piece).isEmpty
        }
        } //  pieceSinkNodes储存所有sinkNode（没有出边的点）

        val visitedNodes = mutable.Set[NodeID]()


        def stmtWeight(sinkId: NodeID): Int = {

            if (visitedNodes.contains(sinkId)) 0 else {
                visitedNodes += sinkId

                if (idToNodeWeight(sinkId) == -1) {
                    idToNodeWeight(sinkId) = calculateNodeWeight(sinkId)
                }


                val currentWeight = idToNodeWeight(sinkId)

                currentWeight + ((inNeigh(sinkId) filter validNodes filter piece) map stmtWeight).sum
            }
        } //  调用calculateNodeWeight(sinkId)计算当前sinkId的节点权重，存储在idToNodeWeight中，最终当前节点的权重为idNodeWeight加上与当前节点相连的入边节点权重的和

        // Weight should be at least 1 to make KaHyPar happy
        (pieceSinkNodes map stmtWeight).sum + 1 + (piece.map(idToStmt).collect { case e: Stop => e }.size + piece.map(idToStmt).collect { case e: Print => e }.size) / 7
        //  所有sinknode权重和 + 1 + Stop和Print类型节点数量除以7
    }


    def updateHyperGraph(): Unit = {
        val pieceWeights = pieces.map(calculatePieceWeight) //  对所有piece进行权重计算
        // val pieceWeights = pieces.map(_.toSet.size)
        // each node in a piece has same treeIds so just pick any one
        val hePinCount = pieces.map { p => idToTreeID(p.head).size } //  计算每个piece中属于同一个tree的节点数量并存入hePinCount


        // Add nodes
        for (elem <- trees.indices) {
            val weight = pieceWeights(elem)
            val connectPieces = (trees(elem).map(idToPieceID) - elem).toSeq
            val connectPieceWeights = connectPieces.map { pid => {
                val pinCount = hePinCount(pid)
                val pieceWeight = pieceWeights(pid)
                (pieceWeight / pinCount)
            }
            }

            hg.addNode(elem, weight + connectPieceWeights.sum)
        } //  遍历trees中的所有节点，weight为一部分子树（构成piece）的权重，connectPieces包含了当前子树中包含的所有pieces，因为pieces中的节点可能有重合
        //  connectPieceWeights赋值为针对connectPieces中所有pieces计算出的权重，为当前elem子树的权重 + 当前子树中所有piece连接的边的数量/piece自己的权重总和

        // Add edges
        for (elem <- pieces.indices) {
            if (elem >= trees.length) {
                // For all edges
                val edgeWeight = pieceWeights(elem)
                val edgeNodes = idToTreeID(pieces(elem).head).to[ArrayBuffer]

                hg.addEdge(edgeNodes, edgeWeight)
            }
        } //  遍历pieces中所有piece，如果当前piece大于树的长度则表明在处理不属于该树的piece，edgeWeight为当前piece的权重，edgeNodes为与当前piece头节点所属的树节点，addEdge
    }


    def writeTohMetis(dir: String, fileName: String) = {
        val writer = new FileWriter(new File(dir, fileName))

        def writeLine(dat: Seq[Any]) = {
            writer write (dat.mkString(" ") + "\n")
        }

        // header
        writeLine(Seq(s"${hg.edges.length}", s"${hg.nodes.length}", "11"))

        // edges
        hg.edges.indices.foreach { eid => {
            // node id + 1 to make hMetis happy
            val edgeNodes = hg.edges(eid).map {
                _ + 1
            }
            val edgeWeight = hg.edgeWeight(eid)
            writeLine(Seq(edgeWeight) ++ edgeNodes)
        }
        }

        // nodes
        hg.nodeWeight.foreach { weight => {
            writeLine(Seq(weight))
        }
        }

        writer.close()
    }

}

object PartGraph {
    def apply(sg: StatementGraph): PartGraph = {
        val pg = new PartGraph
        pg.buildFromGraph(sg)
        pg
    }
}


class ThreadPartitioner(pg: PartGraph, opt: OptFlags) extends LazyLogging {


    val hmetis_input_filename = "parts.hmetis"

    // val kahypar_path = "/Users/xipingcong/Library/CloudStorage/OneDrive-个人/CXP/ICT/研2/故障仿真加速/essent/utils/bin/KaHyPar"
    val kahypar_path = "/home/congxiping/kahypar/build/kahypar/application/KaHyPar"
    //	val kahypar_path = "KaHyPar"
    val kahypar_config_filename = "KaHyPar.config"
    //  val kahypar_preset = "/Users/hwang/project/kahypar/config/km1_kKaHyPar_sea20.ini"

    val absOutputPath: String = if (java.nio.file.Paths.get(opt.outputDir()).isAbsolute) opt.outputDir() else (os.pwd / os.RelPath(opt.outputDir())).toString()

    val parts = ArrayBuffer[BitSet]()
    val parts_read = ArrayBuffer[ArrayBuffer[Int]]()
    val parts_write = ArrayBuffer[ArrayBuffer[Int]]()


    def collectNodeType(dat: Map[String, Int])(s: Statement): Seq[Nothing] = {
        s match {
            case b: Block => {
                b.stmts flatMap collectNodeType(dat)
            }
            case other => {
                val nodeName = other.getClass.getName()
                if (dat contains nodeName) {
                    dat(nodeName) += 1
                } else {
                    dat(nodeName) = 1
                }
            }
        }
        Seq()
        //    返回空的seq，目的是在dat参数中统计不同的node类型和个数，比如HashMap(firrtl.ir.DefWire -> 1, firrtl.ir.Connect -> 3, firrtl.ir.DefInstance -> 1)
    }


    def doOpt() = {


        logger.info("Collect trees")
        val startTime_tree = System.currentTimeMillis() // 获取当前系统时间的毫秒级别时间戳
        pg.initTrees() // 调用initTrees，类pg的一个成员方法
        val endTime_tree = System.currentTimeMillis() //  获取当前系统时间的毫秒级别时间戳
        val elapse_tree = (endTime_tree - startTime_tree) //  elapse_tree包含了initTrees执行所花费的时间
        logger.info(s"Done collect trees in $elapse_tree ms")

        logger.info(s"Found ${pg.sinkNodes.size} sink nodes")

        // Print out sink node type
        val sinkNodeDist = collection.mutable.Map[String, Int]()

        pg.sinkNodes.foreach { sinkId => {
            collectNodeType(sinkNodeDist)(pg.idToStmt(sinkId))
        }
        } //  统计PartGraph类对象pg中的node类型和数量

        logger.info("*****Sink Node Distribution*****")
        for ((k, v) <- sinkNodeDist) {
            logger.info(s"$k : $v")
        } //  打印PartGraph类对象pg中的node类型和数量
        logger.info("*****End Sink Node Distribution*****")


        logger.info("Collect pieces") // 调用initPieces，类pg的一个成员方法，并计算调用时间
        val startTime_pieces = System.currentTimeMillis()
        pg.initPieces() //  将电路结构中的sinkNodes进行划分归类，论文中的三角
        val endTime_pieces = System.currentTimeMillis()
        val elapse_pieces = (endTime_pieces - startTime_pieces)
        logger.info(s"Done collect pieces in $elapse_pieces ms")

        logger.info("Update hyper graph") // 调用updateHyperGraph，类pg的一个成员方法，并计算调用时间
        val startTime_hg = System.currentTimeMillis()
        pg.updateHyperGraph()
        val endTime_hg = System.currentTimeMillis()
        val elapse_hg = (endTime_hg - startTime_hg)
        logger.info(s"Done hyper graph updating in $elapse_hg ms")


        logger.info("Write to hMetis output file") // 调用writeTohMetis，类pg的一个成员方法，并计算调用时间
        val startTime_hmetis = System.currentTimeMillis()
        pg.writeTohMetis(absOutputPath, hmetis_input_filename)
        val endTime_hmetis = System.currentTimeMillis()
        val elapse_hmetis = (endTime_hmetis - startTime_hmetis)
        logger.info(s"Done output in $elapse_hmetis ms")


        logger.info("Call KaHyPar") // 调用hiKaHyPar方法，并计算调用时间
        val startTime_kahypar = System.currentTimeMillis()
        val metis_return_file = hiKaHyPar(opt.parallel)
        val endTime_kahypar = System.currentTimeMillis()
        val elapse_kahypar = (endTime_kahypar - startTime_kahypar)
        logger.info(s"KaHyPar spend $elapse_kahypar ms")

        logger.info("Parse result")
        parseMetisResult(metis_return_file)

        val part_weights = parts.map(pg.calculatePieceWeight)

        parts.indices.foreach { pid => {
            println(s"Pid: $pid, part size: ${parts(pid).size}, part weight: ${part_weights(pid)}")
        }
        }

        //  cxp
        println(pg.trees)
        println(pg.pieces)
        println(pg.idToStmt)

        // Print out weight calculation trace

        //    println("StartJSON")
        //    println("{")
        //    parts.indices.foreach{pid => {
        //      val trace = pg.calculatePieceWeight_Trace(parts(pid))
        //      println("    {")
        //      for ((k, v) <- trace) {
        //        println(s"""        \"${k}\" : ${v},  """)
        //      }
        //      println("    },")
        //    }}
        //    println("}")
        //    println("EndJSON")

        val totalNodeCount = parts.map(_.size).sum

        println(s"Total node count is ${totalNodeCount}, original statement graph has ${pg.validNodes.size} valid nodes")


        val partNodeCount = parts.reduce(_ ++ _).size
        val duplicateNodeCount = parts.map(_.size).sum - partNodeCount

        println(s"Total node counts (whole design) is $partNodeCount")
        println(s"Duplication stmt cost: ${duplicateNodeCount} (${(duplicateNodeCount.toFloat / partNodeCount.toFloat) * 100}%)")

        val smallestSize = parts.map(_.size).min
        val largestSize = parts.map(_.size).max
        println(s"Partition size: max: ${largestSize}, min: ${smallestSize}, avg: ${(partNodeCount + duplicateNodeCount) / parts.length}")


        val wholeDesignWeight = pg.calculatePieceWeight(parts.reduce(_ ++ _))
        val duplicateWeights = part_weights.sum - wholeDesignWeight
        println(s"Total node weight (whole design) is $wholeDesignWeight")
        println(s"Duplication weight cost: ${duplicateWeights} (${(duplicateWeights.toFloat / wholeDesignWeight.toFloat) * 100}%)")

        val lightestSize = part_weights.min
        val heaviestSize = part_weights.max
        println(s"Partition weight: max: ${heaviestSize}, min: ${lightestSize}, avg: ${(wholeDesignWeight + duplicateWeights) / parts.length}")


        val stmtsIdOrdered = TopologicalSort(pg)

        def isReadStmtId(id: NodeID) = pg.idToStmt(id) match {
            case dr: DefRegister => true
            case dm: DefMemory => true
            // EmptyStmt exists in source nodes but not actually a read
            // case EmptyStmt => true
            case _ => false
        }

        def isWriteStmtId(id: NodeID) = pg.idToStmt(id) match {
            case ru: RegUpdate => true
            case mw: MemWrite => true
            // Following IR exists in sink nodes not not actually a write
            //      case p: Print => true
            //      case s: Stop => true
            //      case c: Connect => true
            case _ => false
        }

        val readStmtIdsOrdered = stmtsIdOrdered.collect { case id if isReadStmtId(id) => id }
        val writeStmtIdsOrdered = stmtsIdOrdered.collect { case id if isWriteStmtId(id) => id }

        parts.foreach { part => {
            val part_source = part.flatMap(pg.inNeigh).toSet -- part
            val part_sink = part.filter(pg.outNeigh(_).isEmpty).toSet

            val part_read_ordered = readStmtIdsOrdered.filter(part_source.contains)
            val part_write_ordered = writeStmtIdsOrdered.filter(part_sink.contains)


            parts_read.append(part_read_ordered)
            parts_write.append(part_write_ordered)

        }
        }

        logger.info("Done")

    }


    def hiKaHyPar(desiredParts: Int) = {

        val kahypar_preset =
            """# general
              |mode=direct
              |objective=km1
              |seed=-1
              |cmaxnet=1000
              |vcycles=0
              |# main -> preprocessing -> min hash sparsifier
              |p-use-sparsifier=true
              |p-sparsifier-min-median-he-size=28
              |p-sparsifier-max-hyperedge-size=1200
              |p-sparsifier-max-cluster-size=10
              |p-sparsifier-min-cluster-size=2
              |p-sparsifier-num-hash-func=5
              |p-sparsifier-combined-num-hash-func=100
              |# main -> preprocessing -> community detection
              |p-detect-communities=true
              |p-detect-communities-in-ip=true
              |p-reuse-communities=false
              |p-max-louvain-pass-iterations=100
              |p-min-eps-improvement=0.0001
              |p-louvain-edge-weight=hybrid
              |# main -> coarsening
              |c-type=ml_style
              |c-s=1
              |c-t=160
              |# main -> coarsening -> rating
              |c-rating-score=heavy_edge
              |c-rating-use-communities=true
              |c-rating-heavy_node_penalty=no_penalty
              |c-rating-acceptance-criterion=best_prefer_unmatched
              |c-fixed-vertex-acceptance-criterion=fixed_vertex_allowed
              |# main -> initial partitioning
              |i-mode=recursive
              |i-technique=multi
              |# initial partitioning -> coarsening
              |i-c-type=ml_style
              |i-c-s=1
              |i-c-t=150
              |# initial partitioning -> coarsening -> rating
              |i-c-rating-score=heavy_edge
              |i-c-rating-use-communities=true
              |i-c-rating-heavy_node_penalty=no_penalty
              |i-c-rating-acceptance-criterion=best_prefer_unmatched
              |i-c-fixed-vertex-acceptance-criterion=fixed_vertex_allowed
              |# initial partitioning -> initial partitioning
              |i-algo=pool
              |i-runs=20
              |# initial partitioning -> bin packing
              |i-bp-algorithm=worst_fit
              |i-bp-heuristic-prepacking=false
              |i-bp-early-restart=true
              |i-bp-late-restart=true
              |# initial partitioning -> local search
              |i-r-type=twoway_fm
              |i-r-runs=-1
              |i-r-fm-stop=simple
              |i-r-fm-stop-i=50
              |# main -> local search
              |r-type=kway_fm_hyperflow_cutter_km1
              |r-runs=-1
              |r-fm-stop=adaptive_opt
              |r-fm-stop-alpha=1
              |r-fm-stop-i=350
              |# local_search -> flow scheduling and heuristics
              |r-flow-execution-policy=exponential
              |# local_search -> hyperflowcutter configuration
              |r-hfc-size-constraint=mf-style
              |r-hfc-scaling=16
              |r-hfc-distance-based-piercing=true
              |r-hfc-mbc=true
              |""".stripMargin

        // Write config file
        val writer = new FileWriter(new File(absOutputPath, kahypar_config_filename))
        writer write kahypar_preset
        writer.close()

        val kahypar_imbalance_factor = 0.015
        val kahypar_seed = -1

        val cmd = List(kahypar_path,
            "-h", (os.Path(absOutputPath) / hmetis_input_filename).toString(),
            "-k", desiredParts.toString,
            "-e", kahypar_imbalance_factor.toString,
            "-p", (os.Path(absOutputPath) / kahypar_config_filename).toString(),
            "--seed", kahypar_seed.toString,
            "-w", "true",

            // mandatory arguments, even already given in preset file
            "--mode", "direct",
            "--objective", "km1",
        )

        val r = os.proc(cmd).call(check = false)

        println("KayHyPar output:")
        println("*" * 50)

        print(r.out.string)
        println("*" * 50)

        assert(r.exitCode == 0, s"Return code is not 0, ${r.exitCode} received.")


        (os.Path(absOutputPath) / (hmetis_input_filename
                + ".part"
                + desiredParts.toString
                + ".epsilon"
                + kahypar_imbalance_factor.toString
                + ".seed"
                + kahypar_seed.toString
                + ".KaHyPar"
                )).toString()
    }


    def parseMetisResult(fileName: String) = {

        logger.info("Partitioner: Read " + fileName)

        val partResult = ArrayBuffer[Int]()

        val fileSource = Source.fromFile(fileName)
        fileSource.getLines.foreach { partID => {
            partResult += partID.toInt
        }
        }

        fileSource.close()


        val partCount = partResult.max + 1
        parts ++= ArrayBuffer.fill(partCount)(mutable.BitSet())

        partResult.zipWithIndex.foreach { case (partID, pieceID) => {
            if (pg.sinkNodes.indices.contains(pieceID)) {
                parts(partID) ++= pg.trees(pieceID)
            }
        }
        }
    }


}


object ThreadPartitioner {
    def apply(pg: PartGraph, opt: OptFlags) = {
        new ThreadPartitioner(pg, opt)
    }
}
package essent

import java.io.File

import scopt.OptionParser


case class OptFlags(
    firInputFile: File = null,
    regUpdates: Boolean = true,
    muxShadows: Boolean = true,
    zoneAct: Boolean = true,
    writeHarness: Boolean = false,
    dumpLoFirrtl: Boolean = false,
    trackAct: Boolean = false)

class ArgsParser {
  val parser = new OptionParser[OptFlags]("essent") {
    opt[Unit]("O0").action( (_, c) => c.copy(
        regUpdates = false,
        muxShadows = false,
        zoneAct=false)
    ).text("disable all optimizations")

    opt[Unit]("O1").action( (_, c) => c.copy(
        regUpdates = true,
        muxShadows = false,
        zoneAct=false)
    ).text("enable only optimizations without conditionals")

    opt[Unit]("O2").action( (_, c) => c.copy(
        regUpdates = true,
        muxShadows = true,
        zoneAct=false)
    ).text("enable conditional evaluation of mux inputs")

    opt[Unit]("O3").action( (_, c) => c.copy(
        regUpdates = true,
        muxShadows = true,
        zoneAct=true)
    ).text("enable all optimizations (default)")

    opt[Unit]("dump").action( (_, c) => c.copy(
        dumpLoFirrtl = true)
    ).text("dump low-firrtl prior to essent executing")

    opt[Unit]('h', "harness").action( (_, c) => c.copy(
        writeHarness = true)
    ).text("generate harness for Verilator debug API")

    opt[Unit]("activity-stats").action( (_, c) => c.copy(
        zoneAct = true,
        trackAct = true)
    ).text("print out zone activity stats")

    help("help").text("prints this usage text")

    // TODO: ensure given input file
    arg[File]("<file>").unbounded().action( (x, c) =>
      c.copy(firInputFile = x) ).text(".fir input file")
  }

  def getConfig(args: Seq[String]): Option[OptFlags] = parser.parse(args, OptFlags())
}
essent (essential signal simulation enabled by netlist transformations) [![Build Status](https://github.com/ucsc-vama/essent/actions/workflows/scala-ci.yml/badge.svg)](https://github.com/ucsc-vama/essent/actions/workflows/scala-ci.yml)
================================================================================

This is a beta of _essent_, a high-performance RTL simulator generator. Essent operates on hardware designs in the form of [firrtl](https://github.com/freechipsproject/firrtl), an IR for hardware with a well-defined [spec](https://github.com/chipsalliance/firrtl-spec/releases/latest/download/spec.pdf). Given a hardware design in firrtl, essent emits C++ that can be compiled to make a fast simulator of the design. Essent provides several optimizations to improve performance, and they can be turned on or off with command line flags. A typical flow using the tool will: use essent to make C++ from the firrtl input, write a C++ harness for the emitted code, compile everything, and finally run the simulation. To make a simulator with essent, you will need a JVM (compatible with Scala), and a C++ compiler capable of C++11.

Essent incorporates a number of optimizations to deliver great performance. To learn more about essent as a whole as well as its optimizations, the [talk](https://woset-workshop.github.io/Videos/2021/a23-video.mp4) and [paper](https://woset-workshop.github.io/PDFs/2021/a23.pdf) from [WOSET 2021](https://woset-workshop.github.io) give a good overview. Our other publications (below) dive into the details of the optimizations and bottlenecks of current host CPUs.

Without optimization, essent will generate a simulator that is a very literal translation of the firrtl design. Essent flattens the design, and typically represents each firrtl statement with a single line of C++. Most signals are ephemeral and are locally scoped, which gives the compiler the maximum flexibility to optimize them. Signals that must persist between cycles, such as state elements (registers or memories) or external IOs, are declared in structs which match the module hierarchy. Some optimizations require additional signals to persist between cycles, and these variables are declared effectively globally. Long chains of simple connect statements (no other modifications to signals) will be compressed down to just the chain endpoints. Without optimization, each register has two variables associated with it, and they represent the current value and the next value of the register (two-phase update).


Running essent
--------------------------------------------------------------------------------

Essent is written in Scala, which runs on the JVM. To run essent, it's easiest to use the included `essent` bash script, which launches the JVM appropriately with `essent.jar` (after building it with `sbt assembly`). For the optimizations, essent uses optimization levels like a compiler, so a higher optimization level includes all optimizations from lower levels.

+ `O0` - All optimizations disabled, except compressing out long chains of connect statements.
+ `O1` - When possible, registers will be represented by only one variable instead of two (single-phase update).
+ `O2` - Muxes are represented with `if/else` blocks instead of ternary statements `?`. As many signals as possible are pulled into the if or else blocks. If both the if and else blocks will be trivially one statement, the optimization will not be performed.
+ `O3` - Attempts to exploit low activity factors by reusing results from the previous cycle. The design will be partitioned, and each partition will get its own eval function. If none of the inputs to a partition change, its outputs will be reused. These partitions can include state elements.


Example invocations:

    $ ./essent --help
    $ ./essent -O1 my_design.fir


Interfacing with the generated code
--------------------------------------------------------------------------------

Essent will generate a single `.h` file, with the name of the firrtl circuit. We recommend writing a single `.cc` file to harness the design. Essent uses templated types `UInt` and `SInt` to represent their corresponding firrtl types, and these types are defined in the companion [firrtl-sig](https://github.com/ucsc-vama/firrtl-sig) repo. The harness file should: include the appropriate headers (UInt, SInt, and design's .h file), instantiate the design, and call `eval` on it for the desired number of cycles. The design will automatically randomly initialize itself when the object is created. Reset is typically an input to the circuit. This version of essent does not support multiple clocks or any sort of logic on clock signals.

A call to the eval function for a design progresses the design by at most one cycle, and takes three boolean arguments:

+ `update_registers` - If true, all state elements will be updated to their new value at the end of eval's invocation. If false, the state elements will not be updated, but the rest of the computation will be performed. This choice is helpful when communicating to outside the design, especially if the external paths are combinational.
+ `verbose` - If true, print statements will print their results. If false, their output will be suppressed.
+ `done_reset` - Sometimes during the reset process, some functionality can be triggered. When done_reset is false, prints and stops (assertions) will not be triggered.

Example harness snippet:
```c++
#include "MyDesign.h"

int main() {
  MyDesign* dut = new MyDesign;
  // Reset and flush design
  dut->reset = UInt<1>(1);
  dut->eval(false, false, false);
  for (int i = 0; i < 5; i++) {
    dut->eval(true, false, false);
  dut->reset = UInt<1>(0);
  dut->eval(false, false, false);
  // Actual simulation for 10k cycles
  for (int i = 0; i < 10000; i++) {
    dut->eval(true, false, true);
  delete dut;
}
```

Compiling everything
--------------------------------------------------------------------------------

Since essent emits a single header file for a firrtl circuit, the entire simulator is typically produced by a single compilation. Be sure the `firrtl-sig` directory is in the include path.

An example compile command:

    $ g++ -O3 -std=c++11 -I./firrtl-sig design-harness.cc -o simulator
    
On macOS, when using clang, we also found `-fno-slp-vectorize` to improve compile time for large designs, and `-fbracket-depth=1024` allows it to handle designs with deeply nested muxes.

Running with waveform
--------------------------------------------------------------------------------
To generate waveform with ESSENT you can choose either format , VCD or FST and then you can view using gtkwave waveform viewer.

```
Install gtkwave by entering the following commands in the terminal:

sudo apt update
sudo apt install gtkwave
```
Compile: 
+ `withVCD` - This flag enabled generates waveform in VCD format , which can be viewed using gtkwave; disabled by default 
+ `withFST` - This flag enabled generates waveform in FST format , which can be viewed using gtkwave; disabled by default

Example invocations:

```
    $ ./essent -O3 -withVCD my_design.fir
```

Requitered update in Harness file:

Before we start the simulation , we need to add the below to the testbench file, which generates the header part of the VCD or FST. 

  top->genWaveHeader();
   
Running:
We do not need any extra argument to run with VCD , it is same as running essent without VCD.

with FST:
Please install vcd2fst before we run with FST.
Instead of going into generating FST from scratch, we are using vcd2fst tool to convert our VCD related data into streams and compress parallely to generate 
in a form of FST format, which is 135x smaller than VCD file. 
```

clang++ -O3 -std=c++11 -fno-slp-vectorize -fbracket-depth=1024 -Iriscv/include -I../firrtl-sig emulator.cc -o emulator_fst -Lriscv/lib -Wl,-rpath,riscv/lib -lfesvr -lpthread

./emulator_fst dhrystone.riscv | **vcd2fst -p -v /dev/stdin -f** dump_mydesign.fst"
```


Examples
--------------------------------------------------------------------------------
We provide examples showing how essent can be integrated in [Rocket Chip](https://github.com/ucsc-vama/essent-rocket-demo) and [other projects](https://github.com/ucsc-vama/essent-chisel-gallery).


Contributors
--------------------------------------------------------------------------------
The ESSENT project, including supporting infrastructure beyond this repo, has benefited from contributions by:
+ Scott Beamer
+ Priyanka Dutta
+ Thomas Nijssen
+ Krishna Pandian
+ Jinsung Park
+ Haoyuan Wang
+ Kyle Zhang


Publications
--------------------------------------------------------------------------------

**Efficiently Exploiting Low Activity Factors to Accelerate RTL Simulation**  
Scott Beamer and David Donofrio  
_Design Automation Conference (DAC), San Francisco, July 2020_  
(preferred way to cite codebase)

**A Case for Accelerating Software RTL Simulation**  
Scott Beamer  
_IEEE Micro, 2020_

**ESSENT: A High-Performance RTL Simulator**  
Scott Beamer, Thomas Nijssen, Krishna Pandian, Kyle Zhang  
_Workshop on Open-Source EDA Technology (WOSET), at International Conference on Computer-Aided Design (ICCAD), 2021_

Legal
--------------------------------------------------------------------------------

Essential Signal Simulation Enabled by Netlist Transforms (ESSENT) Copyright (c) 2019, The Regents of the University of California, through Lawrence Berkeley National Laboratory (subject to receipt of any required approvals from the U.S. Dept. of Energy). All rights reserved.

If you have questions about your rights to use or distribute this software, please contact Berkeley Lab's Intellectual Property Office at IPO@lbl.gov.

NOTICE. This Software was developed under funding from the U.S. Department of Energy and the U.S. Government consequently retains certain rights. As such, the U.S. Government has been granted for itself and others acting on its behalf a paid-up, nonexclusive, irrevocable, worldwide license in the Software to reproduce, distribute copies to the public, prepare derivative works, and perform publicly and display publicly, and to permit other to do so.

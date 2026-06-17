# Diagnostic Patches for shortXorBig Regression Investigation

Each patch adds targeted logging to a specific C2 compiler phase.
Apply individually — they are independent and should not be combined
(they modify different files).

## Patch Summary

| # | File Modified | Flag Required | Grep Pattern | What It Shows |
|---|---------------|---------------|--------------|---------------|
| 01 | loopnode.cpp | compileonly | `[reassoc-extract]` | Values extracted from XOR chain during reassociation |
| 02 | loopnode.cpp | PrintIdeal | `[reassoc-chain]` | Full inner chain structure after reassociation |
| 03 | loopnode.cpp | PrintIdeal | `[reassoc]` | Marker when reassociation completes (before IGVN) |
| 04 | mulnode.cpp | PrintIdeal | `[identity-shift]` | When IdentityIL eliminates `(x<<N)>>N` |
| 05 | mulnode.cpp | PrintIdeal | `[ideal-shift]` | When Ideal eliminates `(LoadS<<16)>>16` or `(LoadB<<24)>>24` |
| 06 | lcm.cpp | TraceOptoOutput | `[sched]` | Every node picked by schedule_local() with scores |
| 07 | lcm.cpp | TraceOptoOutput | `[sched-pressure]` | Pressure heuristic score computation details |
| 08 | matcher.cpp | PrintIdeal | `[matcher]` | Operand pattern selected for each LoadS/LoadB |

## Existing Flags (no patches needed)

These built-in flags were also used in the investigation:

| Flag | Build | What It Shows |
|------|-------|---------------|
| `-XX:+TraceOptoPipelining` | fastdebug | Block dumps with ready_cnt for all nodes |
| `-XX:+TraceLoopOpts` | fastdebug | Loop optimization decisions (unrolling, peeling) |
| `-XX:+PrintIdeal` | fastdebug | C2 Ideal IR graph |
| `-XX:+PrintOptoAssembly` | fastdebug | Machine code after register allocation |
| `-XX:+PrintOptoPeephole` | fastdebug | Peephole rule firings |
| `-XX:+TraceOptoOutput` | fastdebug | Code generation output tracing |
| `-XX:CompileCommand=PrintAssembly,*Class::method` | fastdebug | Final generated assembly |

## How to Apply

```bash
# Apply a single patch (from the jdk root):
cd src/hotspot/share/opto
patch -p0 < ../../../../patches/01-reassoc-extract-logging.patch

# Rebuild just hotspot:
cd /path/to/jdk
make CONF=linux-x86_64-server-fastdebug hotspot JOBS=$(nproc)

# Run via JMH:
java -jar benchmarks.jar "VectorReduction2.NoSuperword.shortXorBig" \
    -p SIZE=2048 -p seed=0 -f 1 -wi 3 -i 1 -w 1s -r 1s \
    -jvmArgs "-XX:+PrintIdeal -XX:CompileCommand=compileonly,*VectorReduction2::shortXorBig"

# Revert:
cd src/hotspot/share/opto
patch -R -p0 < ../../../../patches/01-reassoc-extract-logging.patch
```

Note: Patches 01-03 modify `loopnode.cpp` — apply only one at a time,
or combine manually. Similarly, patches 04-05 both modify `mulnode.cpp`,
and patches 06-07 both modify `lcm.cpp`.

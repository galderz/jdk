---
name: hotspot-c2-debug-scheduling
description: Debug C2 instruction scheduling (GCM/LCM) — trace node placement, ready lists, pressure heuristics
whenToUse: When investigating instruction ordering issues, register pressure problems, or performance regressions related to how C2 orders instructions within a basic block
---

# C2 Instruction Scheduling Debugging

## Quick Start

Dump the full scheduling trace (block structure, ready counts, scheduling order):
```bash
java -XX:+TraceOptoPipelining -XX:CompileCommand=compileonly,*Class::method ...
```

## Key Flags

| Flag | Build | What it shows |
|------|-------|--------------|
| `-XX:+TraceOptoPipelining` | fastdebug | Block dumps with `ready cnt` per node, scheduling order |
| `-XX:+TraceOptoOutput` | fastdebug | Code generation output (use for custom logging hooks) |
| `-XX:+PrintOptoAssembly` | fastdebug | Machine code after register allocation, before peephole |
| `-XX:-OptoRegScheduling` | any | Disable register-pressure-aware scheduling (for comparison) |

## Understanding `ready_cnt`

Each node has a `ready_cnt` = number of block-local inputs not yet scheduled.
A node enters the ready list when `ready_cnt` reaches 0.

```
#   ready cnt:  0  latency:  0   451: loadS    ← ready immediately (all inputs external)
#   ready cnt:  1  latency:  0   482: loadB    ← waiting for 1 block-local input
#   ready cnt:  2  latency:  0   306: xorI     ← waiting for 2 inputs (chain dependency)
```

Computed in `lcm.cpp:1009-1017` by counting inputs in the same basic block.

## The Scheduling Decision (`select()` in lcm.cpp)

`schedule_local()` picks one node per round from the ready list using `select()`:

1. **`n_choice`**: highest wins (3=must_clone predecessor, 2=normal, 1=deferred)
2. **`n_latency`**: highest wins (critical path distance to block end)
3. **`n_score`**: highest wins (initially `n->req()`, modified by pressure heuristic)

### Pressure Heuristic (lcm.cpp:669-689)

When `OptoRegScheduling` is enabled and pressure exceeds the high limit:

```cpp
if (int_pressure < 0)  // scheduling this node REDUCES pressure
    n_score = (accumulated_best + n_score) - int_pressure;  // BOOSTED
else if (int_pressure > 0)  // scheduling this node INCREASES pressure
    n_score = 1;  // DEMOTED
```

`accumulated_best` is the best score from earlier candidates in the SAME round.
This means a 1-point difference in `accumulated_best` can cascade through all
subsequent scheduling decisions.

## Diagnosing Clustering vs Interleaving

When a reassociated XOR/Add chain clusters all operations at the end of the
loop body instead of interleaving with computation:

1. Check `ready_cnt` for the chain's first XorI node
   - `ready_cnt=2` with small ready list → clustering (XorI is last to be ready)
   - Lower `ready_cnt` with large ready list → interleaving possible

2. Check what gates the loads:
   - `ready_cnt=0` for loads → all loads ready immediately → parallel computation → clustering
   - `ready_cnt=1` for loads → loads gated by ConvI2L or similar → sequential → interleaving

3. Count ConvI2L nodes in the main loop block:
   - 0 ConvI2L → loads take Phi directly (ConvI2L folded into addressing) → ready_cnt=0
   - 1+ ConvI2L → loads depend on it → ready_cnt=1

## Key Source Files

| File | What it does |
|------|-------------|
| `gcm.cpp` | Global Code Motion — `schedule_early()` / `schedule_late()` — places nodes in blocks |
| `lcm.cpp` | Local Code Motion — `schedule_local()` / `select()` — orders within blocks |
| `chaitin.cpp` | Register allocator (Chaitin-Briggs graph coloring) |

## Adding Custom Logging

Add to `select()` in `lcm.cpp` right before `worklist.map((uint)idx, worklist.pop())`:

```cpp
if (TraceOptoOutput && n->is_Mach()) {
    tty->print("[sched] %s(%d) choice=%d lat=%d score=%d(req=%d) ready=%d\n",
               n->Name(), n->_idx, choice, latency, score, n->req(),
               worklist.size());
}
```

Rebuild with `make CONF=... hotspot JOBS=$(nproc)` (fast incremental).

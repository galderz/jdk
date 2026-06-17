---
name: hotspot-c2-debug-methodology
description: Systematic methodology for debugging C2 JIT performance regressions — the step-by-step process, what to check at each stage, and how to narrow down root causes
whenToUse: When starting a C2 performance regression investigation, or when unsure which debugging approach to use next
---

# C2 Performance Regression Debugging Methodology

## Phase 1: Reproduce and Isolate

### 1. Confirm with JMH (not standalone)

Standalone reproducers often DON'T replicate JMH behavior because:
- Different compilation tier/mode (OSR vs standard)
- Blackhole prevents dead-code elimination
- Separate fork per benchmark controls compilation context
- Instance vs static fields change register pressure

Always verify findings against the actual JMH benchmark.

### 2. Isolate the optimization

Disable suspect optimizations one at a time:
```bash
-XX:LoopUnrollLimit=1          # Disable loop unrolling
-XX:-UseSuperWord              # Disable auto-vectorization
-XX:-OptoRegScheduling         # Disable pressure-aware scheduling
```

If the regression disappears with a flag, that optimization interacts with
the change. "Interacts with" ≠ "causes" — the root cause may be elsewhere.

### 3. Build both baseline and patch

```bash
# Baseline (origin/master)
git worktree add ../jdk-baseline origin/master
cd ../jdk-baseline && bash configure --with-debug-level=fastdebug && make images

# Patch (current branch)
bash configure --with-debug-level=fastdebug && make images
```

For benchmarking, also build release: `--with-debug-level=release`.
Fastdebug has assertion overhead that skews timing.

## Phase 2: Compare IR and Assembly

### 4. Compare Ideal IR

```bash
java -XX:+PrintIdeal -XX:CompileCommand=compileonly,*Class::method ...
```

Count key node types (LShiftI, RShiftI, XorI, MulI, AddI).
If counts match → IR is identical → difference is post-IR.

**CAUTION:** PrintIdeal outputs multiple compilations with overlapping node IDs.
Always check for conflicts before cross-referencing nodes.

### 5. Compare generated assembly

Count instruction types in the hot C2 compilation:
```bash
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm.txt | grep -c 'movd[lq]'
```

Key metrics:
- `movdl`/`movdq` count = XMM spill proxy (register pressure)
- `leal` count = coalescing failures
- `<< #` count = scaled addressing

### 6. Compare instruction scheduling pattern

Use `-XX:+TraceOptoPipelining` to see `ready_cnt` per node and block structure.
Look for differences in:
- Which block the main loop is in
- `ready_cnt` values for load nodes (0 = immediately ready, 1 = gated)
- ConvI2L node placement (in-block vs out-of-block)

## Phase 3: Trace the Root Cause

### 7. Work backwards from the symptom

```
Assembly difference (XMM spills, clustering)
    ↑ caused by
Register allocation decisions
    ↑ caused by
Instruction scheduling order (LCM)
    ↑ caused by
Node readiness timing (ready_cnt)
    ↑ caused by
Machine node structure (ConvI2L folding)
    ↑ caused by
Instruction selection (.ad operand matching)
    ↑ caused by
Ideal graph structure (AddP tree shape)
    ↑ caused by
Loop optimization (unrolling + reassociation)
```

At each level, add targeted logging to VERIFY (not assume) the hypothesis.

### 8. Progressive disclosure with logging

Start with **existing flags** (no code changes needed):
1. `-XX:+TraceLoopOpts` — loop optimization decisions
2. `-XX:+TraceOptoPipelining` — scheduling block dumps
3. `-XX:+PrintOptoAssembly` — machine code after register allocation
4. `-XX:+PrintOptoPeephole` — peephole conversions

Then add **targeted logging** (requires fastdebug rebuild):
5. Diagnostic prints in specific C2 functions (guard with `if (PrintIdeal)`)
6. Rebuild with `make hotspot` (incremental, ~10 seconds)

### 9. Verify at each stage

Every claim should be backed by data:
- "The IR is identical" → verify node counts match
- "The scheduling differs" → show ready_cnt values
- "ConvI2L is folded for short but not byte" → show matcher operand selection
- "The predicate fails" → log the predicate evaluation

## Anti-Patterns

- **Don't trust standalone reproducers** for JMH-level regressions
- **Don't assume PrintIdeal node IDs** are unique across compilations
- **Don't assume the IR difference** is the cause — the IR may be identical with differences emerging later
- **Don't assume a theory without data** — each step should produce verifiable evidence
- **Don't modify generated files** expecting them to persist — `dfa_x86.cpp` is regenerated on rebuild
- **Don't compare fastdebug timings** against release — assertion overhead is significant

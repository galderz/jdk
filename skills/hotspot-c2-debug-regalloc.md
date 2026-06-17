---
name: hotspot-c2-debug-regalloc
description: Debug C2 register allocation — trace spills, XMM moves, peephole lea conversions, register pressure
whenToUse: When investigating register spilling, XMM register usage, performance regressions from register pressure, or addl vs leal instruction selection
---

# C2 Register Allocation Debugging

## Quick Start

Count XMM spill moves in the generated assembly (proxy for register pressure):
```bash
java -XX:CompileCommand=PrintAssembly,*Class::method ... 2>&1 | \
    sed -n '/Compiled method (c2).*%/,/Compiled method/p' | \
    grep -c 'movd[lq]'
```

## Key Flags

| Flag | Build | What it shows |
|------|-------|--------------|
| `-XX:CompileCommand=PrintAssembly,*Class::method` | fastdebug | Final generated machine code |
| `-XX:+PrintOptoAssembly` | fastdebug | Code after register allocation, before peephole |
| `-XX:+PrintOptoPeephole` | fastdebug | Peephole rule firings (addl→leal conversions) |
| `-XX:+TraceSpilling` | fastdebug | Verbose spill decisions from Chaitin-Briggs |

## Instruction Mix Analysis

Count key instruction types in the hot C2 compilation to diagnose register pressure:

```bash
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_output.txt | \
    grep -c 'movd[lq]'    # XMM register moves (spill proxy)
```

| Instruction | What it means |
|------------|--------------|
| `movdl`/`movdq` | GP↔XMM register move — value spilled to XMM because GP registers exhausted |
| `leal` | Non-destructive add — register allocator couldn't coalesce (input still live) |
| `addl` | Destructive add — input register freed after operation |
| `movswl`/`movsbl` | Sign-extension (I2S/I2B narrowing) |

## Peephole Optimization (x86.ad:25546)

The peephole converts `movl + addl` → `leal` when register allocation couldn't
coalesce a copy with the add destination. Rule #0 in `PrintOptoPeephole`:

```
peephole %{
    peepmatch (addI_rReg);
    peepprocedure (lea_coalesce_reg);  // src/hotspot/cpu/x86/peephole_x86_64.cpp:376
    peepreplace (leaI_rReg_rReg_peep());
%}
```

More peephole #0 firings = more register pressure. Compare between methods:
```bash
java -XX:+PrintOptoPeephole -XX:CompileCommand=compileonly,... 2>&1 | \
    grep "peephole" | sort | uniq -c | sort -rn
```

## XOR Chain Clustering Pattern

After reassociation of a 16x-unrolled loop, the XOR chain should theoretically
compute all values in parallel (clustering). If the scheduler interleaves
XorI with computation instead, register pressure stays low.

Visualize the scheduling pattern:
```python
# X=XOR M=MUL A=ADD n=narrowing .=XMM_move
# Clustered (bad for registers):  ...MAnMAMAnXXXXXXXX.X.X.X.X...
# Interleaved (good for registers): ...MAnXnnnMAMAnXnnnMAMAnX...
```

## Scaled vs Unscaled Addressing and Register Pressure

Array element size affects x86 SIB addressing, which affects ConvI2L folding:

| Element | SIB addressing | ConvI2L folded? | Load ready_cnt |
|---------|---------------|-----------------|----------------|
| byte (1B) | `[base + long_index + disp]` | Depends on compressed oops | Often 1 |
| short (2B) | `[base + int_index*2 + disp]` | Yes (indPosIndexScaleOffset) | Often 0 |
| int (4B) | `[base + int_index*4 + disp]` | Yes (indPosIndexScaleOffset) | Often 0 |

When ConvI2L is NOT folded → separate `convI2L_reg_reg` in block → loads depend
on it (`ready_cnt=1`) → loads trickle into ready list → sequential scheduling.

When ConvI2L IS folded → loads take Phi directly (`ready_cnt=0`) → all loads
immediately ready → parallel scheduling.

## Key Source Files

| File | What it does |
|------|-------------|
| `chaitin.cpp` | Chaitin-Briggs register allocator — graph coloring, spill decisions |
| `lcm.cpp` | LCM scheduling with pressure heuristic (`select()`, `adjust_register_pressure()`) |
| `peephole_x86_64.cpp` | Peephole optimizer — `lea_coalesce_helper()` converts mov+add to lea |
| `x86.ad:25546` | Peephole rules — `addI_rReg → leaI_rReg_rReg_peep` |

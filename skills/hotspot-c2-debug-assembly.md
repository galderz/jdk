---
name: hotspot-c2-debug-assembly
description: Analyze C2-generated x86 assembly — instruction patterns, SIB addressing, spill identification, loop body analysis
whenToUse: When examining generated machine code for performance issues, comparing assembly between methods or JDK versions, or identifying register spilling patterns
---

# C2 Assembly Analysis

## Quick Start

Dump assembly for a specific method:
```bash
# Fastdebug JDK (has built-in disassembler):
java -XX:CompileCommand=PrintAssembly,*Class::method ...

# Release JDK (needs hsdis plugin):
java -XX:+UnlockDiagnosticVMOptions -XX:CompileCommand=PrintAssembly,*Class::method ...
```

## Finding the Hot Compilation

Multiple compilations appear in the output (C1 tier 3, C2 OSR, C2 standard).
The hot one depends on context:

```
Compiled method (c1) 118  498 %     3   Class::method   ← C1 OSR (tier 3)
Compiled method (c2) 155  502 %     4   Class::method   ← C2 OSR (tier 4)
Compiled method (c2) 182  504       4   Class::method   ← C2 Standard (tier 4)
```

- `%` = OSR (On-Stack Replacement)
- JMH measurement uses the **standard** compilation (no `%`)
- Standalone benchmarks often use **OSR** compilation

Extract just the hot C2 compilation:
```bash
sed -n '/Compiled method (c2).*%/,/Compiled method/p' output.txt  # OSR
sed -n '/Compiled method (c2)[^%]*$/,/Compiled method/p' output.txt  # Standard
```

## x86 SIB Addressing Modes

SIB = Scale-Index-Base: `[base + index * scale + displacement]`

| Array type | Scale | Example instruction | ConvI2L folded? |
|-----------|-------|-------------------|----------------|
| `byte[]` | 1 | `movsbl R, [base + index + #16]` | Usually not (compressed oops) |
| `short[]` | 2 | `movswl R, [base + index << #1 + #16]` | Usually yes |
| `int[]` | 4 | `movl R, [base + index << #2 + #16]` | Usually yes |
| `long[]` | 8 | `movq R, [base + index << #3 + #16]` | Usually yes |

Scale=1 (byte) means no explicit scaling in SIB — the `<< #N` is absent.

## Instruction Pattern Visualization

Map key instruction types to single characters for pattern analysis:

```python
tags = []
for line in assembly:
    if 'xorl' in line: tags.append('X')
    elif 'imul' in line: tags.append('M')
    elif 'addl' in line: tags.append('A')
    elif 'movswl' in line or 'movsbl' in line: tags.append('n')  # narrowing
    elif re.search(r'movd[lq]', line): tags.append('.')  # XMM move
print(''.join(tags))
```

Example patterns:
```
Clustered XORs (high reg pressure):  ...MAnMAMAn.XXXXXXXX.X.X.X.X...
Interleaved XORs (low reg pressure): ...MAnXnnnMAMAnXnnnMAMAnXnnn...
```

## Counting Key Instructions

```bash
# From the hot C2 compilation:
HOT=$(sed -n '/Compiled method (c2).*%/,/Compiled method/p' output.txt)
echo "$HOT" | grep -c 'xorl'      # XOR operations
echo "$HOT" | grep -c 'imul'      # Multiplications
echo "$HOT" | grep -c 'movd[lq]'  # XMM moves (spill proxy)
echo "$HOT" | grep -c 'movswl'    # I2S narrowing
echo "$HOT" | grep -c 'movsbl'    # I2B narrowing
echo "$HOT" | grep -c 'leal'      # Non-destructive add (pressure indicator)
echo "$HOT" | grep -c '<< #'      # Scaled index loads
```

## Identifying Register Spills

**XMM register moves** (`movdl`, `movdq`) between GP and XMM registers indicate
the register allocator ran out of GP registers and is using XMM as overflow:

```asm
movdl   XMM9, R11      ← spill R11 to XMM9
...                     ← other computation
movdl   R11, XMM9      ← reload from XMM9
```

**Stack spills** (`mov ... [rsp+N]`) are even more expensive:

```asm
movl    [rsp + #16], R8   ← spill to stack
...
movl    R8, [rsp + #16]   ← reload from stack
```

## JMH Assembly Dumping

To get assembly from the actual JMH compilation context (not standalone):
```bash
java -jar benchmarks.jar "BenchmarkName" \
    -f 1 -wi 3 -i 1 -w 1s -r 1s \
    -jvmArgs "-XX:CompileCommand=PrintAssembly,*Class::method"
```

Note: use a **fastdebug** JDK for the JMH fork to get disassembly.
The `-jvmArgs` flag passes the argument to the forked JVM.

---
name: hotspot-c2-debug-ir
description: Debug C2 JIT compiler Ideal IR — dump graphs, trace optimizations, compare compilations
whenToUse: When investigating C2 compilation issues at the Ideal graph level (before instruction selection)
---

# C2 Ideal IR Debugging

## Quick Start

Dump the Ideal graph for a specific method:
```bash
java -XX:+PrintIdeal -XX:CompileCommand=compileonly,*ClassName::methodName ...
```

## Key Flags

| Flag | Build | What it shows |
|------|-------|--------------|
| `-XX:+PrintIdeal` | fastdebug | Final Ideal graph after all optimizations |
| `-XX:+TraceLoopOpts` | fastdebug | Loop optimization decisions (unrolling, peeling, partial peeling) |
| `-XX:+TraceIterativeGVN` | fastdebug | IGVN transformations (value numbering, identity, ideal rules) |
| `-XX:CompileCommand=compileonly,*Class::method` | any | Restrict C2 to one method (cleaner output) |
| `-XX:LoopUnrollLimit=N` | any | Control unrolling (1=disable, useful for isolating effects) |

## Common Pitfalls

### PrintIdeal outputs MULTIPLE compilations with overlapping node IDs

C2 compiles the same method multiple times (OSR + standard). Both appear in
the same PrintIdeal output stream, and **node IDs reset** between compilations.
Node 500 in the first compilation is a DIFFERENT node from node 500 in the second.

Detect conflicts:
```python
node_types = {}
for line in open('ideal_output.txt'):
    m = re.match(r'\s*(\d+)\s+(\w+)\s+===', line)
    if m and m.group(1) in node_types and node_types[m.group(1)] != m.group(2):
        print(f"CONFLICT: node {m.group(1)} is {node_types[m.group(1)]} AND {m.group(2)}")
    if m:
        node_types[m.group(1)] = m.group(2)
```

### OSR vs Standard compilation

JMH calls methods repeatedly. The **standard** compilation (no `%` in compile log)
is what runs during measurement. The **OSR** compilation (has `%`) runs during
warmup when entering a hot loop mid-execution.

Identify from compile log:
```
Compiled method (c2) 432   25 %     4    ClassName::method @ 4   ← OSR (has %)
Compiled method (c2) 477   26       4    ClassName::method       ← Standard (no %)
```

### Ideal graph node format

```
NodeID  Type  === in(0) in(1) in(2) ...  [[ out1 out2 ... ]]  #type  !jvms: ...
```
- `in(0)` = control input (often `_` for data nodes)
- `[[ ... ]]` = output nodes (who uses this node)
- `#type` = type annotation (e.g., `#int`, `#short`, `#long:>=0`)
- `!jvms:` = Java Virtual Machine State (source location)

## Key IGVN Rules to Know

### RShiftI sign-extension elimination

`mulnode.cpp:1240` — `RShiftNode::IdentityIL()`:
Eliminates `(x << N) >> N` when `type(x)` already fits in the sign-extended range.

`mulnode.cpp:1322` — `RShiftINode::Ideal()`:
Eliminates `(LoadS << 16) >> 16` and `(LoadB << 24) >> 24` specifically.

### XorI type inference

`addnode.cpp:1151` — `XorINode::add_ring()`:
Uses `RangeInference::infer_xor()` for bit-level type narrowing through XOR.
XOR of two byte-range values stays in byte range; same for short.

## Tracing Specific Optimizations

To see when a specific Ideal/Identity rule fires, add logging inside the rule
body guarded by `if (PrintIdeal)`. Rebuild with `make hotspot` (fast incremental).

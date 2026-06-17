---
name: hotspot-c2-debug-instruction-selection
description: Debug C2 instruction selection (ADLC matcher) — trace which .ad operand patterns match, DFA labeling, ConvI2L folding
whenToUse: When investigating which x86 machine instructions are selected for ideal nodes, memory operand matching (indPosIndex vs indIndex), or ConvI2L folding into addressing modes
---

# C2 Instruction Selection (ADLC Matcher) Debugging

## Quick Start

The ADLC matcher converts Ideal nodes to machine nodes using patterns from
`src/hotspot/cpu/x86/x86.ad`. The matcher runs a DFA (Deterministic Finite
Automaton) to find the lowest-cost match for each node tree.

## Understanding Memory Operand Matching

Array loads match `loadS`/`loadB`/`loadI` instructions, each taking a `memory`
operand. The `memory` opclass includes many variants (x86.ad:6240):

```
opclass memory(indirect, indOffset8, indOffset32, indIndexOffset, indIndex,
               indIndexScale, indPosIndexScale, indIndexScaleOffset,
               indPosIndexOffset, indPosIndexScaleOffset,
               indCompressedOopOffset, ...)
```

### Key Operand Patterns

| Pattern | Takes | Folds ConvI2L? | SIB Encoding |
|---------|-------|---------------|-------------|
| `indPosIndexScaleOffset` (5750) | `rRegI idx` | YES | `[base + idx*scale + off]` |
| `indPosIndexOffset` (5733) | `rRegI idx` | YES | `[base + idx + off]` |
| `indIndexScaleOffset` (5717) | `rRegL lreg` | NO | `[base + lreg*scale + off]` |
| `indIndexOffset` (5653) | `rRegL lreg` | NO | `[base + lreg + off]` |
| `indPosIndexScaleOffsetNarrow` (5918) | `rRegI idx` | YES* | `[base + idx*scale + off]` |
| `indPosIndexOffsetNarrow` (5901) | `rRegI idx` | YES* | `[base + idx + off]` |

*Narrow variants have extra predicate: `CompressedOops::shift() == 0`

### The `Pos` Predicate

`indPosIndex*` operands have a predicate requiring the index to be non-negative:
```cpp
predicate(n->in(2)->in(3)->as_Type()->type()->is_long()->_lo >= 0);
```

### Compressed Oops and the `shift() == 0` Trap

The `Narrow` variants (`indPosIndexOffsetNarrow`, `indPosIndexScaleOffsetNarrow`)
have an ADDITIONAL predicate (x86.ad:5904, 5921):
```cpp
predicate(CompressedOops::shift() == 0 && ...)
```

With typical heaps (>4GB), `CompressedOops::shift()` is 3 (not 0). This means
the narrow variants **always fail** on typical configurations. If the address
tree goes through `DecodeN` (compressed oop decode), the DFA labels it with
narrow-specific labels, and the non-narrow variants can't match either.

Result: fallback to `indIndexOffset` (takes `rRegL`) → separate `convI2L_reg_reg`
machine node emitted → ConvI2L NOT folded into addressing.

### How ConvI2L Folding Affects Performance

When ConvI2L is folded (e.g., `indPosIndexScaleOffset`):
- Load takes int Phi directly as index
- Load has `ready_cnt=0` in scheduling (no block-local dependency)
- All loads ready immediately → parallel computation

When ConvI2L is NOT folded (e.g., `indIndexOffset`):
- Separate `convI2L_reg_reg` machine node in the block
- Load has `ready_cnt=1` (depends on ConvI2L)
- Loads gated → sequential processing

## Adding Matcher Logging

In `matcher.cpp` `ReduceInst()`, after `MachNode *mach = s->MachNodeGenerator(rule)`:

```cpp
if (PrintIdeal && leaf->is_Load()) {
    int ideal_op = leaf->Opcode();
    if (ideal_op == Op_LoadS || ideal_op == Op_LoadB) {
        tty->print("[matcher] %s(%d) → instr rule: %s, addr=AddP(%d)\n",
                   NodeClassNames[ideal_op], leaf->_idx,
                   _ruleName[rule],
                   leaf->in(MemNode::Address)->_idx);
    }
}
```

In `ReduceInst_Interior()`, after `mach->_opnds[num_opnds++] = ...`:

```cpp
if (PrintIdeal && s->_leaf->is_Load()) {
    int ideal_op = s->_leaf->Opcode();
    if (ideal_op == Op_LoadS || ideal_op == Op_LoadB) {
        tty->print("[matcher]   operand[%d]: class=%s instance=%s\n",
                   num_opnds-1, _ruleName[op], _ruleName[opnd_class_instance]);
    }
}
```

## Adding DFA Predicate Logging

Modify the predicate in `x86.ad` using a GNU statement expression:

```cpp
// x86.ad:5904 — indPosIndexOffsetNarrow predicate
predicate(({
    bool _r = CompressedOops::shift() == 0 &&
              n->in(2)->in(3)->as_Type()->type()->is_long()->_lo >= 0;
    if (PrintIdeal) {
        tty->print("[ad-predicate] indPosIndexOffsetNarrow AddP(%d): shift=%d _lo=%ld → %s\n",
                   n->_idx, CompressedOops::shift(),
                   (long)n->in(2)->in(3)->as_Type()->type()->is_long()->_lo,
                   _r ? "PASS" : "FAIL");
    }
    _r;
}));
```

Alternatively, add logging directly in the generated `dfa_x86.cpp` (fragile,
regenerated on full rebuild, but doesn't require ADLC to support statement
expressions).

## Key Source Files

| File | What it does |
|------|-------------|
| `x86.ad` | Architecture description — instruction and operand matching rules |
| `matcher.cpp` | `match_tree()`, `ReduceInst()`, `Label_Root()` — the BURS matcher |
| `dfa_x86.cpp` | ADLC-generated DFA — evaluates predicates and structural matches |
| `ad_x86.cpp` | ADLC-generated — machine node constructors |

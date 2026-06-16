# Investigation: shortXorBig Performance Regression

## The Problem

After reassociating reduction chains (XorI, AddI, etc.), `shortXorBig` regresses by ~43%
while `byteXorBig` is unaffected:

```
Benchmark                                 (SIZE)   Base    Patch   Diff
VectorReduction2.NoSuperword.byteXorBig     2048  344.809  344.230    0%
VectorReduction2.NoSuperword.shortXorBig    2048  304.868  174.448  -43%
```

Both benchmarks are structurally identical — only the element type differs (byte vs short).

---

## Reproducer

Save as `XorRepro.java` alongside the JDK source tree:

```java
import java.util.Random;

public class XorRepro {

    static final int SIZE = 2048;

    byte[] in1B = new byte[SIZE], in2B = new byte[SIZE], in3B = new byte[SIZE];
    short[] in1S = new short[SIZE], in2S = new short[SIZE], in3S = new short[SIZE];

    public static void main(String[] args) {
        XorRepro repro = new XorRepro();
        Random r = new Random(0);
        for (int i = 0; i < SIZE; i++) {
            repro.in1B[i] = (byte) r.nextInt();
            repro.in2B[i] = (byte) r.nextInt();
            repro.in3B[i] = (byte) r.nextInt();
            repro.in1S[i] = (short) r.nextInt();
            repro.in2S[i] = (short) r.nextInt();
            repro.in3S[i] = (short) r.nextInt();
        }

        for (int i = 0; i < 20_000; i++) {
            repro.byteXorBig();
            repro.shortXorBig();
        }

        int iters = 500_000;
        long t0, t1;

        t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) repro.byteXorBig();
        t1 = System.nanoTime();
        System.out.println("byteXorBig:  " + (t1 - t0) / 1_000_000 + " ms for " + iters + " iters");

        t0 = System.nanoTime();
        for (int i = 0; i < iters; i++) repro.shortXorBig();
        t1 = System.nanoTime();
        System.out.println("shortXorBig: " + (t1 - t0) / 1_000_000 + " ms for " + iters + " iters");
    }

    byte byteXorBig() {
        byte acc = 0;
        for (int i = 0; i < SIZE; i++) {
            byte val = (byte)((in1B[i] * in2B[i]) + (in1B[i] * in3B[i]) + (in2B[i] * in3B[i]));
            acc ^= val;
        }
        return acc;
    }

    short shortXorBig() {
        short acc = 0;
        for (int i = 0; i < SIZE; i++) {
            short val = (short)((in1S[i] * in2S[i]) + (in1S[i] * in3S[i]) + (in2S[i] * in3S[i]));
            acc ^= val;
        }
        return acc;
    }
}
```

---

## Build Setup

Two JDK builds are needed: a **baseline** (origin/master, no reassociation patch)
and a **patch** (current branch). Both as release for benchmarking, plus a fastdebug
for diagnostic flags.

```bash
# 1. Build the PATCH JDK (current branch with reassociation)
bash configure --with-debug-level=fastdebug --disable-warnings-as-errors
make images JOBS=$(nproc)
PATCH_DBG=$(pwd)/build/linux-x86_64-server-fastdebug/jdk/bin/java

bash configure --with-debug-level=release --disable-warnings-as-errors \
    --with-conf-name=linux-x86_64-server-release
make CONF=linux-x86_64-server-release images JOBS=$(nproc)
PATCH_REL=$(pwd)/build/linux-x86_64-server-release/jdk/bin/java

# 2. Build the BASELINE JDK (origin/master, no reassociation)
git worktree add ../jdk-baseline origin/master
cd ../jdk-baseline
bash configure --with-debug-level=fastdebug --disable-warnings-as-errors
make images JOBS=$(nproc)
BASE_DBG=$(pwd)/build/linux-x86_64-server-fastdebug/jdk/bin/java

bash configure --with-debug-level=release --disable-warnings-as-errors \
    --with-conf-name=linux-x86_64-server-release
make CONF=linux-x86_64-server-release images JOBS=$(nproc)
BASE_REL=$(pwd)/build/linux-x86_64-server-release/jdk/bin/java
cd -

# 3. Compile the reproducer (use either JDK)
$PATCH_DBG/../javac XorRepro.java
```

---

## Step 1: Confirm the Regression (release builds)

**Question:** Does the patch cause a regression?

```bash
echo "=== BASELINE ===" && $BASE_REL -XX:-UseSuperWord -cp . XorRepro
echo "=== PATCH ==="    && $PATCH_REL -XX:-UseSuperWord -cp . XorRepro
```

**Expected:** Both methods get slower with the patch, but short regresses more than byte.
Run multiple times to confirm stability.

---

## Step 2: Confirm Reassociation Fires Identically for Both

**Question:** Does the reassociation treat byte and short differently?

To see the reassociation fire, uncomment the `tty->print` lines in
`src/hotspot/share/opto/loopnode.cpp` inside `try_reassociate_chain()`,
rebuild hotspot (`make CONF=linux-x86_64-server-fastdebug hotspot JOBS=$(nproc)`),
then run:

```bash
$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::shortXorBig \
    -cp . XorRepro 2>&1 | grep "\[reassoc\]"

$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::byteXorBig \
    -cp . XorRepro 2>&1 | grep "\[reassoc\]"
```

**Expected:** Both show exactly 2 chains of XorI, each chain length 16.
The reassociation is identical for both types.

---

## Step 3: Confirm the IR (Ideal Graph) Is Structurally Identical

**Question:** Do byte and short produce different C2 IR?

```bash
$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::shortXorBig \
    -XX:+PrintIdeal -cp . XorRepro 2>&1 | \
    grep -c "LShiftI\|RShiftI\|XorI\|MulI\|AddI"

$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::byteXorBig \
    -XX:+PrintIdeal -cp . XorRepro 2>&1 | \
    grep -c "LShiftI\|RShiftI\|XorI\|MulI\|AddI"
```

**Expected:** Same counts for both. The ideal graph structure is identical.
The types differ (byte vs short ranges) but the graph topology is the same.

---

## Step 4: Confirm Unrolling Is the Same

**Question:** Do both methods unroll the same way?

```bash
$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::shortXorBig \
    -XX:+TraceLoopOpts -cp . XorRepro 2>&1 | grep "Unroll"

$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::byteXorBig \
    -XX:+TraceLoopOpts -cp . XorRepro 2>&1 | grep "Unroll"
```

**Expected:** Both show `Unroll 2`, `Unroll 4`, `Unroll 8`, `Unroll 16`.
Same unrolling trajectory.

---

## Step 5: Isolate Unrolling as the Amplifier

**Question:** Does the regression disappear without unrolling?

```bash
echo "=== BASELINE no-unroll ===" && \
    $BASE_REL -XX:-UseSuperWord -XX:LoopUnrollLimit=1 -cp . XorRepro
echo "=== PATCH no-unroll ===" && \
    $PATCH_REL -XX:-UseSuperWord -XX:LoopUnrollLimit=1 -cp . XorRepro
```

**Expected:** Zero regression for both methods. Without unrolling, the XOR chain
is only 1 element long — too short for reassociation to fire (minimum chain length is 2).
This confirms: unrolling creates the 16-element chain that reassociation then transforms.

---

## Step 6: Compare Generated Assembly — The Smoking Gun

**Question:** What does the generated machine code look like?

```bash
# Dump assembly for both methods, patch JDK, each compiled alone
$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::shortXorBig \
    -XX:CompileCommand=PrintAssembly,XorRepro::shortXorBig \
    -cp . XorRepro 2>&1 > asm_patch_short.txt

$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::byteXorBig \
    -XX:CompileCommand=PrintAssembly,XorRepro::byteXorBig \
    -cp . XorRepro 2>&1 > asm_patch_byte.txt
```

Then count the key instructions in the C2 OSR compilation
(the one with `Compiled method (c2) ... %`):

```bash
# For each file, find the C2 OSR section and count instructions.
# Look for lines between "Compiled method (c2) ... %" and the next "Compiled method".
# Count: xorl, imull, addl, leal, movswl/movsbl, movdl, movdq

echo "=== PATCH SHORT ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_patch_short.txt | \
    grep -c 'movd[lq]'

echo "=== PATCH BYTE ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_patch_byte.txt | \
    grep -c 'movd[lq]'
```

**Expected:** Short has ~69 XMM register moves (`movdl`/`movdq`), byte has ~6.
The XMM moves are the register allocator spilling GP values to XMM registers
because it ran out of general-purpose registers.

Do the same for baseline to see the pre-existing difference:

```bash
$BASE_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::shortXorBig \
    -XX:CompileCommand=PrintAssembly,XorRepro::shortXorBig \
    -cp . XorRepro 2>&1 > asm_base_short.txt

$BASE_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::byteXorBig \
    -XX:CompileCommand=PrintAssembly,XorRepro::byteXorBig \
    -cp . XorRepro 2>&1 > asm_base_byte.txt

echo "=== BASELINE SHORT ===" && \
    sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_base_short.txt | \
    grep -c 'movd[lq]'
echo "=== BASELINE BYTE ===" && \
    sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_base_byte.txt | \
    grep -c 'movd[lq]'
```

**Expected results (XMM moves as proxy for register spill pressure):**

| Config   | byte | short |
|----------|------|-------|
| Baseline |   ~8 |   ~25 |
| Patch    |   ~6 |  ~69  |

Key observations:
1. **Even without the patch**, short already has ~3x more XMM spills than byte (25 vs 8).
2. The patch **improves** byte (8 → 6) but **catastrophically worsens** short (25 → 69).
3. The reassociation amplifies a pre-existing register pressure imbalance.

---

## Step 7: The Root Cause — Scaled Addressing Modes

**Question:** Why does short have more register pressure than byte in the first place?

```bash
# Count scaled vs unscaled array loads in the C2 OSR compilation
echo "=== BYTE: scaled loads ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_base_byte.txt | \
    grep 'movsbl' | grep -c '<< #'

echo "=== BYTE: unscaled loads ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_base_byte.txt | \
    grep 'movsbl' | grep -vc '<< #'

echo "=== SHORT: scaled loads ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_base_short.txt | \
    grep 'movswl' | grep -c '<< #'

echo "=== SHORT: unscaled loads ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_base_short.txt | \
    grep 'movswl' | grep -vc '<< #'
```

**Expected:**
- Byte: ~0 scaled, ~54 unscaled
- Short: ~51 scaled, ~3 unscaled

**This is the fundamental difference.**

### What is SIB encoding?

SIB stands for **Scale-Index-Base** — a byte in x86 machine code that encodes
a memory address as:

```
effective_address = base_register + (index_register * scale) + displacement
```

The SIB byte has three fields:
- **Scale**: 1, 2, 4, or 8 (2 bits: 00=1, 01=2, 10=4, 11=8)
- **Index**: which register is multiplied by the scale (3 bits)
- **Base**: which register is added directly (3 bits)

For example, `movswl R8, [R9 + R10*2 + 42]` encodes:
- Base = R9 (array base pointer)
- Index = R10 (loop counter)
- Scale = 2 (short = 2 bytes per element)
- Displacement = 42 (constant offset for a specific unrolled iteration)

The scale values 1/2/4/8 directly correspond to Java element sizes:
`byte[]=1`, `short[]/char[]=2`, `int[]/float[]=4`, `long[]/double[]=8`.

The critical constraint: when scale > 1, the index register **cannot be folded
into the base**. The CPU must receive them separately because it multiplies the
index by the scale factor at execution time.

### How this applies to our benchmark

On x86-64, array loads use SIB addressing:
`[base + index * scale + displacement]`

- **Byte arrays** (element size = 1): scale factor = 1 (no scaling needed).
  The compiler can pre-compute `base + i` into a single register, then use
  constant displacements for each unrolled iteration: `[base_plus_i + #0]`,
  `[base_plus_i + #1]`, etc. The loop counter `i` is **folded** into the base.

- **Short arrays** (element size = 2): scale factor = 2.
  The SIB encoding requires the index register to be **separate** from the base,
  because the CPU multiplies the index by 2 at execution time.
  The loop counter `i` **must remain in its own register** throughout the entire
  loop body. This is one fewer GP register available for computation.

On x86-64 with ~12 usable GP registers, losing 1 register is significant.
This cascading effect means:
1. Fewer registers → more coalescing failures in Chaitin-Briggs graph coloring
2. More coalescing failures → more spill copies (`movl`)
3. The peephole optimizer converts `movl + addl` → `leal` (see below)
4. More `leal` = more live values at once (leal preserves both inputs)

---

## Step 8: Observe the Peephole lea Conversions

**Question:** How many `mov + add` → `lea` peephole conversions happen?

```bash
$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::shortXorBig \
    -XX:+PrintOptoPeephole -cp . XorRepro 2>&1 | \
    grep "peephole" | sort | uniq -c | sort -rn

$PATCH_DBG -XX:-UseSuperWord \
    -XX:CompileCommand=compileonly,XorRepro::byteXorBig \
    -XX:+PrintOptoPeephole -cp . XorRepro 2>&1 | \
    grep "peephole" | sort | uniq -c | sort -rn
```

**Expected:**
- Short: ~16 `peephole number: 0` (the `addI_rReg` → `leaI_rReg_rReg_peep` rule)
- Byte:  ~6 `peephole number: 0`

Peephole rule #0 is defined in `src/hotspot/cpu/x86/x86.ad` (line ~25546):
```
peephole %{
  peeppredicate(VM_Version::supports_fast_2op_lea());
  peepmatch (addI_rReg);
  peepprocedure (lea_coalesce_reg);
  peepreplace (leaI_rReg_rReg_peep());
%}
```

It fires when the register allocator couldn't coalesce a copy with an `addl`
destination (because the input value is still live). The peephole replaces the
`movl copy, src; addl copy, other` pair with `leal copy, [src + other]`.

More conversions for short confirms more coalescing failures, which traces back
to the register pressure from scaled addressing.

---

## Step 9: Observe the XOR Chain Structure in Assembly

**Question:** How are the XOR operations scheduled in the loop body?

Look at the XOR instructions in the C2 OSR assembly:

```bash
echo "=== PATCH SHORT: XOR placement ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_patch_short.txt | \
    grep -n 'xorl.*# int'

echo "=== PATCH BYTE: XOR placement ==="
sed -n '/Compiled method (c2).*%/,/Compiled method/p' asm_patch_byte.txt | \
    grep -n 'xorl.*# int'
```

**Expected with the patch:**
- **Short**: ~16 consecutive XOR instructions clustered in a dense block
  inside the loop body. All iteration values are computed first, spilled to
  XMM, then XOR'd together in one burst.

- **Byte**: XOR instructions are spread out across the loop body — one XOR
  after each iteration's computation. Each value is consumed immediately.

You can see this visually by mapping instructions to single characters
(X=XOR, M=MUL, A=ADD, n=narrowing, .=XMM move):

```
PATCH short: ...nMAMAn.n.n.nMAnMAMAnXXXXXXXX.X.X.X.X.X.X.X.X.A.n...
                                     ^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
                                     16 XORs clustered with XMM fills

PATCH byte:  ...MAnXnnnMAMAnXnnnMAMAnXnnnMAMAnXnnnMAMAnXnnn...
                  ^          ^          ^          ^
                  XOR after each iteration = interleaved
```

**The clustering does NOT appear in the baseline.** Without reassociation,
both methods have XOR runs of at most 3 (sequential `acc ^= val` pattern).

### Why clustering happens for short but not byte

After reassociation creates the inner XOR chain, IGVN (Iterative Global Value
Numbering) runs identity transformations that produce different IR structures:

**Short's inner chain** (verified via `-XX:+PrintIdeal`):
```
XorI(RShiftI, XorI(LoadS, XorI(LoadS, XorI(LoadS, XorI(MulI, XorI(RShiftI, ...))))))
          ↑              ↑         ↑         ↑         ↑
        late           EARLY     EARLY     EARLY     EARLY    ← mix of early and late
```

**Byte's inner chain**:
```
XorI(RShiftI, XorI(RShiftI, XorI(RShiftI, XorI(RShiftI, XorI(RShiftI, ...)))))
          ↑            ↑            ↑            ↑            ↑
        late          late         late         late         late    ← uniformly late
```

**The IR is identical for both methods.** Diagnostic prints inside
`reassociate_chain()` confirm ALL extracted chain values are `RShiftI` at
extraction time — identical for both byte and short. Furthermore, logging
inside `RShiftNode::IdentityIL()` (mulnode.cpp:1240) and `RShiftINode::Ideal()`
(mulnode.cpp:1322) shows these rules fire the SAME number of times for
both methods.

**IMPORTANT: `PrintIdeal` outputs TWO compilations** (OSR + standard) with
overlapping node IDs in the same stream. If you grep for XorI inputs without
separating compilations, you may see `LoadS`/`MulI` from the standard
compilation mixed with `RShiftI` from the OSR compilation, creating the
illusion of structural differences. Always check for node ID conflicts:
```python
# Detect overlapping compilations in PrintIdeal output
import re
node_types = {}
for line in open('ideal_output.txt'):
    m = re.match(r'\s*(\d+)\s+(\w+)\s+===', line)
    if m and m.group(1) in node_types and node_types[m.group(1)] != m.group(2):
        print(f"CONFLICT: node {m.group(1)} is {node_types[m.group(1)]} AND {m.group(2)}")
    if m:
        node_types[m.group(1)] = m.group(2)
```

You can verify the chain inputs with a diagnostic print inside
`reassociate_chain()` (before `build_min_max`):
```cpp
tty->print("[reassoc-extract] left: %s(%d)  right: %s(%d)\n",
           NodeClassNames[left->Opcode()], left->_idx,
           NodeClassNames[right->Opcode()], right->_idx);
```

Since the IR is identical, the clustering difference must emerge during
instruction selection or code scheduling (after ideal graph → machine graph).

The scheduling consequence:
- `LoadS` (array load) is available **early** — as soon as memory is accessed.
  It must stay alive in a register until the XOR chain reaches its position.
- `RShiftI` (narrowing) is available **late** — computed after the full
  expression. The scheduler places XorI right after RShiftI, naturally
  interleaving.

### The scheduler's decision mechanism

C2's local code scheduler (`lcm.cpp`, `schedule_local()`) has a register-pressure-
aware heuristic. When register pressure exceeds a threshold, it scores ready
nodes based on their pressure impact:

- Scheduling a node that **reduces** pressure → score boosted → **picked eagerly**
- Scheduling a node that **increases** pressure → score = 1 (minimum) → **deferred**

(See `lcm.cpp` lines ~669-689, the `_scheduling_for_pressure` block.)

**For byte's XorI(prev, RShiftI):** RShiftI is **single-use** (only consumed by
this XorI). Scheduling XorI frees the RShiftI register. Net effect: pressure
stays same or drops → score boosted → scheduled eagerly → interleaving.

**For short's XorI(prev, LoadS):** LoadS is **multi-use** (consumed by BOTH MulI
in the expression AND by this XorI). Scheduling XorI does NOT free LoadS's
register (MulI still needs it). Net effect: pressure increases (new XorI result
lives, LoadS still lives) → score = 1 → **deferred**.

And since the inner chain is sequential (r3 depends on r2, r4 on r3, etc.),
deferring the first LoadS-consuming XorI **blocks the entire rest of the chain**.
The scheduler processes all expression computations first (which DO reduce
pressure), then runs the XOR chain in one burst at the end.

To verify: check `PrintIdeal` output for the XorI chain value inputs. Multi-use
inputs (LoadS with output count > 1) cause deferral. Single-use inputs
(RShiftI with output count = 1) allow interleaving.

The interleaved pattern (byte) requires only ~2 GP registers for the XOR chain.
The deferred pattern (short) requires all 16 values to be alive simultaneously,
causing the XMM spill cascade.

---

## How Reassociation Amplifies the Problem

### Before reassociation (baseline):

The XOR chain after 16x unrolling is sequential:
```
acc = ((((acc ^ v0) ^ v1) ^ v2) ^ ... ^ v15)
```

Each `vi` is computed and XOR'd with `acc` immediately. Only 1 extra value
is alive at a time (the current `vi`). Register pressure is modest.
Even so, short already has more XMM moves than byte due to the addressing
mode constraint (verified from JMH assembly: short=11, byte=3).

### After reassociation (patch):

The chain is restructured to:
```
acc = acc ^ (((v0 ^ v1) ^ v2) ^ ... ^ v15)
```

The inner chain `(v0 ^ v1 ^ ... ^ v15)` is independent of `acc` (the phi
from the previous iteration), which is the intended ILP benefit. But ALL 16
`vi` values must be computed before the inner chain can start — they all need
to be alive simultaneously.

For byte (already low pressure): the register allocator interleaves the XOR
chain with computation, so values are consumed immediately → 3 XMM spills.

For short (already high pressure due to scaled addressing): the register
allocator defers all 16 XORs to the end, so all values must be live
simultaneously → 76 XMM spills.

**Note on the standalone reproducer:** A standalone `XorRepro.java` reproducer
was initially used but it does NOT replicate the JMH behavior. The standalone
reproducer showed a regression for byte (not short), which is the opposite.
This is because JMH controls the compilation context (separate fork, Blackhole,
warmup) which changes which C2 compilation tier and mode (OSR vs standard)
runs during measurement. Always verify findings against the actual JMH
benchmark.

---

## Summary of Root Cause Chain

```
short arrays: element size = 2 bytes
    ↓
x86 SIB addressing: [base + index * 2 + disp]
    ↓
Loop counter must stay in a SEPARATE register (for the *2 scaling)
    ↓
~12 usable GP registers instead of ~13
    ↓
More coalescing failures in Chaitin-Briggs register allocator
    ↓
Baseline: 11 XMM moves (short) vs 3 (byte) — already worse
    ↓
Reassociation forces 16 values live simultaneously
(IR is IDENTICAL for byte and short after reassociation + IGVN)
    ↓
Code scheduling (LCM) defers XOR chain under high register pressure
    ↓
Patch: 76 XMM moves (short) vs 3 (byte) — catastrophic amplification
```

(Numbers verified from actual JMH assembly dumps, not standalone reproducer.)

**Open question:** the exact mechanism in the LCM scheduler that causes
clustering for short but interleaving for byte remains to be fully traced.
The IR graphs are identical, so the difference must emerge during instruction
selection (byte arrays use unscaled addressing, short arrays use scaled `<< #1`),
which produces different machine node structures that the LCM scheduler handles
differently. See `schedule_local()` in `lcm.cpp` and the register pressure
heuristic at lines ~669-689.

---

## Key C2 Diagnostic Flags Used

| Flag | Build | Purpose |
|------|-------|---------|
| `-XX:+TraceLoopOpts` | fastdebug | Shows loop optimization decisions (unrolling, peeling, etc.) |
| `-XX:+PrintIdeal` | fastdebug | Dumps the C2 Ideal IR graph (platform-independent) |
| `-XX:CompileCommand=PrintAssembly,Class::method` | fastdebug | Dumps final machine code for a method |
| `-XX:+PrintOptoAssembly` | fastdebug | Dumps machine code after register allocation, before peephole |
| `-XX:+PrintOptoPeephole` | fastdebug | Shows which peephole rules fire and how many times |
| `-XX:+TraceSpilling` | fastdebug | Verbose register allocator spill decisions |
| `-XX:CompileCommand=compileonly,Class::method` | any | Restricts C2 compilation to one method (cleaner output) |
| `-XX:LoopUnrollLimit=1` | any | Disables loop unrolling (useful for isolating effects) |
| `-XX:-UseSuperWord` | any | Disables auto-vectorization (matches the JMH `NoSuperword` config) |

---

## Key Source Files

| File | Role |
|------|------|
| `src/hotspot/share/opto/loopnode.cpp` | The reassociation patch (`try_reassociate_chain`, `reassociate_chain`) |
| `src/hotspot/share/opto/chaitin.cpp` | Chaitin-Briggs register allocator (graph coloring, spill decisions) |
| `src/hotspot/share/opto/gcm.cpp` | Global Code Motion — schedules nodes into basic blocks |
| `src/hotspot/share/opto/lcm.cpp` | Local Code Motion — orders instructions within a block |
| `src/hotspot/cpu/x86/x86.ad` | x86 instruction selection rules (addI_rReg, leaI patterns, peephole rules) |
| `src/hotspot/cpu/x86/peephole_x86_64.cpp` | Peephole optimization (lea_coalesce_helper) |
| `src/hotspot/share/opto/addnode.cpp` | XorINode::Value(), type inference for XOR |

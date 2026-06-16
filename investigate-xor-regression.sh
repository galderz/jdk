#!/bin/bash
#
# Diagnostic script for the shortXorBig vs byteXorBig regression.
#
# Usage:
#   ./investigate-xor-regression.sh <patch-jdk-fastdebug> <baseline-jdk-fastdebug> \
#                                   <patch-benchmarks-jar> <baseline-benchmarks-jar> \
#                                   [<patch-jdk-release> <baseline-jdk-release>]
#
# Example:
#   ./investigate-xor-regression.sh \
#       build/linux-x86_64-server-fastdebug/jdk/bin/java \
#       ../jdk-baseline/build/linux-x86_64-server-fastdebug/jdk/bin/java \
#       build/linux-x86_64-server-fastdebug/images/test/micro/benchmarks.jar \
#       ../jdk-baseline/build/linux-x86_64-server-fastdebug/images/test/micro/benchmarks.jar \
#       build/linux-x86_64-server-release/jdk/bin/java \
#       ../jdk-baseline/build/linux-x86_64-server-release/jdk/bin/java
#
# Requirements:
#   - fastdebug JDK builds (for PrintAssembly, TraceLoopOpts)
#   - microbenchmark jars built with --with-jmh
#   - release JDK builds (optional, for accurate JMH timing)

set -euo pipefail

PATCH_DBG="${1:?Usage: $0 <patch-fastdebug-java> <baseline-fastdebug-java> <patch-jar> <baseline-jar> [patch-release-java] [baseline-release-java]}"
BASE_DBG="${2:?}"
PATCH_JAR="${3:?}"
BASE_JAR="${4:?}"
PATCH_REL="${5:-$PATCH_DBG}"
BASE_REL="${6:-$BASE_DBG}"

OUTDIR=$(mktemp -d /tmp/xor-regression-XXXXXX)
echo "Output directory: $OUTDIR"
echo

JMH_OPTS="-p SIZE=2048 -p seed=0 -f 1 -wi 5 -i 3 -w 2s -r 2s"
JMH_FAST_OPTS="-p SIZE=2048 -p seed=0 -f 1 -wi 3 -i 1 -w 1s -r 1s"
BENCH_FILTER="VectorReduction2.NoSuperword.(shortXorBig|byteXorBig)"

# ─────────────────────────────────────────────────────────────────────
# Helper: extract instruction counts from C2 compilation with most imul
# ─────────────────────────────────────────────────────────────────────
count_instrs() {
    local file="$1"
    python3 - "$file" << 'PYEOF'
import re, sys
filename = sys.argv[1]
with open(filename) as f:
    lines = f.readlines()

c2_comps = []
for i, line in enumerate(lines):
    if 'Compiled method (c2)' in line:
        c2_comps.append(i)

best, best_imul = None, 0
for idx, start in enumerate(c2_comps):
    end = c2_comps[idx+1] if idx+1 < len(c2_comps) else len(lines)
    section = lines[start:end]
    ic = sum(1 for l in section if 'imul' in l)
    if ic > best_imul:
        best_imul = ic
        best = section

if best is None or best_imul == 0:
    print("NO_HOT_COMPILATION")
    sys.exit(0)

xor = sum(1 for l in best if re.search(r'\bxorl\b', l) and 'xorps' not in l)
imul = sum(1 for l in best if 'imul' in l)
addl = sum(1 for l in best if re.search(r'\baddl\b', l))
leal = sum(1 for l in best if re.search(r'\bleal\b', l))
xmm = sum(1 for l in best if re.search(r'movd[lq]', l))
scaled = sum(1 for l in best if re.search(r'movs[bw]l.*<< #', l))
unscaled = sum(1 for l in best if re.search(r'movs[bw]l.*\[', l) and '<< #' not in l)

# XOR clustering: longest consecutive XOR run
tags = []
for line in best:
    if re.search(r'\bxorl\b', line) and 'xorps' not in line:
        tags.append('X')
    elif 'imul' in line:
        tags.append('M')
    elif re.search(r'\baddl\b', line):
        tags.append('A')
    elif 'movswl' in line or 'movsbl' in line:
        tags.append('n')
    elif re.search(r'movd[lq]', line):
        tags.append('.')

max_xor_run = 0
run = 0
for t in tags:
    if t == 'X':
        run += 1
        max_xor_run = max(max_xor_run, run)
    else:
        run = 0

seq = ''.join(tags)

print(f"xorl={xor} imul={imul} addl={addl} leal={leal} XMM={xmm} scaled={scaled} unscaled={unscaled} max_xor_run={max_xor_run}")
print(f"pattern={seq}")
PYEOF
}

# ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  STEP 1: JMH Benchmark Performance (release builds)        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo

echo "--- BASELINE ---"
$BASE_REL -jar "$BASE_JAR" "$BENCH_FILTER" $JMH_OPTS 2>&1 | \
    grep -E "^Benchmark|^VectorReduction2" | tee "$OUTDIR/jmh_baseline.txt"
echo

echo "--- PATCH ---"
$PATCH_REL -jar "$PATCH_JAR" "$BENCH_FILTER" $JMH_OPTS 2>&1 | \
    grep -E "^Benchmark|^VectorReduction2" | tee "$OUTDIR/jmh_patch.txt"
echo

# ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  STEP 2: Assembly Dumps (fastdebug builds via JMH)         ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo

for config_label in BASELINE PATCH; do
    if [ "$config_label" = "BASELINE" ]; then
        JAVA="$BASE_DBG"
        JAR="$BASE_JAR"
    else
        JAVA="$PATCH_DBG"
        JAR="$PATCH_JAR"
    fi

    for bench in shortXorBig byteXorBig; do
        outfile="$OUTDIR/asm_${config_label,,}_${bench}.txt"
        echo "  Dumping assembly: $config_label $bench ..."
        $JAVA -jar "$JAR" \
            "VectorReduction2.NoSuperword.$bench" \
            $JMH_FAST_OPTS \
            -jvmArgs "-XX:CompileCommand=PrintAssembly,*VectorReduction2::$bench" \
            2>&1 > "$outfile"
    done
done
echo

# ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  STEP 3: Assembly Analysis                                  ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo

printf "%-10s %-12s  %5s %5s %5s %5s %5s %7s %7s %12s\n" \
    "Config" "Method" "xorl" "imul" "addl" "leal" "XMM" "scaled" "unscl" "max_xor_run"
printf "%s\n" "$(printf '─%.0s' {1..95})"

for config_label in baseline patch; do
    for bench in shortXorBig byteXorBig; do
        file="$OUTDIR/asm_${config_label}_${bench}.txt"
        if [ ! -f "$file" ]; then
            printf "%-10s %-12s  %s\n" "$config_label" "$bench" "FILE NOT FOUND"
            continue
        fi

        # Run python once, cache both lines
        output=$(count_instrs "$file")
        result=$(echo "$output" | head -1)
        pattern_line=$(echo "$output" | tail -1)

        if [ "$result" = "NO_HOT_COMPILATION" ]; then
            printf "%-10s %-12s  %s\n" "$config_label" "$bench" "no hot C2 compilation found"
            continue
        fi

        # Parse the key=value output (use word boundaries to avoid scaled matching unscaled)
        xorl=$(echo "$result" | grep -oP 'xorl=\K[0-9]+')
        imul=$(echo "$result" | grep -oP 'imul=\K[0-9]+')
        addl=$(echo "$result" | grep -oP 'addl=\K[0-9]+')
        leal=$(echo "$result" | grep -oP 'leal=\K[0-9]+')
        xmm=$(echo "$result" | grep -oP 'XMM=\K[0-9]+')
        scaled=$(echo "$result" | grep -oP ' scaled=\K[0-9]+')
        unscaled=$(echo "$result" | grep -oP 'unscaled=\K[0-9]+')
        max_xor=$(echo "$result" | grep -oP 'max_xor_run=\K[0-9]+')

        printf "%-10s %-12s  %5s %5s %5s %5s %5s %7s %7s %12s\n" \
            "$config_label" "$bench" "$xorl" "$imul" "$addl" "$leal" "$xmm" "$scaled" "$unscaled" "$max_xor"

        # Save pattern for later visualization
        echo "$pattern_line" > "$OUTDIR/pattern_${config_label}_${bench}.txt"
    done
done
echo

# ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  STEP 4: Instruction Pattern Visualization                  ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo
echo "Legend: X=XOR  M=MUL  A=ADD  n=narrowing(movswl/movsbl)  .=XMM move"
echo

for config_label in baseline patch; do
    for bench in shortXorBig byteXorBig; do
        patfile="$OUTDIR/pattern_${config_label}_${bench}.txt"
        [ ! -f "$patfile" ] && continue
        pattern=$(sed 's/pattern=//' "$patfile")
        [ -z "$pattern" ] && continue
        echo "  $config_label $bench:"
        echo "  $pattern" | fold -w 78 | sed 's/^/    /'
        echo
    done
done

# ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  STEP 5: Ideal Graph — XOR Chain Input Analysis (patch)     ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo
echo "Dumping C2 Ideal graph for patch builds (explains WHY clustering occurs)..."
echo

for bench in shortXorBig byteXorBig; do
    outfile="$OUTDIR/ideal_patch_${bench}.txt"
    echo "  Dumping ideal: PATCH $bench ..."
    $PATCH_DBG -jar "$PATCH_JAR" \
        "VectorReduction2.NoSuperword.$bench" \
        $JMH_FAST_OPTS \
        -jvmArgs "-XX:+PrintIdeal -XX:CompileCommand=compileonly,*VectorReduction2::$bench" \
        2>&1 > "$outfile"
done
echo

# Analyze ideal graph — check for node ID conflicts (multiple compilations)
python3 - "$OUTDIR" << 'PYEOF'
import re, sys, os

outdir = sys.argv[1]

for bench in ['shortXorBig', 'byteXorBig']:
    filename = os.path.join(outdir, f'ideal_patch_{bench}.txt')
    if not os.path.exists(filename):
        print(f"  {bench}: ideal graph file not found")
        continue

    with open(filename) as f:
        lines = f.readlines()

    # Detect multiple compilations via node ID conflicts
    node_first = {}
    conflicts = 0
    for line in lines:
        m = re.match(r'\s*(\d+)\s+(\w+)\s+===', line)
        if m:
            nid, ntype = m.group(1), m.group(2)
            if nid in node_first and node_first[nid] != ntype:
                conflicts += 1
            else:
                node_first[nid] = ntype

    # Parse FIRST compilation only (stop at first conflict)
    nodes = {}
    seen_ids = set()
    for line in lines:
        m = re.match(r'\s*(\d+)\s+(\w+)\s+===\s+(.*?)\s*\[\[\s*(.*?)\s*\]\]', line)
        if m:
            nid = m.group(1)
            if nid in seen_ids:
                break  # second compilation starts here
            seen_ids.add(nid)
            nodes[nid] = {
                'type': m.group(2),
                'inputs': [x.strip() for x in m.group(3).split() if x.strip() != '_'],
                'outputs': [x.strip() for x in m.group(4).split() if x.strip()]
            }

    # Find XorI chain inputs (first compilation only)
    chain_inputs = []
    for nid, info in nodes.items():
        if info['type'] != 'XorI':
            continue
        has_xor_input = any(inp in nodes and nodes[inp]['type'] == 'XorI' for inp in info['inputs'])
        if not has_xor_input or len(info['outputs']) > 2:
            continue
        for inp in info['inputs']:
            if inp in nodes and nodes[inp]['type'] != 'XorI':
                chain_inputs.append((nid, inp, nodes[inp]['type']))

    rshift_count = sum(1 for _, _, t in chain_inputs if t == 'RShiftI')
    other_count = sum(1 for _, _, t in chain_inputs if t != 'RShiftI')

    print(f"  {bench} (first compilation, {len(nodes)} nodes, {conflicts} ID conflicts):")
    print(f"    XorI chain inputs: {rshift_count} RShiftI, {other_count} other")
    if other_count > 0:
        for _, vid, vtype in chain_inputs:
            if vtype != 'RShiftI':
                print(f"      non-RShiftI: {vtype}({vid})")
    print()

print("  NOTE: PrintIdeal outputs MULTIPLE compilations with overlapping node IDs.")
print("  This analysis uses only the FIRST compilation to avoid confusion.")
print("  If both methods show all-RShiftI chain inputs, the IR is identical —")
print("  the clustering difference emerges during instruction selection/scheduling,")
print("  driven by the scaled addressing mode difference (short uses << #1).")
PYEOF
echo

# ─────────────────────────────────────────────────────────────────────
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  STEP 6: Summary                                            ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo
echo "Key metrics to compare:"
echo "  XMM moves:     register spill pressure (short >> byte = problem)"
echo "  Scaled loads:  SIB [base + index*2] addressing (short only)"
echo "  max_xor_run:   XOR clustering (>8 = deferred chain, bad for short)"
echo
echo "If short has high XMM + high max_xor_run with the patch but not baseline,"
echo "the reassociation is deferring the XOR chain due to register pressure"
echo "from scaled addressing."
echo
echo "NOTE: JIT compilation is non-deterministic. Assembly counts may vary"
echo "between runs. The JMH performance numbers (Step 1) are reliable;"
echo "assembly analysis (Steps 3-4) shows the general pattern but exact"
echo "counts can differ. Run multiple times if in doubt."
echo
echo "Assembly dumps saved in: $OUTDIR/"
echo "Done."

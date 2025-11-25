/*
 * Copyright (c) 2025 IBM Corporation. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

/**
 * @test
 * @bug 8351409
 * @summary Test the IR effects of reduction reassociation
 * @library /test/lib /
 * @run driver compiler.loopopts.TestReductionReassociation
 */

package compiler.loopopts;

import compiler.lib.generators.Generator;
import compiler.lib.ir_framework.*;
import compiler.lib.verify.Verify;

import static compiler.lib.generators.Generators.G;

public class TestReductionReassociation {
    static final Generator<Long> GEN_L = G.longs();

    static final long[] aL = new long[10_000];

    final Object[] gold = test();

    static {
        G.fill(GEN_L, aL);
    }

    public static void main(String[] args) {
        TestFramework.runWithFlags("-XX:-UseSuperWord", "-XX:LoopMaxUnroll=0");
    }

    @Test
    @IR(counts = {IRNode.MAX_L, "= 5"}, phase = CompilePhase.AFTER_LOOP_OPTS)
    public Object[] test() {
        long resultUnroll = Integer.MIN_VALUE;
        long resultReassocTree = Integer.MIN_VALUE;
        for (int i = 0; i < aL.length; i += 4) {
            long v0 = aL[i + 0];
            long v1 = aL[i + 1];
            long v2 = aL[i + 2];
            long v3 = aL[i + 3];

            // max(v3, max(v2, max(v1, max(v0, result))))
            long u0 = Math.max(v0, resultUnroll);
            long u1 = Math.max(v1, u0);
            long u2 = Math.max(v2, u1);
            long u3 = Math.max(v3, u2);
            resultUnroll = u3;

            // max(result, max(max(v0, v1), max(v2, v3))
            long t0 = Math.max(v0, v1);
            long t1 = Math.max(v2, v3);
            long t2 = Math.max(t0, t1);
            long t3 = Math.max(resultReassocTree, t2);
            resultReassocTree = t3;
        }

        return new Object[]{resultUnroll, resultReassocTree};
    }

    // max(result, max(max(v3, v2), max(v1, v0))
    // long t0 = Math.max(v3, v2);
    // long t1 = Math.max(v1, v0);
    // long t2 = Math.max(t0, t1);
    // long t3 = Math.max(resultReassocTree, t2);
    // resultReassocTree = t3;

//    @Test
//    @IR(counts = {IRNode.MAX_L, "= 4"}, phase = CompilePhase.BEFORE_MACRO_EXPANSION)
//    public Object[] test() {
//        // result = max(v3, max(v2, max(v1, max(v0, result))))
//        long resultUnroll = Integer.MIN_VALUE;
//        for (int i = 0; i < aL.length; i += 4) {
//            var v0 = aL[i + 0];
//            var v1 = aL[i + 1];
//            var v2 = aL[i + 2];
//            var v3 = aL[i + 3];
//            var t0 = Math.max(v0, resultUnroll);
//            var t1 = Math.max(v1, t0);
//            var t2 = Math.max(v2, t1);
//            var t3 = Math.max(v3, t2);
//            resultUnroll = t3;
//        }
//
//        // result = max(result, max(max(v0, v1), max(v2, v3))
//        long resultReassocTree = Integer.MIN_VALUE;
//        for (int i = 0; i < aL.length; i += 4) {
//            var v0 = aL[i + 0];
//            var v1 = aL[i + 1];
//            var v2 = aL[i + 2];
//            var v3 = aL[i + 3];
//            var t0 = Math.max(v0, v1);
//            var t1 = Math.max(v2, v3);
//            var t2 = Math.max(t0, t1);
//            resultReassocTree = Math.max(resultReassocTree, t2);
//        }
//
//        return new Object[]{resultUnroll, resultReassocTree};
//        // i = max(a, max(b, max(c, max(d, i)))
//        // i' = max(i, max(max(a, b), max(c, d)))
//        // to
//        // i = max(i, max(max(a, b), max(c, d)))
//        // i' = max(i, max(max(a, b), max(c, d)))
//        // =
//        // i = max(i, max(max(a, b), max(c, d)))
//        // i' = i
//    }

    @Check(test = "test")
    public void check(Object[] vals) {
        Verify.checkEQ(gold[0], vals[0]);
        Verify.checkEQ(gold[1], vals[1]);
        Verify.checkEQ(vals[0], vals[1]);
    }
}

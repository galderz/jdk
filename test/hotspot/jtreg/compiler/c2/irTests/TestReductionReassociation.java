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
package compiler.c2.irTests;

import compiler.lib.generators.Generator;
import compiler.lib.generators.Generators;
import compiler.lib.ir_framework.*;

/*
 * @test
 * @bug 8351409
 * @summary Test that commutative add node reduction chains are reassociated correctly
 * @library /test/lib /
 * @run driver compiler.c2.irTests.TestReductionReassociation
 */
public class TestReductionReassociation {
    private static final Generator<Long> GEN_LONG = Generators.G.longs();

    private static final int SIZE = 1024;

    public static void main(String[] args) {
        TestFramework.run();
    }

    @Setup
    static Object[] setupLongArray() {
        long[] longs = new long[SIZE];
        Generators.G.fill(GEN_LONG, longs);
        return new Object[]{longs};
    }

    @Test
    @IR(counts = {IRNode.MAX_L, "> 0"}, phase = CompilePhase.PHASEIDEALLOOP_ITERATIONS)
    @Arguments(setup = "setupLongArray")
    public Object[] testMaxLongManualUnroll4(long[] a) {
        long r = 0;

        for (int i = 0; i < a.length; i += 4)
        {
            var v0 = a[i + 0];
            var v1 = a[i + 1];
            var v2 = a[i + 2];
            var v3 = a[i + 3];
            var t0 = Math.max(v1, v0);
            var t1 = Math.max(v2, t0);
            var t2 = Math.max(v3, t1);
            r = Math.max(r, t2);
        }

        return new Object[]{a, r};
    }

    @Check(test = "testMaxLongManualUnroll4")
    public void checkTestMaxLongManualUnroll4(Object[] vals) {
        checkReduction(vals);
    }

    private static void checkReduction(Object[] vals) {
        long[] a = (long[]) vals[0];
        long testRet = (long) vals[1];

        long r = 0;
        for (int i = 0; i < a.length; i++) {
            long aI = a[i];
            r = aI > r ? aI : r;
        }

        if (r != testRet) {
            throw new IllegalStateException("Long max reduction test failed: expected " + testRet + " but got " + r);
        }
    }
}

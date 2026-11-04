/*
 * Copyright (c) 2026, Oracle and/or its affiliates. All rights reserved.
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
 */

/*
 * @test
 * @bug 8375502
 * @key randomness
 * @summary Unsigned widening (LoadUB feeding int, Byte.toUnsignedInt shape)
 *          vectorizes via an inserted unsigned cast.
 * @library /test/lib /
 * @run driver compiler.loopopts.superword.TestUnsignedCast
 */

package compiler.loopopts.superword;

import compiler.lib.ir_framework.*;
import compiler.lib.verify.Verify;
import java.util.Map;
import java.util.HashMap;
import java.util.Random;
import jdk.test.lib.Utils;

public class TestUnsignedCast {
    static int RANGE = 1024;
    private static final Random RANDOM = Utils.getRandomInstance();

    byte[] aB;

    Map<String,TestFunction> tests = new HashMap<String,TestFunction>();

    Map<String,Object[]> golds = new HashMap<String,Object[]>();

    interface TestFunction {
        Object[] run();
    }

    public static void main(String[] args) {
        TestFramework.run();
    }

    public TestUnsignedCast() {
        aB = generateB();

        tests.put("unsignedWiden", () -> { return unsignedWiden(aB.clone(), new int[RANGE]); });
        tests.put("unsignedWidenToUnsignedInt", () -> { return unsignedWidenToUnsignedInt(aB.clone(), new int[RANGE]); });

        for (Map.Entry<String,TestFunction> entry : tests.entrySet()) {
            String name = entry.getKey();
            TestFunction test = entry.getValue();
            Object[] gold = test.run();
            golds.put(name, gold);
        }
    }

    @Warmup(100)
    @Run(test = {"unsignedWiden",
                 "unsignedWidenToUnsignedInt"})
    public void runTests() {
        for (Map.Entry<String,TestFunction> entry : tests.entrySet()) {
            String name = entry.getKey();
            TestFunction test = entry.getValue();
            // Recall gold value from before compilation
            Object[] gold = golds.get(name);
            // Compute new result
            Object[] result = test.run();
            // Compare gold and new result
            Verify.checkEQ(gold, result);
        }
    }

    static byte[] generateB() {
        byte[] a = new byte[RANGE];
        for (int i = 0; i < a.length; i++) {
            a[i] = (byte)RANDOM.nextInt();
        }
        return a;
    }

    @Test
    @IR(counts = {IRNode.VECTOR_UCAST_B2I, IRNode.VECTOR_SIZE + "min(max_byte, max_int)", ">0",
                  IRNode.STORE_VECTOR, "> 0",
                  IRNode.VECTOR_REINTERPRET_B, "= 0"},
        applyIfPlatform = {"64-bit", "true"},
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true", "rvv", "true"})
    static Object[] unsignedWiden(byte[] src, int[] dst) {
        for (int i = 0; i < src.length; i++) {
            dst[i] = src[i] & 0xFF;
        }
        return new Object[]{ src, dst };
    }

    @Test
    @IR(counts = {IRNode.VECTOR_UCAST_B2I, IRNode.VECTOR_SIZE + "min(max_byte, max_int)", ">0",
                  IRNode.STORE_VECTOR, "> 0",
                  IRNode.VECTOR_REINTERPRET_B, "= 0"},
        applyIfPlatform = {"64-bit", "true"},
        applyIfCPUFeatureOr = {"avx", "true", "asimd", "true", "rvv", "true"})
    static Object[] unsignedWidenToUnsignedInt(byte[] src, int[] dst) {
        for (int i = 0; i < src.length; i++) {
            dst[i] = Byte.toUnsignedInt(src[i]);
        }
        return new Object[]{ src, dst };
    }
}

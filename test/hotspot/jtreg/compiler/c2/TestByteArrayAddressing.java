/*
 * Copyright (c) 2026 IBM Corporation. All rights reserved.
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

/*
 * @test
 * @bug 8387146
 * @summary Verify that optimal byte array addressing is in use
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @run driver ${test.main.class}
 */

package compiler.c2;

import compiler.lib.generators.Generator;
import compiler.lib.ir_framework.Arguments;
import compiler.lib.ir_framework.CompilePhase;
import compiler.lib.ir_framework.IR;
import compiler.lib.ir_framework.IRNode;
import compiler.lib.ir_framework.Setup;
import compiler.lib.ir_framework.Test;
import compiler.lib.ir_framework.TestFramework;

import static compiler.lib.generators.Generators.G;

public class TestByteArrayAddressing {
    private static final Generator<Integer> GEN_I = G.ints();

    public static void main(String[] args) {
        TestFramework.run();
    }

//    @Setup
//    public static Object[] setupMultiArrays() {
//        final byte[] b1 = new byte[100];
//        final byte[] b2 = new byte[100];
//        final byte[] b3 = new byte[100];
//        for (int i = 0; i < b1.length; i++) {
//            b1[i] = GEN_I.next().byteValue();
//            b2[i] = GEN_I.next().byteValue();
//            b3[i] = GEN_I.next().byteValue();
//        }
//        return new Object[] {b1, b2, b3, 42};
//    }
//
//    @Test
//    @Arguments(setup = "setupMultiArrays")
//    @IR(counts = {IRNode.X86_SCONV_I2L, "= 20"},
//        applyIfPlatform = {"x64", "true"},
//        phase = CompilePhase.MATCHING)
//    private static int testSingleOffset(byte[] b1, byte[] b2, byte[] b3, int i) {
//        return b1[i] + b2[i] + b3[i];
//    }

    @Setup
    public static Object[] setup() {
        final byte[] bytes = new byte[100];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = GEN_I.next().byteValue();
        }
        return new Object[] {bytes, 42, 82};
    }

    @Test
    @Arguments(setup = "setup")
    @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
        applyIfPlatform = {"x64", "true"},
        phase = CompilePhase.MATCHING)
    private static int test(byte[] b, int i) {
        return b[i];
    }

    @Test
    @Arguments(setup = "setup")
    @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
        applyIfPlatform = {"x64", "true"},
        phase = CompilePhase.MATCHING)
    private static int testSameOffset(byte[] b, int i, int j) {
        i = Integer.min(Integer.max(i, 0), 1000);
        int v = b[i];
        b[j] = 42;
        return v + b[i];
    }

    @Test
    @Arguments(setup = "setup")
    @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
        applyIfPlatform = {"x64", "true"},
        phase = CompilePhase.MATCHING)
    private static int testDifferentOffset(byte[] b, int i) {
        i = Integer.min(Integer.max(i, 0), 1000);
        return b[i] + b[i + 1];
    }

//    @Test
//    @Arguments(setup = "setupMultiArrays")
//    @IR(counts = {IRNode.X86_SCONV_I2L, "= 20"},
//        applyIfPlatform = {"x64", "true"},
//        phase = CompilePhase.MATCHING)
//    private static int testMultipleArraysMultiOffset(byte[] b1, byte[] b2, byte[] b3, int i) {
//        return b1[i] + b2[i + 1] + b3[i + 2];
//    }
//
//    @Test
//    @Arguments(setup = "setupMultiArrays")
//    @IR(counts = {IRNode.X86_SCONV_I2L, "= 20"},
//        applyIfPlatform = {"x64", "true"},
//        phase = CompilePhase.MATCHING)
//    private static int test04(byte[] in1B, byte[] in2B, byte[] in3B, int i) {
//        return (byte)((in1B[i] * in2B[i]) + (in1B[i] * in3B[i]) + (in2B[i] * in3B[i]));
//    }
//
//    @Test
//    @Arguments(setup = "setupMultiArrays")
//    @IR(counts = {IRNode.X86_SCONV_I2L, "= 20"},
//        applyIfPlatform = {"x64", "true"},
//        phase = CompilePhase.MATCHING)
//    private static int test05(byte[] in1B, byte[] in2B, byte[] in3B, int i) {
//        return (byte)((in1B[i] * in2B[i]) + (in1B[i + 1] * in3B[i]) + (in2B[i + 1] * in3B[i + 1]));
//    }
//
//    @Test
//    @Arguments(setup = "setupMultiArrays")
//    @IR(counts = {IRNode.X86_SCONV_I2L, "= 20"},
//        applyIfPlatform = {"x64", "true"},
//        phase = CompilePhase.MATCHING)
//    private static int test06(byte[] in1B, byte[] in2B, byte[] in3B, int i) {
//        byte v0 = (byte)((in1B[i] * in2B[i]) + (in1B[i] * in3B[i]) + (in2B[i] * in3B[i]));
//        byte v1 = (byte)((in1B[i + 1] * in2B[i + 1]) + (in1B[i + 1] * in3B[i + 1]) + (in2B[i + 1] * in3B[i + 1]));
//        byte v2 = (byte)((in1B[i + 2] * in2B[i + 2]) + (in1B[i + 2] * in3B[i + 2]) + (in2B[i + 2] * in3B[i + 2]));
//        byte v3 = (byte)((in1B[i + 3] * in2B[i + 3]) + (in1B[i + 3] * in3B[i + 3]) + (in2B[i + 3] * in3B[i + 3]));
//        return v0 ^ v1 ^ v2 ^ v3;
//    }
//
//    @Test
//    @Arguments(setup = "setupMultiArrays")
//    @IR(counts = {IRNode.X86_SCONV_I2L, "= 20"},
//        applyIfPlatform = {"x64", "true"},
//        phase = CompilePhase.MATCHING)
//    private static int test07(byte[] b1, byte[] b2, byte[] b3, int i) {
//        i = Integer.min(Integer.max(i, 0), Integer.MAX_VALUE - 1);
//        return b1[i] + b2[i] + b3[i];
//    }
}

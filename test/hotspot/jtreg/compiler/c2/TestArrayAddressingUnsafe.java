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
import jdk.internal.misc.Unsafe;
import java.util.Objects;

public class TestArrayAddressingUnsafe {
    private static final Unsafe UNSAFE = Unsafe.getUnsafe();
    private static final int BASE = (int) UNSAFE.arrayBaseOffset(byte[].class);

    public static void main(String[] args) {
        TestFramework.runWithFlags("--add-exports", "java.base/jdk.internal.misc=ALL-UNNAMED");
    }

    @Setup
    public static Object[] setup() {
        byte[] a = new byte[1024];
        byte[] b = new byte[1024];
        a[42] = 20;
        b[42] = 22;
	int offset = BASE + 42;
        return new Object[] {a, b, offset};
    }

    @Test
    @Arguments(setup = "setup")
    @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
        applyIfPlatform = {"x64", "true"},
        phase = CompilePhase.MATCHING)
    private static int test(byte[] a, byte[] b, int offset) {
        int checkedOffset = Objects.checkIndex(offset, 1000);
        return UNSAFE.getByte(a, (long) checkedOffset) + UNSAFE.getByte(b, (long) checkedOffset);
    }
}

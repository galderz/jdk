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

import compiler.lib.compile_framework.CompileFramework;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.TemplateToken;
import compiler.lib.template_framework.library.TestFrameworkClass;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static compiler.lib.template_framework.Template.body;
import static compiler.lib.template_framework.Template.let;

/*
 * @test
 * @summary Test that commutative add node reduction chains are reassociated correctly
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @compile ../../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../../compiler/lib/generators/Generators.java
 * @compile ../../../compiler/lib/verify/Verify.java
 * @run driver compiler.c2.irTests.TestReductionReassociation2
 */
public class TestReductionReassociation2 {

    public static void main(String[] args) {
        CompileFramework comp = new CompileFramework();
        comp.addJavaSourceCode("p.xyz.InnerTest", generate(comp));
        comp.compile();
        comp.invoke("p.xyz.InnerTest", "main", new Object[] {new String[] {}});
    }

    static String generate(CompileFramework comp) {
        var testTemplate = Template.make("loop", (String loop) -> body(
            let("size", 1_000),
            """
            // --- $test start ---
            // $test with size=#size
            private static long[] $INPUT = new long[#size];
            static {
                Generators.G.fill(Generators.G.longs(), $INPUT);
            }
            private static Object $GOLD = $test($INPUT);

            @Setup
            public static Object[] $setup() {
                return new Object[] {$INPUT};
            }

            @Test
            @Arguments(setup = "$setup")
            public static Object $test(long[] a) {
                long result = Integer.MIN_VALUE;
                #loop
                return result;
            }

            @Check(test = "$test")
            public static void $check(Object result) {
                Verify.checkEQ(result, $GOLD);
            }
            // --- $test end   ---
            """
        ));

        // Create a test for each operator.
        int indent = 4;
        int[] reassocSizes = {1, 2, 4};
        List<String> loopBodies = Arrays.stream(reassocSizes).mapToObj(s -> reassocLoopBody(s, indent)).toList();
        List<TemplateToken> testTemplateTokens = loopBodies.stream().map(testTemplate::asToken).toList();

        // Create the test class, which runs all testTemplateTokens.
        return TestFrameworkClass.render(
                // package and class name.
                "p.xyz", "InnerTest",
                // Set of imports.
                Set.of("compiler.lib.generators.*",
                        "compiler.lib.verify.*"),
                // classpath, so the Test VM has access to the compiled class files.
                comp.getEscapedClassPathOfCompiledClasses(),
                // The list of tests.
                testTemplateTokens);
    }

    private static String reassocLoopBody(int size, int indent) {
        StringBuilder builder = new StringBuilder();

        String loopIndent = String.valueOf(' ').repeat(indent);
        String bodyIndent = String.valueOf(' ').repeat(indent + 4);

        builder.append("for (int i = 0; i < a.length; i += %d) {%n%s".formatted(size, bodyIndent));

        builder.append("var v0 = a[i + 0];%n%s".formatted(bodyIndent));

        int index = 1;
        for (int i = 1; i < size; i++) {
            builder.append("var v%d = a[i + %d];%n%s".formatted(index, index, bodyIndent));
            index++;
        }

        if (size == 1) {
            builder.append("var v1 = v0;%n%s".formatted(bodyIndent));
        }

        builder.append("var t0 = Math.max(v1, v0);%n%s".formatted(bodyIndent));

        index = 1;
        for (int i = index; i < size - 1; i++) {
            builder.append("var t%d = Math.max(v%d, t%d);%n%s".formatted(index, index + 1, index - 1, bodyIndent));
            index++;
        }

        builder.append("result = Math.max(result, t%d);%n%s".formatted(index - 1, loopIndent));
        builder.append("}%n%s".formatted(loopIndent));

        return builder.toString();
    }
}

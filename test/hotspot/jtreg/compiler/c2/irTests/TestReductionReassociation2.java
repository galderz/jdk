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

import java.util.ArrayList;
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
        var codeBlock = new CodeBlock(1);
        codeBlock.beginControlFlow("for (int i = 0; i < a.length; i += %d)", size);

        codeBlock.addStatement("var v0 = a[i + 0]");

        int index = 1;
        for (int i = 1; i < size; i++) {
            codeBlock.addStatement("var v%d = a[i + %d]", index, index);
            index++;
        }

        if (size == 1) {
            codeBlock.addStatement("var v1 = v0");
        }

        codeBlock.addStatement("var t0 = Math.max(v1, v0)");

        index = 1;
        for (int i = index; i < size - 1; i++) {
            codeBlock.addStatement("var t%d = Math.max(v%d, t%d)", index, index + 1, index - 1);
            index++;
        }

        return codeBlock
                .addStatement("result = Math.max(result, t%d)", index - 1)
                .endControlFlow()
                .toString();

    }

    private static final class CodeBlock {
        final List<String> parts = new ArrayList<>();
        final String indent = "    ";
        final int initialIndentLevel;

        CodeBlock() {
            this.initialIndentLevel = 0;
        }

        CodeBlock(int initialIndentLevel) {
            this.initialIndentLevel = initialIndentLevel;
        }

        CodeBlock beginControlFlow(String controlFlow, Object... args) {
            add(controlFlow + " {\n", args);
            indent();
            return this;
        }

        CodeBlock addStatement(String format, Object... args) {
            add(String.format(format + ";\n", args));
            return this;
        }

        CodeBlock endControlFlow() {
            unindent();
            add("}\n");
            return this;
        }

        private CodeBlock add(String code, Object... args) {
            add(String.format(code, args));
            return this;
        }

        private CodeBlock indent() {
            this.parts.add("$>");
            return this;
        }

        private CodeBlock unindent() {
            this.parts.add("$<");
            return this;
        }

        private CodeBlock add(String part) {
            parts.add(part);
            return this;
        }

        @Override
        public String toString() {
            final StringBuilder out = new StringBuilder();
            emit(out);
            return out.toString();
        }

        private void emit(StringBuilder out) {
            int indentLevel = initialIndentLevel;
            final List<String> copy = new ArrayList<>(parts);
            if (indentLevel > 0) {
                out.append(copy.removeFirst());
            }

            for (String part : copy) {
                switch (part) {
                    case "$>" -> indentLevel++;
                    case "$<" -> indentLevel--;
                    default -> {
                        out.append(indent.repeat(Math.max(0, indentLevel)));
                        out.append(part);
                    }
                }
            }
        }
    }
}

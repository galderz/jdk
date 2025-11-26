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

/*
 * @test id=vanilla
 * @bug 8351409
 * @summary Test reduction reassociation
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @compile ../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../compiler/lib/generators/Generators.java
 * @compile ../../compiler/lib/verify/Verify.java
 * @run driver compiler.loopopts.TestReductionReassociationFuzzer vanilla
 */

/*
 * @test id=no-superword
 * @bug 8351409
 * @summary Test reduction reassociation disabling superword
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @compile ../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../compiler/lib/generators/Generators.java
 * @compile ../../compiler/lib/verify/Verify.java
 * @run driver compiler.loopopts.TestReductionReassociationFuzzer no-superword
 */

package compiler.loopopts;

import compiler.lib.compile_framework.CompileFramework;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.TemplateToken;
import compiler.lib.template_framework.library.TestFrameworkClass;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static compiler.lib.template_framework.Template.*;

public class TestReductionReassociationFuzzer {
    public static void main(String[] args) {
        // Create a new CompileFramework instance.
        CompileFramework comp = new CompileFramework();

        long t0 = System.nanoTime();
        // Add a java source file.
        comp.addJavaSourceCode("compiler.loopopts.templated.ReductionReassociationFuzzer", generate(comp));

        long t1 = System.nanoTime();
        // Compile the source file.
        comp.compile();

        long t2 = System.nanoTime();

        String[] flags = switch(args[0]) {
            case "vanilla" -> new String[] {};
            case "no-superword" -> new String[] {"-XX:-UseSuperWord"};
            default -> throw new RuntimeException("unknown run id=" + args[0]);
        };
        // Run the tests without any additional VM flags.
        // compiler.loopopts.templated.ReductionReassociationFuzzer.main(new String[] {});
        comp.invoke("compiler.loopopts.templated.ReductionReassociationFuzzer", "main", new Object[] {flags});
        long t3 = System.nanoTime();

        System.out.println("Code Generation:  " + (t1-t0) * 1e-9f);
        System.out.println("Code Compilation: " + (t2-t1) * 1e-9f);
        System.out.println("Running Tests:    " + (t3-t2) * 1e-9f);
    }

    public static String generate(CompileFramework comp) {
        // Create a list to collect all tests.
        List<TemplateToken> testTemplateTokens = new ArrayList<>();

        final int size = 10_000;

        for(int factor : List.of(1, 2, 4, 8, 16)) {
            testTemplateTokens.add(TestGenerator.make(factor, size, ManualUnroll.Naive).generate());
        }

        for(int factor : List.of(1, 2, 4, 8, 16)) {
            testTemplateTokens.add(TestGenerator.make(factor, size, ManualUnroll.Reassoc).generate());
        }

        // Try a non-factor of 2 that the size is divisible by
        testTemplateTokens.add(TestGenerator.make(5, size, ManualUnroll.Naive).generate());
        testTemplateTokens.add(TestGenerator.make(5, size, ManualUnroll.Reassoc).generate());

        // Create the test class, which runs all testTemplateTokens.
        return TestFrameworkClass.render(
            // package and class name.
            "compiler.loopopts.templated", "ReductionReassociationFuzzer",
            // List of imports.
            Set.of("compiler.lib.generators.*",
                "compiler.lib.verify.*",
                "java.lang.foreign.*",
                "java.util.Random",
                "jdk.test.lib.Utils"),
            // classpath, so the Test VM has access to the compiled class files.
            comp.getEscapedClassPathOfCompiledClasses(),
            // The list of tests.
            testTemplateTokens);
    }

    enum ManualUnroll
    {
        Naive,  // result = max(v7, max(v6, max(v5, max(v4, max(v3, max(v2, max(v1, max(v0, result))))))))
        Reassoc, // result = max(result, max(v7, max(v6, max(v5, max(v4, max(v3, max(v2, max(v1, v0))))))))
    }

    record TestGenerator(
        int factor,
        int size,
        ManualUnroll manualUnroll
    ) {

        public static TestGenerator make(int factor, int size, ManualUnroll unroll) {
            return new TestGenerator(factor, size, unroll);
        }

        public TemplateToken generate() {
            final String id = manualUnroll.toString() + factor;
            var testTemplate = Template.make(() -> {
                String test = $("test_" + id);
                String input = $("input_" + id);
                String expected = $("expected_" + id);
                String setup = $("setup_" + id);
                String check = $("check_" + id);
                return scope(
                    """
                    // --- $test start ---

                    """,
                    generateArrayField(input),
                    generateExpectedField(test, expected, input),
                    generateSetup(setup, input),
                    generateTest(setup, test),
                    generateCheck(test, check, expected),
                    """

                    // --- $test end ---
                    """
                );
            });
            return testTemplate.asToken();
        }

        private TemplateToken generateManualUnrollNaive() {
            var template = Template.make(() -> scope(
                let("factor", factor),
                "var t0 = Math.max(v0, result);",
                IntStream.range(1, factor).mapToObj(i ->
                    List.of("var t", i, " = Math.max(v", i, ", t", i - 1, ");")
                ).toList(),
                "result = t",
                factor - 1,
                ";"
            ));
            return template.asToken();
        }

        private TemplateToken generateManualUnrollReassoc() {
            var template = Template.make(() -> scope(
                let("factor", factor),
                "var t0 = v0;",
                IntStream.range(1, factor).mapToObj(i ->
                    List.of("var t", i, " = Math.max(v", i, ", t", i - 1, ");")
                ).toList(),
                "result = Math.max(result, t",
                factor - 1,
                ");"
            ));
            return template.asToken();
        }

        private TemplateToken generateTest(String setup, String test) {
            var template = Template.make(() -> scope(
                let("factor", factor),
                let("setup", setup),
                let("test", test),
                """
                @Test
                @Arguments(setup = "#setup")
                public static Object #test(long[] a) {
                    long result = Integer.MIN_VALUE;
                    for (int i = 0; i < a.length; i += #factor) {
                        var v0 = a[i + 0];
                """,
                IntStream.range(1, factor).mapToObj(i ->
                    List.of("var v", i, " = a[i + ", i, "];")
                ).toList(),
                switch (manualUnroll) {
                    case Naive -> generateManualUnrollNaive();
                    case Reassoc -> generateManualUnrollReassoc();
                },
                """
                    }
                    return result;
                }
                """
            ));
            return template.asToken();
        }

        private TemplateToken generateArrayField(String input) {
            var template = Template.make(() -> scope(
                let("size", size),
                let("input", input),
                """
                private static long[] #input = new long[#size];
                static {
                    Generators.G.fill(Generators.G.longs(), #input);
                }
                """
            ));
            return template.asToken();
        }

        private TemplateToken generateExpectedField(String test, String expected, String input) {
            var template = Template.make(() -> scope(
                let("size", size),
                let("test", test),
                let("expected", expected),
                let("input", input),
                """
                private static Object #expected = #test(#input);
                """
            ));
            return template.asToken();
        }

        private TemplateToken generateSetup(String setup, String input) {
            var template = Template.make(() -> scope(
                let("setup", setup),
                let("input", input),
                """
                @Setup
                public static Object[] #setup() {
                    return new Object[] {#input};
                }
                """
            ));
            return template.asToken();
        }

        private TemplateToken generateCheck(String test, String check, String expected) {
            var template = Template.make(() -> scope(
                let("test", test),
                let("check", check),
                let("expected", expected),
                """
                @Check(test = "#test")
                public static void #check(Object result) {
                    Verify.checkEQ(result, #expected);
                }
                """
            ));
            return template.asToken();
        }
    }
}


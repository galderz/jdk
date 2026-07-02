package compiler.c2;

import compiler.lib.compile_framework.CompileFramework;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.TemplateToken;
import compiler.lib.template_framework.library.PrimitiveType;
import compiler.lib.template_framework.library.TestFrameworkClass;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static compiler.lib.template_framework.Template.let;
import static compiler.lib.template_framework.Template.scope;
import static compiler.lib.template_framework.library.CodeGenerationDataNameType.bytes;
import static compiler.lib.template_framework.library.CodeGenerationDataNameType.chars;
import static compiler.lib.template_framework.library.CodeGenerationDataNameType.shorts;

public class TestArrayAddressing {
    public static void main(String[] args) {
        // Create a new CompileFramework instance.
        CompileFramework comp = new CompileFramework();

        // Add a java source file.
        comp.addJavaSourceCode("compiler.c2.templated.ArrayAddressing", generate(comp));

        // Compile the source file.
        comp.compile();

        comp.invoke("compiler.c2.templated.ArrayAddressing", "main", new Object[] {new String[] {}});
    }

    private static String generate(CompileFramework comp) {
        final List<PrimitiveType> types = List.of(bytes(), shorts(), chars());

        final List<TemplateToken> tests = new ArrayList<>();
        tests.add(valueGenerator());
        for (PrimitiveType type : types) {
            tests.add(new TestGenerator(type).generate());
        }

        // Create the test class, which runs all testTemplateTokens.
        return TestFrameworkClass.render(
            // package and class name.
            "compiler.c2.templated", "ArrayAddressing",
            // List of imports.
            Collections.emptySet(),
            // classpath, so the Test VM has access to the compiled class files.
            comp.getEscapedClassPathOfCompiledClasses(),
            // The list of tests.
            tests);
    }

    static TemplateToken valueGenerator() {
        var template = Template.make(() -> scope(
            """
            private static final Generator<Integer> GEN_I = G.ints();
            """
        ));
        return template.asToken();
    }

    record TestGenerator(PrimitiveType type) {
        TemplateToken generate() {
            var template = Template.make(() -> scope(
                let("type", type.name()),
                """
                @Setup
                public static Object[] setup_#type() {
                    final #type[] arr = new #type[100];
                    for (int i = 0; i < arr.length; i++) {
                        arr[i] = (#type) GEN_I.next();
                    }
                    return new Object[] {arr, 42};
                }

                @Test
                @Arguments(setup = "setup_#type")
                @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
                    applyIfPlatform = {"x64", "true"},
                    phase = CompilePhase.MATCHING)
                private static int test_#type(#type[] arr, int i) {
                    return arr[i];
                }

                static volatile int volatileField;

                @Test
                @Arguments(setup = "setup_#type")
                @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
                    applyIfPlatform = {"x64", "true"},
                    phase = CompilePhase.MATCHING)
                private static int testSameOffset_#type(#type[] arr, int i) {
                    i = Integer.min(Integer.max(i, 0), 1000);
                    int v = arr[i];
                    volatileField = 42;
                    return v + arr[i];
                }

                @Test
                @Arguments(setup = "setup")
                @IR(counts = {IRNode.X86_SCONV_I2L, "= 0"},
                    applyIfPlatform = {"x64", "true"},
                    phase = CompilePhase.MATCHING)
                private static int testDifferentOffset_#type(#type[] arr, int i) {
                    i = Integer.min(Integer.max(i, 0), 1000);
                    return arr[i] + arr[i + 1];
                }
                """
            ));
            return template.asToken();
        }
    }
}
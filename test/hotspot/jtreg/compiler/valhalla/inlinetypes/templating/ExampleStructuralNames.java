/*
 * @test
 * @summary tbd
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @enablePreview
 * @compile ../../../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../../../compiler/lib/verify/Verify.java
 * @run main/othervm --enable-preview
 *                   compiler.valhalla.inlinetypes.templating.ExampleStructuralNames
 */

package compiler.valhalla.inlinetypes.templating;

import compiler.lib.compile_framework.CompileFramework;
import compiler.lib.template_framework.StructuralName;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.library.Hooks;
import compiler.lib.template_framework.library.TestFrameworkClass;

import java.util.List;
import java.util.Set;

import static compiler.lib.template_framework.Template.$;
import static compiler.lib.template_framework.Template.addStructuralName;
import static compiler.lib.template_framework.Template.body;
import static compiler.lib.template_framework.Template.let;
import static compiler.lib.template_framework.Template.structuralNames;

public class ExampleStructuralNames
{
    private static final BoxType boxType = new BoxType("Box");

    public static String generate(CompileFramework compiler) {
        var templateClass = Template.make(() -> body(
            // Register the method name, so we can later sample.
            addStructuralName($("Box"), boxType),
            """
            static final class $Box {}
            """
        ));

        var templateInstantiate = Template.make(() -> body(
            let("Box", structuralNames().exactOf(boxType).sample().name()),
            """
            private static #Box box = new #Box();
            private static Object $GOLD = $test();

            @Test
            public static Object $test() {
                return box;
            }

            @Check(test = "$test")
            public static void $check(Object result) {
                Verify.checkEQ(result, $GOLD);
            }
            """
        ));

        var testTemplate = Template.make(() -> body(
            """
            // --- $test start ---
            // $test with ...
            // --- $test end   ---
            """,
            Hooks.CLASS_HOOK.anchor(
                Hooks.CLASS_HOOK.insert(templateClass.asToken()),
                templateInstantiate.asToken()
            )
        ));

        var testTemplateTokens = List.of(testTemplate.asToken());

        return TestFrameworkClass.render(
            "compiler.valhalla.inlinetypes.templating.generated",
            "TestBox",
            Set.of("compiler.lib.ir_framework.ForceInline",
                "compiler.lib.verify.Verify",
                "compiler.valhalla.inlinetypes.InlineTypeIRNode"),
            compiler.getEscapedClassPathOfCompiledClasses(),
            testTemplateTokens
        );
    }

    public static void main(String[] args) throws Exception {
        final CompileFramework compiler = new CompileFramework();

        final String code = generate(compiler);
        System.out.println("Code: " + System.lineSeparator() + code);

        compiler.addJavaSourceCode("TestBox", code);

        compiler.compile(
            "--enable-preview",
            "--release",
            System.getProperty("java.specification.version")
        );

        compiler.invoke(
            "compiler.valhalla.inlinetypes.templating.generated.TestBox",
            "main",
            new Object[] {new String[] {
                "--enable-preview"
                // , "-XX:+PrintFieldLayout"
                // , "-XX:+PrintInlining"
            }}
        );
    }

    record BoxType(String name) implements StructuralName.Type {
        @Override
        public boolean isSubtypeOf(StructuralName.Type other) {
            return other instanceof BoxType(String n) && name().startsWith(n);
        }

        @Override
        public String toString() { return name(); }
    }
}


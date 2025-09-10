/*
 * @test
 * @summary tbd
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @enablePreview
 * @compile ../../../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../../../compiler/lib/verify/Verify.java
 * @run main/othervm --enable-preview
 *                   compiler.valhalla.inlinetypes.templating.ExampleNoNames
 */

package compiler.valhalla.inlinetypes.templating;

import compiler.lib.compile_framework.CompileFramework;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.library.TestFrameworkClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static compiler.lib.template_framework.Template.body;
import static compiler.lib.template_framework.Template.let;

public class ExampleNoNames
{
    public static String generate(CompileFramework compiler) {
        final Map<String, Object> namedArguments = new LinkedHashMap<>();
        namedArguments.put("typeId", 1);
        namedArguments.put("testId", 1);

        var templateClass = Template.make(() -> body(
            let("id", namedArguments.get("typeId")),
            """
            static final class Box#id {}
            """
        ));

        var templateTest = Template.make(() -> body(
            let("typeId", namedArguments.get("typeId")),
            let("testId", namedArguments.get("testId")),
            """
            private static Box#typeId box = new Box#typeId();
            private static Object GOLD#testId = test#testId();

            @Test
            public static Object test#testId() {
                return box;
            }

            @Check(test = "test#testId")
            public static void check#testId(Object result) {
                Verify.checkEQ(result, GOLD#testId);
            }
            """
        ));

        var testTemplate = Template.make(() -> body(
            let("testId", namedArguments.get("testId")),
            """
            // --- test#testId start ---
            """,
            templateClass.asToken(),
            templateTest.asToken(),
            """
            // --- test#testId end   ---
            """
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
}


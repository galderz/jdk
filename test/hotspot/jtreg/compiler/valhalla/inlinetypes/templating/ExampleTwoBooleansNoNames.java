/*
 * @test
 * @summary tbd
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @enablePreview
 * @compile ../../../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../../../compiler/lib/verify/Verify.java
 * @run main/othervm --enable-preview
 *                   compiler.valhalla.inlinetypes.templating.ExampleTwoBooleansNoNames
 */

package compiler.valhalla.inlinetypes.templating;

import compiler.lib.compile_framework.CompileFramework;
import compiler.lib.template_framework.DataName.Type;
import compiler.lib.template_framework.Template;
import compiler.lib.template_framework.library.CodeGenerationDataNameType;
import compiler.lib.template_framework.library.PrimitiveType;
import compiler.lib.template_framework.library.TestFrameworkClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static compiler.lib.template_framework.Template.body;
import static compiler.lib.template_framework.Template.let;

public class ExampleTwoBooleansNoNames
{
    record FieldArgument(String name, PrimitiveType type, Object value) {
        String declaration() {
            return "%s %s".formatted(type.name(), name);
        }

        String hash() {
            return "%s.hashCode(%s)".formatted(type.boxedTypeName(), value);
        }
    }

    public static String generate(CompileFramework compiler) {
        final List<PrimitiveType> types = List.of(CodeGenerationDataNameType.booleans(), CodeGenerationDataNameType.booleans());

        int fieldId = 0;
        final List<FieldArgument> fieldArguments = new ArrayList<>();
        for (PrimitiveType type : types) {
            fieldArguments.add(new FieldArgument("f" + fieldId++, type, type.con()));
        }

        final Map<String, Object> namedArguments = new LinkedHashMap<>();
        namedArguments.put("id", 1);

        var templateHash = Template.make(() -> body(
            """
            int hash() {
                return
            """,
            fieldArguments.stream().map(FieldArgument::hash).collect(Collectors.joining(" + ", "", " + 0;")),
            """
            }
            """
        ));

        var templateClass = Template.make(() -> body(
            let("id", namedArguments.get("id")),
            let("fieldDeclarations", fieldArguments.stream().map(FieldArgument::declaration).collect(Collectors.joining(", "))),
            """
            value record Box#id(#fieldDeclarations) {
            """,
            templateHash.asToken(),
            """
            }
            """
        ));

        var templateTest = Template.make(() -> body(
            let("id", namedArguments.get("id")),
            let("fieldValues", fieldArguments.stream().map(FieldArgument::value).map(Object::toString).collect(Collectors.joining(", "))),
            """
            private static Box#id box = new Box#id(#fieldValues);
            private static Object GOLD#id = test#id();

            @Test
            public static Object test#id() {
                return box;
            }

            @Check(test = "test#id")
            public static void check#id(Object result) {
                Verify.checkEQ(result, GOLD#id);
            }
            """
        ));

        var testTemplate = Template.make(() -> body(
            let("id", namedArguments.get("id")),
            """
            // --- test#id start ---
            """,
            templateClass.asToken(),
            templateTest.asToken(),
            """
            // --- test#id end   ---
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

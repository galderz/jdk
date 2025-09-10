/*
 * @test
 * @summary tbd
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @enablePreview
 * @compile ../../../../compiler/lib/ir_framework/TestFramework.java
 * @compile ../../../../compiler/lib/verify/Verify.java
 * @run main/othervm --enable-preview
 *                   compiler.valhalla.inlinetypes.templating.ExampleTwoLongsNoNames
 */

package compiler.valhalla.inlinetypes.templating;

import compiler.lib.compile_framework.CompileFramework;
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

public class ExampleTwoLongsNoNames
{
    record FieldArgument(String name, PrimitiveType type, Object value) {
        String declaration() {
            return "%s %s".formatted(type.name(), name);
        }

        String init() {
            return "var %s = %s".formatted(name, value);
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

        var templateClass = Template.make(() -> body(
            let("id", namedArguments.get("id")),
            let("fieldDeclarations", fieldArguments.stream().map(FieldArgument::declaration).collect(Collectors.joining(", "))),
            """
            value record Box#id(#fieldDeclarations) {}
            """
        ));

        var templateTest = Template.make(() -> body(
            let("id", namedArguments.get("id")),
            let("fieldInits", fieldArguments.stream().map(FieldArgument::init).collect(Collectors.joining(";\n    ", "", ";"))),
            let("fieldNames", fieldArguments.stream().map(FieldArgument::name).collect(Collectors.joining(", "))),
            """
            private static int expected#id = test#id();

            @Test
            @IR(failOn = {ALLOC_OF_BOX_KLASS, STORE_OF_ANY_KLASS, IRNode.UNSTABLE_IF_TRAP, IRNode.PREDICATE_TRAP})
            public static int test#id() {
                #fieldInits
                var box = new Box#id(#fieldNames);
                return box.hashCode();
            }

            @Check(test = "test#id")
            public static void check#id(int value) {
                Verify.checkEQ(value, expected#id);
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

        var templateIrNodes = Template.make(() -> body(
            """
            static final String BOX_KLASS = "compiler/valhalla/inlinetypes/templating/generated/.*Box\\\\w*";
            static final String ANY_KLASS = "compiler/valhalla/inlinetypes/templating/generated/[\\\\w/]*";

            static final String ALLOC_OF_BOX_KLASS = IRNode.PREFIX + "ALLOC_OF_BOX_KLASS" + InlineTypeIRNode.POSTFIX;
            static {
                 IRNode.allocateOfNodes(ALLOC_OF_BOX_KLASS, BOX_KLASS);
            }

            static final String STORE_OF_ANY_KLASS = IRNode.PREFIX + "STORE_OF_ANY_KLASS" + InlineTypeIRNode.POSTFIX;
            static {
                IRNode.anyStoreOfNodes(STORE_OF_ANY_KLASS, ANY_KLASS);
            }
            """
        ));

        var testTemplateTokens = List.of(templateIrNodes.asToken(), testTemplate.asToken());

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

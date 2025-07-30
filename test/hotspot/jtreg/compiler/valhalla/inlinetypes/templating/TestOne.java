/*
 * @test
 * @summary tbd
 * @modules java.base/jdk.internal.misc
 * @library /test/lib /
 * @compile ../../../../compiler/lib/ir_framework/TestFramework.java
 * @run driver compiler.valhalla.inlinetypes.templating.TestOne
 */

package compiler.valhalla.inlinetypes.templating;

import compiler.lib.compile_framework.CompileFramework;
import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;

import javax.lang.model.element.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TestOne {

    static String mainMethod(CompileFramework compiler) {
        return """
            public static void main(String[] args) {
                final TestFramework framework = new TestFramework(TestBox.class);
                framework.addFlags("-classpath", "%s");
                framework.addFlags("--enable-preview");
                framework.start();
            }
            """.formatted(compiler.getEscapedClassPathOfCompiledClasses());
    }

    public static String generate(CompileFramework compiler) {
        return """
               package compiler.valhalla.inlinetypes.templating.generated;

               import compiler.lib.ir_framework.*;
               import jdk.test.lib.Asserts;

               %s

               public class TestBox {
                   static final String BOX_KLASS = "compiler/valhalla/inlinetypes/templating/.*Box\\\\w*";
                   static final String ANY_KLASS = "compiler/valhalla/inlinetypes/templating/[\\\\w/]*";
                   static final String POSTFIX = "#I_";

                   static final String ALLOC_OF_BOX_KLASS = IRNode.PREFIX + "ALLOC_OF_BOX_KLASS" + POSTFIX;
                   static {
                        IRNode.allocateOfNodes(ALLOC_OF_BOX_KLASS, BOX_KLASS);
                   }

                   static final String STORE_OF_ANY_KLASS = IRNode.PREFIX + "STORE_OF_ANY_KLASS" + POSTFIX;
                   static {
                       IRNode.anyStoreOfNodes(STORE_OF_ANY_KLASS, ANY_KLASS);
                   }

                   %s

                   @Test
                   @IR(failOn = {ALLOC_OF_BOX_KLASS, STORE_OF_ANY_KLASS, IRNode.UNSTABLE_IF_TRAP, IRNode.PREDICATE_TRAP})
                   public boolean test1() {
                       final Box v = new Box(true);
                       return v.b;
                   }

                   @Run(test = "test1")
                   public void test1_verifier() {
                       final boolean result = test1();
                       Asserts.assertTrue(result);
                   }
               }
               """.formatted(
                   new Type(
                       TypeKind.VALUE_CLASS,
                       "Box",
                       new Fields(new Field(boolean.class.getTypeName(), "b", new Modifiers(Modifier.FINAL))),
                       new Methods(new Method("Box", new Modifiers(Modifier.PUBLIC), new Parameters(new Parameter(boolean.class.getTypeName(), "b")), new Statements("this.b = b")))),
                   mainMethod(compiler)
        );
    }

    public static void main(String[] args) throws Exception {
        final CompileFramework compiler = new CompileFramework();

        final String src = generate(compiler);
        System.out.println("Source code:\n" + src);
        compiler.addJavaSourceCode("TestBox", src);

        compiler.compile("--enable-preview", "--release", System.getProperty("java.specification.version"));

        final String[] command = {
            "-classpath",
            compiler.getEscapedClassPathOfCompiledClasses(),
            "--enable-preview",
            "-Dtest.jdk=" + System.getProperty("test.jdk"),
            "compiler.valhalla.inlinetypes.templating.generated.TestBox"
        };

        final OutputAnalyzer analyzer = ProcessTools.executeTestJava(command);
        analyzer.reportDiagnosticSummary();
        analyzer.stdoutContains("Test complete.");
        analyzer.stdoutContains("Passed. Execution successful");
        analyzer.shouldHaveExitValue(0);
    }

    record Type(TypeKind kind, String name, Fields fields, Methods methods) {

        @Override
        public String toString() {
            return """
                %s %s {
                    %s

                    %s
                }
                """.formatted(kind, name, fields, methods);
        }
    }

    enum TypeKind {
        VALUE_CLASS,
        ;

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT).replace("_", " ");
        }
    }

    record Methods(Method... methods) {
        @Override
        public String toString() {
            return Stream.of(methods)
                .map(Object::toString)
                .collect(Collectors.joining(System.lineSeparator() + System.lineSeparator()));
        }
    }

    record Method(String methodName, Modifiers modifiers, Parameters parameters, Statements statements) {
        @Override
        public String toString() {
            return """
                %s %s(%s) {
                    %s
                }
                """.formatted(modifiers, methodName, parameters, statements);
        }
    }

    record Statements(String... statements) {
        @Override
        public String toString() {
            return Stream.of(statements)
                .collect(Collectors.joining(";" + System.lineSeparator(), "", ";"));
        }
    }

    record Parameters(Parameter... parameters) {
        @Override
        public String toString() {
            return Stream.of(parameters)
                .map(Objects::toString)
                .collect(Collectors.joining(", "));
        }
    }

    record Parameter(String typeName, String name) {
        @Override
        public String toString() {
            return typeName + " " + name;
        }
    }

    record Fields(Field... fields) {
        @Override
        public String toString() {
            return Stream.of(fields)
                .map(Object::toString)
                .collect(Collectors.joining(";" + System.lineSeparator(), "", ";"));
        }
    }

    record Field(String typeName, String name, Modifiers modifiers) {
        @Override
        public String toString() {
            return String.format("%s %s %s", modifiers, typeName, name);
        }
    }

    record Modifiers(Modifier... modifiers) {
        @Override
        public String toString() {
            return Stream.of(modifiers)
                .map(Object::toString)
                .collect(Collectors.joining(" "));
        }
    }
}

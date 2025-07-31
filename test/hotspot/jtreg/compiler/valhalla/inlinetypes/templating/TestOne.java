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

public class TestOne {

    public static String generate(CompileFramework compiler) {
        return """
               package compiler.valhalla.inlinetypes.templating.generated;

               import compiler.lib.ir_framework.*;
               import jdk.test.lib.Asserts;

               public class TestBox {
                   static final String BOX_KLASS = "compiler/valhalla/inlinetypes/templating/generated/.*Box\\\\w*";
                   static final String ANY_KLASS = "compiler/valhalla/inlinetypes/templating/generated/[\\\\w/]*";
                   static final String POSTFIX = "#I_";

                   static final String ALLOC_OF_BOX_KLASS = IRNode.PREFIX + "ALLOC_OF_BOX_KLASS" + POSTFIX;
                   static {
                        IRNode.allocateOfNodes(ALLOC_OF_BOX_KLASS, BOX_KLASS);
                   }

                   static final String STORE_OF_ANY_KLASS = IRNode.PREFIX + "STORE_OF_ANY_KLASS" + POSTFIX;
                   static {
                       IRNode.anyStoreOfNodes(STORE_OF_ANY_KLASS, ANY_KLASS);
                   }

                   public static void main(String[] args) {
                       final TestFramework framework = new TestFramework(TestBox.class);
                       framework.addFlags("-classpath", "%s");
                       framework.addFlags("--enable-preview");
                       framework.addFlags("-XX:-DoEscapeAnalysis");
                       framework.start();
                   }

                   value class Box1 {
                       final boolean b;

                       Box1(boolean b) {
                           this.b = b;
                       }
                   }

                   @Test
                   @IR(failOn = {ALLOC_OF_BOX_KLASS, STORE_OF_ANY_KLASS, IRNode.UNSTABLE_IF_TRAP, IRNode.PREDICATE_TRAP})
                   public boolean test1() {
                       final Box1 v = new Box1(true);
                       return v.b;
                   }

                   @Check(test = "test1")
                   public void test1_verifier(boolean result) {
                       Asserts.assertTrue(result);
                   }
               }
               """.formatted(compiler.getEscapedClassPathOfCompiledClasses());
    }

    public static void main(String[] args) throws Exception {
        final CompileFramework compiler = new CompileFramework();

        compiler.addJavaSourceCode("TestBox", generate(compiler));

        compiler.compile("--enable-preview", "--release", System.getProperty("java.specification.version"));

        final String[] command = {
            "-classpath",
            compiler.getEscapedClassPathOfCompiledClasses(),
            "--enable-preview",
            "-Dtest.jdk=" + System.getProperty("test.jdk"),
            "-DReportStdout=true",
            "compiler.valhalla.inlinetypes.templating.generated.TestBox"
        };

        final OutputAnalyzer analyzer = ProcessTools.executeTestJava(command);
        analyzer.reportDiagnosticSummary();
        analyzer.stdoutContains("Test complete.");
        analyzer.stdoutContains("Passed. Execution successful");
        analyzer.shouldHaveExitValue(0);
    }
}

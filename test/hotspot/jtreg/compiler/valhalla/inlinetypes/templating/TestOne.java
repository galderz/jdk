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

    // todo use more precise regex for IR failOn

    public static String generate(CompileFramework compiler) {
        return """
               package compiler.valhalla.inlinetypes.templating.generated;

               import compiler.lib.ir_framework.*;
               import jdk.test.lib.Asserts;

               value class Box {
                   final boolean b;

                   Box(boolean b) {
                       this.b = b;
                   }
               }

               public class TestBox {
                   %s

                   @Test
                   @IR(failOn = {IRNode.ALLOC, IRNode.STORE, IRNode.TRAP})
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
               """.formatted(mainMethod(compiler));
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
            "compiler.valhalla.inlinetypes.templating.generated.TestBox"
        };

        final OutputAnalyzer analyzer = ProcessTools.executeTestJava(command);
        analyzer.reportDiagnosticSummary();
        analyzer.stdoutContains("Test complete.");
        analyzer.stdoutContains("Passed. Execution successful");
        analyzer.shouldHaveExitValue(0);
    }

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
}

/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.psi.formatter.java;

import com.intellij.psi.codeStyle.CommonCodeStyleSettings;

/**
 * Is intended to hold specific java formatting tests for 'braces placement' settings (
 * <code>Project Settings - Code Style - Alignment and Braces</code>).
 *
 * @author Denis Zhdanov
 * @since Apr 27, 2010 6:39:24 PM
 */
public class JavaFormatterBracesTest extends AbstractJavaFormatterTest {

  public void testBracePositioningAtPreviousLine() throws Exception {
    // Inspired by IDEADEV-18529
    doTextTest(
      "public class TestBed\n" +
      "{\n" +
      "    public void methodOne()\n" +
      "    {\n" +
      "        //code...\n" +
      "    }\n" +
      "\n" +
      "    @SomeAnnotation\n" +
      "            <T extends Comparable> void methodTwo(T item) {\n" +
      "        //code...\n" +
      "    }\n" +
      "\n" +
      "    private void methodThree(String s) {\n" +
      "        //code...\n" +
      "    }\n" +
      "}",

      "public class TestBed {\n" +
      "    public void methodOne() {\n" +
      "        //code...\n" +
      "    }\n" +
      "\n" +
      "    @SomeAnnotation\n" +
      "    <T extends Comparable> void methodTwo(T item) {\n" +
      "        //code...\n" +
      "    }\n" +
      "\n" +
      "    private void methodThree(String s) {\n" +
      "        //code...\n" +
      "    }\n" +
      "}");
  }

  public void testSimpleBlockInOneLinesAndForceBraces() throws Exception {
    // Inspired by IDEA-19328
    getSettings().KEEP_SIMPLE_BLOCKS_IN_ONE_LINE = true;
    getSettings().IF_BRACE_FORCE = CommonCodeStyleSettings.FORCE_BRACES_ALWAYS;

    doMethodTest(
      "if (x > y) System.out.println(\"foo!\");",

      "if (x > y) { System.out.println(\"foo!\"); }"
    );
  }

  public void testEnforcingBracesForExpressionEndingWithLineComment() throws Exception {
    // Inspired by IDEA-57936
    getSettings().IF_BRACE_FORCE = CommonCodeStyleSettings.FORCE_BRACES_ALWAYS;

    doMethodTest(
      "if (true) i = 1; // Cool if\n" +
      "else i = 2;",

      "if (true) {\n" +
      "    i = 1; // Cool if\n" +
      "} else {\n" +
      "    i = 2;\n" +
      "}"
    );
  }

  public void testMoveBraceOnNextLineForAnnotatedMethod() throws Exception {
    // Inspired by IDEA-59336
    getSettings().METHOD_BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE;
    getSettings().KEEP_SIMPLE_METHODS_IN_ONE_LINE = true;

    doClassTest(
      "@Override\n" +
      "public int hashCode() {\n" +
      "}\n" +
      "@Deprecated\n" +
      "void foo() {\n" +
      "}",
      "@Override\n" +
      "public int hashCode()\n" +
      "{\n" +
      "}\n" +
      "\n" +
      "@Deprecated\n" +
      "void foo()\n" +
      "{\n" +
      "}"
    );
  }
  
  public void testKeepSimpleClassesAndInterfacesInOneLine() {
    // Inspired by IDEA-65433
    getSettings().KEEP_SIMPLE_CLASSES_IN_ONE_LINE = true;
    
    String[] tests = {
      "class Test {}",
      
      "interface Test {}",
      
      "class Test {\n" +
      "    void test() {\n" +
      "        new Object() {};\n" +
      "    }\n" +
      "}",
      
      "class Test {\n" +
      "    void test() {\n" +
      "        bind(new TypeLiteral<MyType>() {}).toProvider(MyProvider.class);\n" +
      "    }\n" +
      "}"
    };

    for (String test : tests) {
      doTextTest(test, test);
    }
  }

  public void testKeepSimpleClassesInOneLineAndLeftBraceOnNextLine() throws Exception {
    // Inspired by IDEA-75053.
    getSettings().KEEP_SIMPLE_CLASSES_IN_ONE_LINE = true;
    getSettings().CLASS_BRACE_STYLE = CommonCodeStyleSettings.NEXT_LINE;
    
    String text =
      "class Test\n" +
      "{\n" +
      "    void foo() {\n" +
      "        bind(new TypeLiteral<MyType>() {}).toProvider(MyProvider.class);\n" +
      "    }\n" +
      "}";
    doTextTest(text, text);
  }
}

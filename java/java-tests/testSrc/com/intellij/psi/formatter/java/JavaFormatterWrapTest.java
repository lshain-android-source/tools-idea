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

import com.intellij.lang.java.JavaLanguage;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.codeStyle.CommonCodeStyleSettings;

/**
 * Is intended to hold specific java formatting tests for 'wrapping' settings.
 *
 * @author Denis Zhdanov
 * @since Apr 29, 2010 4:06:15 PM
 */
public class JavaFormatterWrapTest extends AbstractJavaFormatterTest {

  public void testWrappingAnnotationArrayParameters() throws Exception {
    getSettings().getRootSettings().RIGHT_MARGIN = 80;
    getSettings().ARRAY_INITIALIZER_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
    doTextTest(
      "@AttributeOverrides( { @AttributeOverride(name = \"id\", column = @Column(name = \"recovery_id\"))," +
      "@AttributeOverride(name = \"transactionReference\", column = @Column(name = \"deal_reference\"))," +
      "@AttributeOverride(name = \"eventDate\", column = @Column(name = \"recovery_date\"))," +
      "@AttributeOverride(name = \"amount\", column = @Column(name = \"recovery_amount\"))," +
      "@AttributeOverride(name = \"currency\", column = @Column(name = \"local_currency\"))," +
      "@AttributeOverride(name = \"exchangeRate\", column = @Column(name = \"exchange_rate\"))," +
      "@AttributeOverride(name = \"exchangeRateDate\", column = @Column(name = \"recovery_date\", insertable = false, updatable = false))," +
      "@AttributeOverride(name = \"exchangeRateAlterationJustification\", column = @Column(name = \"exchange_rate_justification\"))," +
      "@AttributeOverride(name = \"systemExchangeRate\", column = @Column(name = \"system_exchange_rate\")) })\n" +
      "class Foo {\n" +
      "}",
      
      "@AttributeOverrides({\n" +
      "        @AttributeOverride(name = \"id\", column = @Column(name = \"recovery_id\")),\n" +
      "        @AttributeOverride(name = \"transactionReference\", column = @Column(name = \"deal_reference\")),\n" +
      "        @AttributeOverride(name = \"eventDate\", column = @Column(name = \"recovery_date\")),\n" +
      "        @AttributeOverride(name = \"amount\", column = @Column(name = \"recovery_amount\")),\n" +
      "        @AttributeOverride(name = \"currency\", column = @Column(name = \"local_currency\")),\n" +
      "        @AttributeOverride(name = \"exchangeRate\", column = @Column(name = \"exchange_rate\")),\n" +
      "        @AttributeOverride(name = \"exchangeRateDate\", column = @Column(name = \"recovery_date\", insertable = false, updatable = false)),\n" +
      "        @AttributeOverride(name = \"exchangeRateAlterationJustification\", column = @Column(name = \"exchange_rate_justification\")),\n" +
      "        @AttributeOverride(name = \"systemExchangeRate\", column = @Column(name = \"system_exchange_rate\"))})\n" +
      "class Foo {\n" +
      "}"
    );
  }

  public void testAnnotationParamValueExceedingRightMargin() throws Exception {
    // Inspired by IDEA-18051
    getSettings().getRootSettings().RIGHT_MARGIN = 80;
    doTextTest(
      "package formatting;\n" +
      "\n" +
      "public class EnumInAnnotationFormatting {\n" +
      "\n" +
      "    public enum TheEnum {\n" +
      "\n" +
      "        FIRST,\n" +
      "        SECOND,\n" +
      "        THIRD,\n" +
      "\n" +
      "    }\n" +
      "\n" +
      "    public @interface TheAnnotation {\n" +
      "\n" +
      "        TheEnum[] value();\n" +
      "\n" +
      "        String comment();\n" +
      "\n" +
      "    }\n" +
      "\n" +
      "\n" +
      "    @TheAnnotation(value = {TheEnum.FIRST, TheEnum.SECOND}, comment = \"some long comment that goes longer that right margin 012345678901234567890\")\n" +
      "    public class Test {\n" +
      "\n" +
      "    }\n" +
      "\n" +
      "}",
      "package formatting;\n" +
      "\n" +
      "public class EnumInAnnotationFormatting {\n" +
      "\n" +
      "    public enum TheEnum {\n" +
      "\n" +
      "        FIRST,\n" +
      "        SECOND,\n" +
      "        THIRD,\n" +
      "\n" +
      "    }\n" +
      "\n" +
      "    public @interface TheAnnotation {\n" +
      "\n" +
      "        TheEnum[] value();\n" +
      "\n" +
      "        String comment();\n" +
      "\n" +
      "    }\n" +
      "\n" +
      "\n" +
      "    @TheAnnotation(value = {TheEnum.FIRST, TheEnum.SECOND}, comment = \"some long comment that goes longer that right margin 012345678901234567890\")\n" +
      "    public class Test {\n" +
      "\n" +
      "    }\n" +
      "\n" +
      "}");
  }

  public void testEnumConstantsWrapping() {
    // Inspired by IDEA-54667
    getSettings().ENUM_CONSTANTS_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
    getSettings().getRootSettings().RIGHT_MARGIN = 80;

    // Don't expect the constants to be placed on new line.
    doTextTest(
      "enum Test {FIRST, SECOND}",
      "enum Test {FIRST, SECOND}"
    );

    // Expect not only enum constants to be wrapped but line break inside enum-level curly braces as well.
    doTextTest(
      "enum Test {FIRST, SECOND, THIIIIIIIIIIIIIIIIIRRDDDDDDDDDDDDDD, FOURTHHHHHHHHHHHHHHHH}",

      "enum Test {\n" +
      "    FIRST, SECOND, THIIIIIIIIIIIIIIIIIRRDDDDDDDDDDDDDD, FOURTHHHHHHHHHHHHHHHH\n" +
      "}"
    );
  }

  public void testMethodAnnotationFollowedBySingleLineComment() throws Exception {
    // Inspired by IDEA-22808
    getSettings().METHOD_ANNOTATION_WRAP = CommonCodeStyleSettings.WRAP_ALWAYS;

    String text =
      "@Test//mycomment\n" +
      "public void foo() {\n" +
      "}";
    
    // Expecting the code to be left as-is
    doClassTest(text, text);
  }

  public void testWrapCompoundStringLiteralThatEndsAtRightMargin() {
    // Inspired by IDEA-82398
    getSettings().getRootSettings().RIGHT_MARGIN = 30;
    getSettings().getRootSettings().getCommonSettings(JavaLanguage.INSTANCE).WRAP_LONG_LINES = true;

    final String text = "class Test {\n" +
                        "    String s = \"first line \" +\n" +
                        "            +\"second line\";\n" +
                        "}";
    doTextTest(text, text);
  }
  
  public void testWrapLongLine() {
    // Inspired by IDEA-55782
    getSettings().getRootSettings().RIGHT_MARGIN = 50;
    getSettings().getRootSettings().getCommonSettings(JavaLanguage.INSTANCE).WRAP_LONG_LINES = true;

    doTextTest(
      "class TestClass {\n" +
      "    // Single line comment that is long enough to exceed right margin\n" +
      "    /* Multi line comment that is long enough to exceed right margin*/\n" +
      "    /**\n" +
      "      Javadoc comment that is long enough to exceed right margin" +
      "     */\n" +
      "     public String s = \"this is a string that is long enough to be wrapped\"\n" +
      "}",
      "class TestClass {\n" +
      "    // Single line comment that is long enough \n" +
      "    // to exceed right margin\n" +
      "    /* Multi line comment that is long enough \n" +
      "    to exceed right margin*/\n" +
      "    /**\n" +
      "     * Javadoc comment that is long enough to \n" +
      "     * exceed right margin\n" +
      "     */\n" +
      "    public String s = \"this is a string that is\" +\n" +
      "            \" long enough to be wrapped\"\n" +
      "}"
    );
  }

  public void testWrapLongLineWithTabs() {
    // Inspired by IDEA-55782
    getSettings().getRootSettings().RIGHT_MARGIN = 20;
    getSettings().getRootSettings().getCommonSettings(JavaLanguage.INSTANCE).WRAP_LONG_LINES = true;
    getIndentOptions().USE_TAB_CHARACTER = true;
    getIndentOptions().TAB_SIZE = 4;

    doTextTest(
      "class TestClass {\n" +
      "\t \t   //This is a comment\n" +
      "}",
      "class TestClass {\n" +
      "\t//This is a \n" +
      "\t// comment\n" +
      "}"
    );
  }

  public void testWrapLongLineWithSelection() {
    // Inspired by IDEA-55782
    getSettings().getRootSettings().RIGHT_MARGIN = 20;
    getSettings().getRootSettings().getCommonSettings(JavaLanguage.INSTANCE).WRAP_LONG_LINES = true;

    String initial =
      "class TestClass {\n" +
      "    //This is a comment\n" +
      "    //This is another comment\n" +
      "}";

    int start = initial.indexOf("//");
    int end = initial.indexOf("comment");
    myTextRange = new TextRange(start, end);
    doTextTest(initial, initial);

    myLineRange = new TextRange(1, 1);
    doTextTest(
      initial,
      "class TestClass {\n" +
      "    //This is a \n" +
      "    // comment\n" +
      "    //This is another comment\n" +
      "}"
    );
  }
  
  public void testWrapMethodAnnotationBeforeParams() throws Exception {
    // Inspired by IDEA-59536
    getSettings().getRootSettings().RIGHT_MARGIN = 90;
    getSettings().METHOD_ANNOTATION_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
    getSettings().METHOD_PARAMETERS_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
    
    doClassTest(
      "@SuppressWarnings({\"SomeInspectionIWantToIgnore\"}) public void doSomething(int x, int y) {}",
      "@SuppressWarnings({\"SomeInspectionIWantToIgnore\"})\n" +
      "public void doSomething(int x, int y) {" +
      "\n}"
    );
  }
  
  public void testMultipleExpressionInSameLine() throws Exception {
    // Inspired by IDEA-64975.
    
    getSettings().KEEP_MULTIPLE_EXPRESSIONS_IN_ONE_LINE = true;
    doMethodTest(
      "int i = 1; int j = 2;",
      "int i = 1; int j = 2;"
    );

    getSettings().KEEP_MULTIPLE_EXPRESSIONS_IN_ONE_LINE = false;
    doMethodTest(
      "int i = 1; int j = 2;",
      "int i = 1;\n" +
      "int j = 2;"
    );
  }
  
  public void testIncompleteFieldAndAnnotationWrap() throws Exception {
    // Inspired by IDEA-64725
    
    getSettings().FIELD_ANNOTATION_WRAP = CommonCodeStyleSettings.DO_NOT_WRAP;
    doClassTest(
      "@NotNull Comparable<String>",
      "@NotNull Comparable<String>"
    );
  }

  public void testResourceListWrap() throws Exception {
    getSettings().KEEP_SIMPLE_BLOCKS_IN_ONE_LINE = true;
    getSettings().getRootSettings().RIGHT_MARGIN = 40;
    getSettings().RESOURCE_LIST_WRAP = CommonCodeStyleSettings.WRAP_AS_NEEDED;
    doMethodTest("try (MyResource r1 = null; MyResource r2 = null) { }",
                 "try (MyResource r1 = null;\n" +
                 "     MyResource r2 = null) { }");

    getSettings().RESOURCE_LIST_LPAREN_ON_NEXT_LINE = true;
    getSettings().RESOURCE_LIST_RPAREN_ON_NEXT_LINE = true;
    doMethodTest("try (MyResource r1 = null; MyResource r2 = null) { }",
                 "try (\n" +
                 "        MyResource r1 = null;\n" +
                 "        MyResource r2 = null\n" +
                 ") { }");
  }

  public void testLineLongEnoughToExceedAfterFirstWrapping() throws Exception {
    // Inspired by IDEA-103624
    getSettings().WRAP_LONG_LINES = true;
    getSettings().getRootSettings().RIGHT_MARGIN = 40;
    getSettings().ALIGN_MULTILINE_PARAMETERS_IN_CALLS = true;
    doMethodTest(
      "test(1,\n" +
      "     2,\n" +
      "     MyTestClass.loooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooongMethod());\n" +
      "int i = 1;\n" +
      "int j = 2;",
      "test(1,\n" +
      "     2,\n" +
      "     MyTestClass\n" +
      "             .loooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooongMethod());\n" +
      "int i = 1;\n" +
      "int j = 2;"
    );
  }

  public void testNoUnnecessaryWrappingIsPerformedForLongLine() throws Exception {
    // Inspired by IDEA-103624
    getSettings().WRAP_LONG_LINES = true;
    getSettings().getRootSettings().RIGHT_MARGIN = 40;
    getSettings().ALIGN_MULTILINE_PARAMETERS_IN_CALLS = true;
    String text =
      "test(1,\n" +
      "     2,\n" +
      "     Test.\n" +
      "             loooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooooongMethod());\n" +
      "int i = 1;\n" +
      "int j = 2;";
    doMethodTest(text, text);
  }
}

package com.intellij.codeInsight.daemon.quickFix;

/**
 * @author ven
 */
public class GenericCreateFromUsageTest extends LightQuickFix15TestCase {

  public void test() throws Exception { doAllTests(); }

  @Override
  protected String getBasePath() {
    return "/codeInsight/daemonCodeAnalyzer/quickFix/genericCreateFromUsage";
  }
}

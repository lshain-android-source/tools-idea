package com.siyeh.ig.migration;

import com.siyeh.ig.IGInspectionTestCase;

public class UnnecessaryUnboxingInspectionTest extends IGInspectionTestCase {

  public void test() throws Exception {
    doTest("com/siyeh/igtest/migration/unnecessary_unboxing",
           new UnnecessaryUnboxingInspection());
  }
}
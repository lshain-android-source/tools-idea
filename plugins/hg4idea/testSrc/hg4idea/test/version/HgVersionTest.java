/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package hg4idea.test.version;

import hg4idea.test.HgPlatformTest;
import org.zmlx.hg4idea.util.HgVersion;

import java.lang.reflect.Field;

/**
 * @author Nadya Zabrodina
 */
public class HgVersionTest extends HgPlatformTest {

  private static final TestHgVersion[] commonTests = {
    new TestHgVersion("Mercurial Distributed SCM (version 2.6.2)", 2, 6, 2),
    new TestHgVersion("Mercurial Distributed SCM (version 2.6+20130507)", 2, 6, 20130507),
    new TestHgVersion("Mercurial Distributed SCM (version 1.9.5)", 1, 9, 5),
    new TestHgVersion("Mercurial Distributed SCM (version 2.6)", 2, 6, 0),
    new TestHgVersion("Mercurial Distributed SCM (version 2.7-rc+5-ca2dfc2f63eb)", 2, 7, 0),
    new TestHgVersion("Распределенная SCM Mercurial (версия 2.0.2)", 2, 0, 2),
    new TestHgVersion("Mercurial Distributed SCM (version 2.4.2+20130102)", 2, 4, 2),
    new TestHgVersion("Распределенная SCM Mercurial (версия 2.6.1)", 2, 6, 1)
  };

  public void testParseSupported() throws Exception {
    for (TestHgVersion test : commonTests) {
      HgVersion version = HgVersion.parseVersion(test.output);
      assertEqualVersions(version, test);
      assertTrue(version.isSupported());
    }
  }

  public void testParseUnsupported() throws Exception {
    TestHgVersion unsupportedVersion = new TestHgVersion("Mercurial Distributed SCM (version 1.5.1)", 1, 5, 1);
    HgVersion parsedVersion = HgVersion.parseVersion(unsupportedVersion.output);
    assertEqualVersions(parsedVersion, unsupportedVersion);
    assertFalse(parsedVersion.isSupported());
  }

  private static void assertEqualVersions(HgVersion actual, TestHgVersion expected) throws Exception {
    Field field = HgVersion.class.getDeclaredField("myMajor");
    field.setAccessible(true);
    final int major = field.getInt(actual);
    field = HgVersion.class.getDeclaredField("myMiddle");
    field.setAccessible(true);
    final int middle = field.getInt(actual);
    field = HgVersion.class.getDeclaredField("myMinor");
    field.setAccessible(true);
    final int minor = field.getInt(actual);

    assertEquals(major, expected.major);
    assertEquals(middle, expected.middle);
    assertEquals(minor, expected.minor);
    HgVersion versionFromTest = new HgVersion(expected.major, expected.middle, expected.minor);
    assertEquals(versionFromTest, actual); //test equals meth
  }

  private static class TestHgVersion {
    private final String output;
    private final int major;
    private final int middle;
    private final int minor;

    public TestHgVersion(String output, int major, int middle, int minor) {
      this.output = output;
      this.major = major;
      this.middle = middle;
      this.minor = minor;
    }
  }
}

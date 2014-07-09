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
package com.intellij.idea;

import com.intellij.ide.Bootstrap;
import com.intellij.openapi.application.PathManager;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.util.io.FileUtilRt;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.ExceptionUtil;
import com.intellij.util.Restarter;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@SuppressWarnings({"UseOfSystemOutOrSystemErr", "MethodNamesDifferingOnlyByCase"})
public class Main {
  public static final int UPDATE_FAILED = 1;
  public static final int STARTUP_EXCEPTION = 2;
  public static final int STARTUP_IMPOSSIBLE = 3;
  public static final int LICENSE_ERROR = 4;
  public static final int PLUGIN_ERROR = 5;

  private static final String AWT_HEADLESS = "java.awt.headless";

  private static boolean isHeadless;
  private static boolean isCommandLine;

  private Main() { }

  public static void main(final String[] args) {
    setFlags(args);

    if (isHeadless()) {
      System.setProperty(AWT_HEADLESS, Boolean.TRUE.toString());
    }
    else {
      if (GraphicsEnvironment.isHeadless()) {
        throw new HeadlessException("Unable to detect graphics environment");
      }

      if (args.length == 0) {
        try {
          installPatch();
        }
        catch (Throwable t) {
          showMessage("Update Failed", t);
          System.exit(UPDATE_FAILED);
        }
      }
    }

    try {
      Bootstrap.main(args, Main.class.getName() + "Impl", "start");
    }
    catch (Throwable t) {
      showMessage("Start Failed", t);
      System.exit(STARTUP_EXCEPTION);
    }
  }

  public static boolean isHeadless() {
    return isHeadless;
  }

  public static boolean isCommandLine() {
    return isCommandLine;
  }

  public static void setFlags(String[] args) {
    isHeadless = isHeadless(args);
    isCommandLine = isCommandLine(args);
  }

  private static boolean isHeadless(String[] args) {
    if (Boolean.valueOf(System.getProperty(AWT_HEADLESS))) {
      return true;
    }

    if (args.length == 0) {
      return false;
    }

    String firstArg = args[0];
    return Comparing.strEqual(firstArg, "ant") ||
           Comparing.strEqual(firstArg, "duplocate") ||
           Comparing.strEqual(firstArg, "traverseUI") ||
           (firstArg.length() < 20 && firstArg.endsWith("inspect"));
  }

  private static boolean isCommandLine(String[] args) {
    if (isHeadless()) return true;
    return args.length > 0 && Comparing.strEqual(args[0], "diff");
  }

  public static boolean isUITraverser(final String[] args) {
    return args.length > 0 && Comparing.strEqual(args[0], "traverseUI");
  }

  private static void installPatch() throws IOException {
    String platform = System.getProperty("idea.platform.prefix", "idea");
    String patchFileName = ("jetbrains.patch.jar." + platform).toLowerCase();
    File originalPatchFile = new File(System.getProperty("java.io.tmpdir"), patchFileName);
    File copyPatchFile = new File(System.getProperty("java.io.tmpdir"), patchFileName + "_copy");

    // always delete previous patch copy
    if (!FileUtilRt.delete(copyPatchFile)) {
      throw new IOException("Cannot create temporary patch file");
    }

    if (!originalPatchFile.exists()) {
      return;
    }

    if (!originalPatchFile.renameTo(copyPatchFile) || !FileUtilRt.delete(originalPatchFile)) {
      throw new IOException("Cannot create temporary patch file");
    }

    int status = 0;
    if (Restarter.isSupported()) {
      List<String> args = new ArrayList<String>();

      if (SystemInfo.isWindows) {
        File launcher = new File(PathManager.getBinPath(), "VistaLauncher.exe");
        args.add(Restarter.createTempExecutable(launcher).getPath());
      }

      Collections.addAll(args,
                         System.getProperty("java.home") + "/bin/java",
                         "-Xmx500m",
                         "-classpath",
                         copyPatchFile.getPath(),
                         "com.intellij.updater.Runner",
                         "install",
                         PathManager.getHomePath());

      status = Restarter.scheduleRestart(ArrayUtilRt.toStringArray(args));
    }
    else {
      String message = "Patch update is not supported - please do it manually";
      showMessage("Update Error", message, true);
    }

    System.exit(status);
  }

  public static void showMessage(String title, Throwable t) {
    String message = "Internal error. Please report to http://youtrack.jetbrains.com\n\n" + ExceptionUtil.getThrowableText(t);
    showMessage(title, message, true);
  }

  public static void showMessage(String title, String message, boolean error) {
    if (isCommandLine()) {
      PrintStream stream = error ? System.err : System.out;
      stream.println("\n" + title + ": " + message);
    }
    else {
      try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); }
      catch (Throwable ignore) { }

      JTextPane textPane = new JTextPane();
      textPane.setEditable(false);
      textPane.setText(message.replaceAll("\t", "    "));
      textPane.setBackground(UIManager.getColor("Panel.background"));

      int type = error ? JOptionPane.ERROR_MESSAGE : JOptionPane.INFORMATION_MESSAGE;
      JOptionPane.showMessageDialog(JOptionPane.getRootFrame(), textPane, title, type);
    }
  }
}

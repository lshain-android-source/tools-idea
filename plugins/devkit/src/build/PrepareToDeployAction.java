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
package org.jetbrains.idea.devkit.build;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileContext;
import com.intellij.openapi.compiler.CompileStatusNotification;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.compiler.make.ManifestBuilder;
import com.intellij.openapi.fileTypes.FileTypeManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.OrderRootType;
import com.intellij.openapi.roots.libraries.Library;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.JarFileSystem;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.io.ZipUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.idea.devkit.DevKitBundle;
import org.jetbrains.idea.devkit.module.PluginModuleType;

import java.io.*;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.*;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * User: anna
 * Date: May 5, 2005
 */
public class PrepareToDeployAction extends AnAction {
  @NonNls private static final String ZIP_EXTENSION = ".zip";
  @NonNls private static final String JAR_EXTENSION = ".jar";
  @NonNls private static final String TEMP_PREFIX = "temp";
  @NonNls private static final String MIDDLE_LIB_DIR = "lib";

  private final FileTypeManager myFileTypeManager = FileTypeManager.getInstance();

  public void actionPerformed(final AnActionEvent e) {
    final Module module = LangDataKeys.MODULE.getData(e.getDataContext());
    if (module != null && ModuleType.get(module) instanceof PluginModuleType) {
      doPrepare(Arrays.asList(module), PlatformDataKeys.PROJECT.getData(e.getDataContext()));
    }
  }

  public void doPrepare(final List<Module> pluginModules, final Project project) {
    final List<String> errorMessages = new ArrayList<String>();
    final List<String> successMessages = new ArrayList<String>();

    final CompilerManager compilerManager = CompilerManager.getInstance(project);
    compilerManager.make(compilerManager.createModulesCompileScope(pluginModules.toArray(new Module[pluginModules.size()]), true),
                         new CompileStatusNotification() {
                           public void finished(final boolean aborted,
                                                final int errors,
                                                final int warnings,
                                                final CompileContext compileContext) {
                             if (aborted || errors != 0) return;
                             ApplicationManager.getApplication().invokeLater(new Runnable() {
                               public void run() {
                                 for (Module aModule : pluginModules) {
                                   if (!doPrepare(aModule, errorMessages, successMessages)) {
                                     return;
                                   }
                                 }

                                 if (!errorMessages.isEmpty()) {
                                   Messages.showErrorDialog(errorMessages.iterator().next(), DevKitBundle.message("error.occurred"));
                                 }
                                 else if (!successMessages.isEmpty()) {
                                   StringBuilder messageBuf = new StringBuilder();
                                   for (String message : successMessages) {
                                     if (messageBuf.length() != 0) {
                                       messageBuf.append('\n');
                                     }
                                     messageBuf.append(message);
                                   }
                                   Messages.showInfoMessage(messageBuf.toString(),
                                                            pluginModules.size() == 1
                                                            ? DevKitBundle.message("success.deployment.message", pluginModules.get(0).getName())
                                                            : DevKitBundle.message("success.deployment.message.all"));
                                 }
                               }
                             });
                           }
                         });
  }

  private boolean doPrepare(final Module module, final List<String> errorMessages, final List<String> successMessages) {
    final String pluginName = module.getName();
    final String defaultPath = new File(module.getModuleFilePath()).getParent() + File.separator + pluginName;
    final HashSet<Module> modules = new HashSet<Module>();
    PluginBuildUtil.getDependencies(module, modules);
    modules.add(module);
    final Set<Library> libs = new HashSet<Library>();
    for (Module module1 : modules) {
      PluginBuildUtil.getLibraries(module1, libs);
    }
    final boolean isZip = libs.size() != 0;
    final String oldPath = defaultPath + (isZip ? JAR_EXTENSION : ZIP_EXTENSION);
    final File oldFile = new File(oldPath);
    if (oldFile.exists()) {
      if (Messages
        .showYesNoDialog(module.getProject(), DevKitBundle.message("suggest.to.delete", oldPath), DevKitBundle.message("info.message"),
                         Messages.getInformationIcon()) == DialogWrapper.OK_EXIT_CODE) {
        FileUtil.delete(oldFile);
      }
    }

    final String dstPath = defaultPath + (isZip ? ZIP_EXTENSION : JAR_EXTENSION);
    final File dstFile = new File(dstPath);
    return clearReadOnly(module.getProject(), dstFile) && ProgressManager.getInstance().runProcessWithProgressSynchronously(new Runnable() {
      public void run() {

        final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
        if (progressIndicator != null) {
          progressIndicator.setText(DevKitBundle.message("prepare.for.deployment.common"));
          progressIndicator.setIndeterminate(true);
        }
        try {
          File jarFile = preparePluginsJar(module, modules);
          if (isZip) {
            processLibraries(jarFile, dstFile, pluginName, libs, progressIndicator);
          }
          else {
            FileUtil.copy(jarFile, dstFile);
          }
          successMessages.add(DevKitBundle.message("saved.message", isZip ? 1 : 2, pluginName, dstPath));
        }
        catch (final IOException e) {
          errorMessages.add(e.getMessage() + "\n(" + dstPath + ")");
        }
      }
    }, DevKitBundle.message("prepare.for.deployment", pluginName), true, module.getProject());

  }

  private static boolean clearReadOnly(final Project project, final File dstFile) {
    //noinspection EmptyCatchBlock
    final URL url;
    try {
      url = dstFile.toURL();
    }
    catch (MalformedURLException e) {
      return true;
    }
    final VirtualFile vfile = VfsUtil.findFileByURL(url);
    return vfile == null || !ReadonlyStatusHandler.getInstance(project).ensureFilesWritable(vfile).hasReadonlyFiles();
  }

  private static FileFilter createFilter(final ProgressIndicator progressIndicator, @Nullable final FileTypeManager fileTypeManager) {
    return new FileFilter() {
      public boolean accept(File pathName) {
        if (progressIndicator != null) {
          progressIndicator.setText2("");
        }
        return fileTypeManager == null || !fileTypeManager.isFileIgnored(FileUtil.toSystemIndependentName(pathName.getName()));
      }
    };
  }

  private void processLibraries(final File jarFile,
                                final File zipFile,
                                final String pluginName,
                                final Set<Library> libs,
                                final ProgressIndicator progressIndicator) throws IOException {
    if (FileUtil.ensureCanCreateFile(zipFile)) {
      ZipOutputStream zos = null;
      try {
        zos = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(zipFile)));
        addStructure(pluginName, zos);
        addStructure(pluginName + "/" + MIDDLE_LIB_DIR, zos);
        final String entryName = pluginName + JAR_EXTENSION;
        ZipUtil.addFileToZip(zos, jarFile, getZipPath(pluginName, entryName), new HashSet<String>(),
                             createFilter(progressIndicator, myFileTypeManager));
        Set<String> usedJarNames = new HashSet<String>();
        usedJarNames.add(entryName);
        Set<VirtualFile> jarredVirtualFiles = new HashSet<VirtualFile>();
        for (Library library : libs) {
          final VirtualFile[] files = library.getFiles(OrderRootType.CLASSES);
          for (VirtualFile virtualFile : files) {
            if (jarredVirtualFiles.add(virtualFile)) {
              if (virtualFile.getFileSystem() instanceof JarFileSystem) {
                addLibraryJar(virtualFile, zipFile, pluginName, zos, usedJarNames, progressIndicator);
              }
              else {
                makeAndAddLibraryJar(virtualFile, zipFile, pluginName, zos, usedJarNames, progressIndicator, library.getName());
              }
            }
          }
        }
      }
      finally {
        if (zos != null) zos.close();
      }
    }
  }

  private static String getZipPath(final String pluginName, final String entryName) {
    return "/" + pluginName + "/" + MIDDLE_LIB_DIR + "/" + entryName;
  }

  private void makeAndAddLibraryJar(final VirtualFile virtualFile,
                                    final File zipFile,
                                    final String pluginName,
                                    final ZipOutputStream zos,
                                    final Set<String> usedJarNames,
                                    final ProgressIndicator progressIndicator,
                                    final String preferredName) throws IOException {
    File libraryJar = FileUtil.createTempFile(TEMP_PREFIX, JAR_EXTENSION);
    libraryJar.deleteOnExit();
    ZipOutputStream jar = null;
    try {
      jar = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(libraryJar)));
      ZipUtil.addFileOrDirRecursively(jar, libraryJar, VfsUtil.virtualToIoFile(virtualFile), "",
                                      createFilter(progressIndicator, myFileTypeManager), null);
    }
    finally {
      if (jar != null) jar.close();
    }
    final String jarName =
      getLibraryJarName(virtualFile.getName() + JAR_EXTENSION, usedJarNames, preferredName == null ? null : preferredName + JAR_EXTENSION);
    ZipUtil.addFileOrDirRecursively(zos, zipFile, libraryJar, getZipPath(pluginName, jarName), createFilter(progressIndicator, null), null);
  }

  private static String getLibraryJarName(final String fileName, Set<String> usedJarNames, @Nullable final String preferredName) {
    String uniqueName;
    if (preferredName != null && !usedJarNames.contains(preferredName)) {
      uniqueName = preferredName;
    }
    else {
      uniqueName = fileName;
      if (usedJarNames.contains(uniqueName)) {
        final int dotPos = uniqueName.lastIndexOf(".");
        final String name = dotPos < 0 ? uniqueName : uniqueName.substring(0, dotPos);
        final String ext = dotPos < 0 ? "" : uniqueName.substring(dotPos);

        int i = 0;
        do {
          i++;
          uniqueName = name + i + ext;
        }
        while (usedJarNames.contains(uniqueName));
      }
    }
    usedJarNames.add(uniqueName);
    return uniqueName;
  }

  private static void addLibraryJar(final VirtualFile virtualFile,
                                    final File zipFile,
                                    final String pluginName,
                                    final ZipOutputStream zos,
                                    final Set<String> usedJarNames,
                                    final ProgressIndicator progressIndicator) throws IOException {
    File ioFile = VfsUtil.virtualToIoFile(virtualFile);
    final String jarName = getLibraryJarName(ioFile.getName(), usedJarNames, null);
    ZipUtil.addFileOrDirRecursively(zos, zipFile, ioFile, getZipPath(pluginName, jarName), createFilter(progressIndicator, null), null);
  }

  private static void addStructure(@NonNls final String relativePath, final ZipOutputStream zos) throws IOException {
    ZipEntry e = new ZipEntry(relativePath + "/");
    e.setMethod(ZipEntry.STORED);
    e.setSize(0);
    e.setCrc(0);
    zos.putNextEntry(e);
    zos.closeEntry();
  }

  private File preparePluginsJar(Module module, final HashSet<Module> modules) throws IOException {
    final PluginBuildConfiguration pluginModuleBuildProperties = PluginBuildConfiguration.getInstance(module);
    File jarFile = FileUtil.createTempFile(TEMP_PREFIX, JAR_EXTENSION);
    jarFile.deleteOnExit();
    final Manifest manifest = createOrFindManifest(pluginModuleBuildProperties);
    ZipOutputStream jarPlugin = null;
    try {
      jarPlugin = new JarOutputStream(new BufferedOutputStream(new FileOutputStream(jarFile)), manifest);
      final ProgressIndicator progressIndicator = ProgressManager.getInstance().getProgressIndicator();
      final HashSet<String> writtenItemRelativePaths = new HashSet<String>();
      for (Module module1 : modules) {
        final VirtualFile compilerOutputPath = CompilerModuleExtension.getInstance(module1).getCompilerOutputPath();
        if (compilerOutputPath == null) continue; //pre-condition: output dirs for all modules are up-to-date
        ZipUtil.addDirToZipRecursively(jarPlugin, jarFile, new File(compilerOutputPath.getPath()), "",
                                       createFilter(progressIndicator, myFileTypeManager), writtenItemRelativePaths);
      }
      final String pluginXmlPath = pluginModuleBuildProperties.getPluginXmlPath();
      @NonNls final String metaInf = "/META-INF/plugin.xml";
      ZipUtil.addFileToZip(jarPlugin, new File(pluginXmlPath), metaInf, writtenItemRelativePaths, createFilter(progressIndicator, null));
    }
    finally {
      if (jarPlugin != null) jarPlugin.close();
    }
    return jarFile;
  }

  public static Manifest createOrFindManifest(final PluginBuildConfiguration pluginModuleBuildProperties) throws IOException {
    final Manifest manifest = new Manifest();
    final VirtualFile vManifest = pluginModuleBuildProperties.getManifest();
    if (pluginModuleBuildProperties.isUseUserManifest() && vManifest != null) {
      InputStream in = null;
      try {
        in = new BufferedInputStream(vManifest.getInputStream());
        manifest.read(in);
      }
      finally {
        if (in != null) in.close();
      }
    }
    else {
      Attributes mainAttributes = manifest.getMainAttributes();
      ManifestBuilder.setGlobalAttributes(mainAttributes);
    }
    return manifest;
  }

  public void update(AnActionEvent e) {
    final Module module = LangDataKeys.MODULE.getData(e.getDataContext());
    boolean enabled = module != null && ModuleType.get(module) instanceof PluginModuleType;
    e.getPresentation().setVisible(enabled);
    e.getPresentation().setEnabled(enabled);
    if (enabled) {
      e.getPresentation().setText(DevKitBundle.message("prepare.for.deployment", module.getName()));
    }
  }
}

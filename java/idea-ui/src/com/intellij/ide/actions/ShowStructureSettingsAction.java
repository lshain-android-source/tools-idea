/*
 * Copyright 2000-2009 JetBrains s.r.o.
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
package com.intellij.ide.actions;

import com.intellij.facet.Facet;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.options.newEditor.OptionsEditorDialog;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.roots.ui.configuration.ProjectStructureConfigurable;
import com.intellij.openapi.ui.Messages;

public class ShowStructureSettingsAction extends AnAction implements DumbAware {
  public void actionPerformed(AnActionEvent e) {
    Project project = PlatformDataKeys.PROJECT.getData(e.getDataContext());
    if (project == null) {
      project = ProjectManager.getInstance().getDefaultProject();
    }

    // TEMPORARY HACK! DO NOT MERGE INTO INTELLIJ. This just works around a lot
    // of confusion caused by the fact that the structure dialog lets you edit
    // project state which is ignored by gradle, so temporarily disable this
    // dialog for Android-Gradle-based projects.
    if (isGradleProject(project)) {
      showDisabledProjectStructureDialogMessage();
    }

    ShowSettingsUtil.getInstance().editConfigurable(project, OptionsEditorDialog.DIMENSION_KEY, ProjectStructureConfigurable.getInstance(project));
  }

  public static void showDisabledProjectStructureDialogMessage() {
    Messages.showInfoMessage(
      "We will provide a UI to configure project settings later. " +
      "Until then, please manually edit your build.gradle file to " +
      "configure source folders, libraries and dependencies.\n\n" +
      "NOTE THAT EDITS MADE IN THE FOLLOWING DIALOG DO NOT AFFECT THE GRADLE BUILD.\n" +
      "The dialog can be used for temporary adjustments to SDKs etc.",
      "Project Structure");
  }

  public static boolean isGradleProject(Project project) {
    ModuleManager moduleManager = ModuleManager.getInstance(project);
    for (Module module : moduleManager.getModules()) {
      FacetManager facetManager = FacetManager.getInstance(module);
      for (Facet facet : facetManager.getAllFacets()) {
        if ("android-gradle".equals(facet.getType().getStringId())) {
          return true;
        }
      }
    }
    return false;
  }
}
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
package com.intellij.execution;

import com.intellij.execution.configurations.*;
import com.intellij.execution.executors.DefaultRunExecutor;
import com.intellij.execution.filters.Filter;
import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.execution.util.ExecutionErrorDialog;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.HashSet;
import java.util.Set;

/**
 * @author spleaner
 */
public class JavaExecutionUtil {
  private JavaExecutionUtil() {
  }

  public static boolean executeRun(@NotNull final Project project, String contentName, Icon icon,
                      final DataContext dataContext) throws ExecutionException {
    return executeRun(project, contentName, icon, dataContext, null);
  }

  public static boolean executeRun(@NotNull final Project project, String contentName, Icon icon, DataContext dataContext, Filter[] filters) throws ExecutionException {
    final JavaParameters cmdLine = JavaParameters.JAVA_PARAMETERS.getData(dataContext);
    final DefaultRunProfile profile = new DefaultRunProfile(project, cmdLine, contentName, icon, filters);
    final ProgramRunner runner = RunnerRegistry.getInstance().getRunner(DefaultRunExecutor.EXECUTOR_ID, profile);
    if (runner != null) {
      Executor executor = DefaultRunExecutor.getRunExecutorInstance();
      runner.execute(new ExecutionEnvironment(profile, executor, project, null));
      return true;
    }

    return false;
  }

  public static Module findModule(final Module contextModule, final Set<String> patterns, final Project project, Condition<PsiClass> isTestMethod) {
    final Set<Module> modules = new HashSet<Module>();
    for (String className : patterns) {
      final PsiClass psiClass = findMainClass(project,
                                              className.contains(",") ? className.substring(0, className.indexOf(',')) : className,
                                              GlobalSearchScope.allScope(project));
      if (psiClass != null && isTestMethod.value(psiClass)) {
        modules.add(ModuleUtilCore.findModuleForPsiElement(psiClass));
      }
    }

    if (modules.size() == 1) {
      final Module nextModule = modules.iterator().next();
      if (nextModule != null) {
        return nextModule;
      }
    }
    if (contextModule != null && modules.size() > 1) {
      final HashSet<Module> moduleDependencies = new HashSet<Module>();
      ModuleUtilCore.getDependencies(contextModule, moduleDependencies);
      if (moduleDependencies.containsAll(modules)) {
        return contextModule;
      }
    }
    return null;
  }

  private static final class DefaultRunProfile implements RunProfile {
    private final JavaParameters myParameters;
    private final String myContentName;
    private final Filter[] myFilters;
    private final Project myProject;
    private final Icon myIcon;

    public DefaultRunProfile(final Project project, final JavaParameters parameters, final String contentName, final Icon icon, Filter[] filters) {
      myProject = project;
      myParameters = parameters;
      myContentName = contentName;
      myFilters = filters;
      myIcon = icon;
    }

    public Icon getIcon() {
      return myIcon;
    }

    public RunProfileState getState(@NotNull final Executor executor, @NotNull final ExecutionEnvironment env) throws ExecutionException {
      final JavaCommandLineState state = new JavaCommandLineState(env) {
        protected JavaParameters createJavaParameters() {
          return myParameters;
        }
      };
      final TextConsoleBuilder builder = TextConsoleBuilderFactory.getInstance().createBuilder(myProject);
      if (myFilters != null) {
        for (final Filter myFilter : myFilters) {
          builder.addFilter(myFilter);
        }
      }
      state.setConsoleBuilder(builder);
      return state;
    }

    public String getName() {
      return myContentName;
    }
  }

  @Nullable
  public static String getRuntimeQualifiedName(@NotNull final PsiClass aClass) {
    return ClassUtil.getJVMClassName(aClass);
  }

  @Nullable
  public static String getPresentableClassName(final String rtClassName, final JavaRunConfigurationModule configurationModule) {
    if (StringUtil.isEmpty(rtClassName)) return null;
    final PsiClass psiClass = configurationModule.findClass(rtClassName);
    if (psiClass != null) {
      return psiClass.getName();
    }
    final int lastDot = rtClassName.lastIndexOf('.');
    if (lastDot == -1 || lastDot == rtClassName.length() - 1) {
      return rtClassName;
    }
    return rtClassName.substring(lastDot + 1, rtClassName.length());
  }

  public static Module findModule(@NotNull final PsiClass psiClass) {
    return ModuleUtil.findModuleForPsiElement(psiClass);
  }

  @Nullable
  public static PsiClass findMainClass(final Module module, final String mainClassName) {
    return findMainClass(module.getProject(), mainClassName, module.getModuleRuntimeScope(true));
  }

  @Nullable
  public static PsiClass findMainClass(final Project project, final String mainClassName, final GlobalSearchScope scope) {
    if (project.isDefault()) return null;
    final PsiManager psiManager = PsiManager.getInstance(project);
    final String shortName = StringUtil.getShortName(mainClassName);
    final String packageName = StringUtil.getPackageName(mainClassName);
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(psiManager.getProject());
    final PsiClass psiClass = psiFacade.findClass(StringUtil.getQualifiedName(packageName, shortName.replace('$', '.')), scope);
    return psiClass == null ? psiFacade.findClass(mainClassName, scope) : psiClass;
  }


  public static boolean isNewName(final String name) {
    return name == null || name.startsWith(ExecutionBundle.message("run.configuration.unnamed.name.prefix"));
  }

  public static Location stepIntoSingleClass(final Location location) {
    PsiElement element = location.getPsiElement();
    if (!(element instanceof PsiClassOwner)) {
      if (PsiTreeUtil.getParentOfType(element, PsiClass.class) != null) return location;
      element = PsiTreeUtil.getParentOfType(element, PsiClassOwner.class);
      if (element == null) return location;
    }
    final PsiClassOwner psiFile = (PsiClassOwner)element;
    final PsiClass[] classes = psiFile.getClasses();
    if (classes.length != 1) return location;
    return PsiLocation.fromPsiElement(classes[0]);
  }

  public static String getShortClassName(final String fqName) {
    if (fqName == null) return "";
    return StringUtil.getShortName(fqName);
  }

  public static void showExecutionErrorMessage(final ExecutionException e, final String title, final Project project) {
    ExecutionErrorDialog.show(e, title, project);
  }

  public static boolean isRunnableClass(final PsiClass aClass) {
    return PsiClassUtil.isRunnableClass(aClass, true);
  }
}

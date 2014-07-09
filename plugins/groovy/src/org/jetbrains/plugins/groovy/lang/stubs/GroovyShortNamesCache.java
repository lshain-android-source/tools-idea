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

package org.jetbrains.plugins.groovy.lang.stubs;

import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.PsiShortNamesCache;
import com.intellij.psi.stubs.StubIndex;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ObjectUtils;
import com.intellij.util.Processor;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.containers.HashSet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.lang.psi.GroovyFile;
import org.jetbrains.plugins.groovy.lang.psi.impl.search.GrSourceFilterScope;
import org.jetbrains.plugins.groovy.lang.psi.stubs.index.*;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author ilyas
 */
public class GroovyShortNamesCache extends PsiShortNamesCache {
  private final Project myProject;

  public GroovyShortNamesCache(Project project) {
    myProject = project;
  }

  public static GroovyShortNamesCache getGroovyShortNamesCache(Project project) {
    return ObjectUtils.assertNotNull(ContainerUtil.findInstance(project.getExtensions(PsiShortNamesCache.EP_NAME), GroovyShortNamesCache.class));
  }

  @Override
  @NotNull
  public PsiClass[] getClassesByName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope) {
    Collection<PsiClass> allClasses = getAllScriptClasses(name, scope);
    if (allClasses.isEmpty()) return PsiClass.EMPTY_ARRAY;
    return allClasses.toArray(new PsiClass[allClasses.size()]);
  }

  public List<PsiClass> getScriptClassesByFQName(final String name, final GlobalSearchScope scope, final boolean srcOnly) {
    GlobalSearchScope actualScope = srcOnly ? new GrSourceFilterScope(scope) : scope;
    final Collection<GroovyFile> files = StubIndex.getInstance().get(GrFullScriptNameIndex.KEY, name.hashCode(), myProject, actualScope);
    if (files.isEmpty()) {
      return Collections.emptyList();
    }

    final ArrayList<PsiClass> result = new ArrayList<PsiClass>();
    for (GroovyFile file : files) {
      if (file.isScript()) {
        final PsiClass scriptClass = file.getScriptClass();
        if (scriptClass != null && name.equals(scriptClass.getQualifiedName())) {
          result.add(scriptClass);
        }
      }
    }
    return result;
  }

  @NotNull
  public List<PsiClass> getClassesByFQName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope) {
    final List<PsiClass> result = addClasses(name, scope, true);
    if (result.isEmpty()) {
      result.addAll(addClasses(name, scope, false));
    }
    if (result.isEmpty()) {
      result.addAll(addClasses(name, GlobalSearchScope.projectScope(myProject), false));
    }
    return result;
  }

  private List<PsiClass> addClasses(String name, GlobalSearchScope scope, boolean inSource) {
    final List<PsiClass> result = new ArrayList<PsiClass>(getScriptClassesByFQName(name, scope, inSource));

    for (PsiElement psiClass : StubIndex.getInstance().safeGet(GrFullClassNameIndex.KEY, name.hashCode(), myProject,
                                                                inSource ? new GrSourceFilterScope(scope) : scope, PsiClass.class)) {
      //hashcode doesn't guarantee equals
      if (name.equals(((PsiClass)psiClass).getQualifiedName())) {
        result.add((PsiClass)psiClass);
      }
    }
    return result;
  }

  private Collection<PsiClass> getAllScriptClasses(String shortName, GlobalSearchScope scope) {
    final ArrayList<PsiClass> result = ContainerUtil.newArrayList();
    for (GroovyFile file : StubIndex.getInstance().get(GrScriptClassNameIndex.KEY, shortName, myProject, new GrSourceFilterScope(scope))) {
      ContainerUtil.addIfNotNull(file.getScriptClass(), result);
    }
    return result;
  }

  @Override
  @NotNull
  public String[] getAllClassNames() {
    return ArrayUtil.toStringArray(StubIndex.getInstance().getAllKeys(GrScriptClassNameIndex.KEY, myProject));
  }


  @Override
  public void getAllClassNames(@NotNull HashSet<String> dest) {
    dest.addAll(StubIndex.getInstance().getAllKeys(GrScriptClassNameIndex.KEY, myProject));
  }

  @Override
  @NotNull
  public PsiMethod[] getMethodsByName(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope) {
    final Collection<? extends PsiMethod> methods = StubIndex.getInstance().get(GrMethodNameIndex.KEY, name, myProject, new GrSourceFilterScope(scope));
    final Collection<? extends PsiMethod> annMethods = StubIndex.getInstance().get(GrAnnotationMethodNameIndex.KEY, name, myProject, new GrSourceFilterScope(scope));
    if (methods.isEmpty() && annMethods.isEmpty()) return PsiMethod.EMPTY_ARRAY;
    return ArrayUtil.mergeCollections(annMethods, methods, PsiMethod.ARRAY_FACTORY);
  }

  @Override
  public boolean processMethodsWithName(@NonNls @NotNull String name,
                                        @NotNull GlobalSearchScope scope,
                                        @NotNull Processor<PsiMethod> processor) {
    GrSourceFilterScope filterScope = new GrSourceFilterScope(scope);
    return StubIndex.getInstance().process(GrMethodNameIndex.KEY, name, myProject, filterScope, processor) &&
           StubIndex.getInstance().process(GrAnnotationMethodNameIndex.KEY, name, myProject, filterScope, processor);
  }

  @Override
  @NotNull
  public PsiMethod[] getMethodsByNameIfNotMoreThan(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
    return getMethodsByName(name, scope);
  }

  @NotNull
  @Override
  public PsiField[] getFieldsByNameIfNotMoreThan(@NonNls @NotNull String name, @NotNull GlobalSearchScope scope, int maxCount) {
    return getFieldsByName(name, scope);
  }

  @Override
  @NotNull
  public String[] getAllMethodNames() {
    Collection<String> keys = StubIndex.getInstance().getAllKeys(GrMethodNameIndex.KEY, myProject);
    keys.addAll(StubIndex.getInstance().getAllKeys(GrAnnotationMethodNameIndex.KEY, myProject));
    return ArrayUtil.toStringArray(keys);
  }

  @Override
  public void getAllMethodNames(@NotNull HashSet<String> set) {
    set.addAll(StubIndex.getInstance().getAllKeys(GrMethodNameIndex.KEY, myProject));
  }

  @Override
  @NotNull
  public PsiField[] getFieldsByName(@NotNull @NonNls String name, @NotNull GlobalSearchScope scope) {
    final Collection<? extends PsiField> fields = StubIndex.getInstance().get(GrFieldNameIndex.KEY, name, myProject, new GrSourceFilterScope(scope));
    if (fields.isEmpty()) return PsiField.EMPTY_ARRAY;
    return fields.toArray(new PsiField[fields.size()]);
  }

  @Override
  @NotNull
  public String[] getAllFieldNames() {
    Collection<String> fields = StubIndex.getInstance().getAllKeys(GrFieldNameIndex.KEY, myProject);
    return ArrayUtil.toStringArray(fields);
  }

  @Override
  public void getAllFieldNames(@NotNull HashSet<String> set) {
    set.addAll(StubIndex.getInstance().getAllKeys(GrFieldNameIndex.KEY, myProject));
  }

}

/*
 * Copyright 2003-2009 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ipp.junit;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.InheritanceUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.siyeh.ipp.base.PsiElementPredicate;
import org.jetbrains.annotations.NonNls;

class CreateAssertPredicate implements PsiElementPredicate {

  public boolean satisfiedBy(PsiElement element) {
    if (!(element instanceof PsiExpressionStatement)) {
      return false;
    }
    final PsiExpressionStatement statement =
      (PsiExpressionStatement)element;
    final PsiExpression expression = statement.getExpression();
    final PsiElement parent = expression.getParent();
    if (!(parent instanceof PsiExpressionStatement)) {
      return false;
    }
    final PsiType type = expression.getType();
    if (!PsiType.BOOLEAN.equals(type)) {
      return false;
    }
    final PsiMethod containingMethod =
      PsiTreeUtil.getParentOfType(expression, PsiMethod.class);
    return isTestMethod(containingMethod);
  }

  private static boolean isTestMethod(PsiMethod method) {
    if (method == null) {
      return false;
    }
    if (AnnotationUtil.isAnnotated(method, "org.junit.Test", true)) {
      return true;
    }
    if (method.hasModifierProperty(PsiModifier.ABSTRACT) ||
        !method.hasModifierProperty(PsiModifier.PUBLIC)) {
      return false;
    }
    final PsiType returnType = method.getReturnType();
    if (returnType == null) {
      return false;
    }
    if (!returnType.equals(PsiType.VOID)) {
      return false;
    }
    final PsiParameterList parameterList = method.getParameterList();
    final PsiParameter[] parameters = parameterList.getParameters();
    if (parameters.length != 0) {
      return false;
    }
    @NonNls final String methodName = method.getName();
    if (!methodName.startsWith("test")) {
      return false;
    }
    final PsiClass containingClass = method.getContainingClass();
    return isTestClass(containingClass);
  }

  private static boolean isTestClass(PsiClass aClass) {
    if (aClass == null) {
      return false;
    }
    final Project project = aClass.getProject();
    final GlobalSearchScope scope = GlobalSearchScope.allScope(project);
    final JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    final PsiClass ancestorClass = psiFacade.findClass("junit.framework.TestCase", scope);
    return InheritanceUtil.isInheritorOrSelf(aClass, ancestorClass, true);
  }
}

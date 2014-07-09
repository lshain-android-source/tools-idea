/*
 * Copyright 2003-2013 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ig.performance;

import com.intellij.codeInspection.InspectionManager;
import com.intellij.codeInspection.ui.MultipleCheckboxOptionsPanel;
import com.intellij.openapi.util.Condition;
import com.intellij.psi.*;
import com.intellij.psi.search.searches.ClassInheritorsSearch;
import com.intellij.util.Processor;
import com.intellij.util.Query;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import com.siyeh.ig.fixes.ChangeModifierFix;
import com.siyeh.ig.psiutils.ClassUtils;
import com.siyeh.ig.psiutils.MethodUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.concurrent.atomic.AtomicInteger;

public class MethodMayBeStaticInspection extends BaseInspection {

  /**
   * @noinspection PublicField
   */
  public boolean m_onlyPrivateOrFinal = false;
  /**
   * @noinspection PublicField
   */
  public boolean m_ignoreEmptyMethods = true;

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("method.may.be.static.display.name");
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("method.may.be.static.problem.descriptor");
  }

  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new ChangeModifierFix(PsiModifier.STATIC);
  }

  @Override
  public JComponent createOptionsPanel() {
    final MultipleCheckboxOptionsPanel optionsPanel = new MultipleCheckboxOptionsPanel(this);
    optionsPanel.addCheckbox(InspectionGadgetsBundle.message("method.may.be.static.only.option"), "m_onlyPrivateOrFinal");
    optionsPanel.addCheckbox(InspectionGadgetsBundle.message("method.may.be.static.empty.option"), "m_ignoreEmptyMethods");
    return optionsPanel;
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new MethodCanBeStaticVisitor();
  }

  private class MethodCanBeStaticVisitor extends BaseInspectionVisitor {

    @Override
    public void visitMethod(@NotNull PsiMethod method) {
      super.visitMethod(method);
      if (method.hasModifierProperty(PsiModifier.STATIC) ||
          method.hasModifierProperty(PsiModifier.ABSTRACT) ||
          method.hasModifierProperty(PsiModifier.SYNCHRONIZED) ||
          method.hasModifierProperty(PsiModifier.NATIVE)) {
        return;
      }
      if (method.isConstructor() || method.getNameIdentifier() == null) {
        return;
      }
      if (m_ignoreEmptyMethods && MethodUtils.isEmpty(method)) {
        return;
      }
      final PsiClass containingClass = ClassUtils.getContainingClass(method);
      if (containingClass == null) {
        return;
      }
      final Condition<PsiElement>[] addins = InspectionManager.CANT_BE_STATIC_EXTENSION.getExtensions();
      for (Condition<PsiElement> addin : addins) {
        if (addin.value(method)) {
          return;
        }
      }
      final PsiElement scope = containingClass.getScope();
      if (!(scope instanceof PsiJavaFile) && !containingClass.hasModifierProperty(PsiModifier.STATIC)) {
        return;
      }
      if (m_onlyPrivateOrFinal && !method.hasModifierProperty(PsiModifier.FINAL) && !method.hasModifierProperty(PsiModifier.PRIVATE)) {
        return;
      }
      if (isExcluded(method) || MethodUtils.hasSuper(method) || MethodUtils.isOverridden(method)) {
        return;
      }
      if (implementsSurprisingInterface(method)) {
        return;
      }
      final MethodReferenceVisitor visitor = new MethodReferenceVisitor(method);
      method.accept(visitor);
      if (!visitor.areReferencesStaticallyAccessible()) {
        return;
      }
      registerMethodError(method);
    }

    private boolean implementsSurprisingInterface(final PsiMethod method) {
      final PsiClass containingClass = method.getContainingClass();
      if (containingClass == null) {
        return false;
      }
      final Query<PsiClass> search = ClassInheritorsSearch.search(containingClass, method.getUseScope(), true, true, false);
      final boolean[] result = new boolean[1];
      search.forEach(new Processor<PsiClass>() {
        AtomicInteger count = new AtomicInteger(0);

        @Override
        public boolean process(PsiClass subClass) {
          if (count.incrementAndGet() > 5) {
            result[0] = true;
            return false;
          }
          final PsiReferenceList list = subClass.getImplementsList();
          if (list == null) {
            return true;
          }
          final PsiJavaCodeReferenceElement[] referenceElements = list.getReferenceElements();
          for (PsiJavaCodeReferenceElement referenceElement : referenceElements) {
            final PsiElement target = referenceElement.resolve();
            if (!(target instanceof PsiClass)) {
              result[0] = true;
              return false;
            }
            final PsiClass aClass = (PsiClass)target;
            if (!aClass.isInterface()) {
              result[0] = true;
              return false;
            }
            if (aClass.findMethodBySignature(method, true) != null) {
              result[0] = true;
              return false;
            }
          }
          return true;
        }
      });
      return result[0];
    }

    private boolean isExcluded(PsiMethod method) {
      @NonNls final String name = method.getName();
      if ("writeObject".equals(name)) {
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) {
          return false;
        }
        if (!MethodUtils.hasInThrows(method, "java.io.IOException")) {
          return false;
        }
        final PsiType returnType = method.getReturnType();
        if (!PsiType.VOID.equals(returnType)) {
          return false;
        }
        final PsiParameterList parameterList = method.getParameterList();
        if (parameterList.getParametersCount() != 1) {
          return false;
        }
        final PsiParameter parameter = parameterList.getParameters()[0];
        final PsiType type = parameter.getType();
        return type.equalsToText("java.io.ObjectOutputStream");
      }
      if ("readObject".equals(name)) {
        if (!method.hasModifierProperty(PsiModifier.PRIVATE)) {
          return false;
        }
        if (!MethodUtils.hasInThrows(method, "java.io.IOException", "java.lang.ClassNotFoundException")) {
          return false;
        }
        final PsiType returnType = method.getReturnType();
        if (!PsiType.VOID.equals(returnType)) {
          return false;
        }
        final PsiParameterList parameterList = method.getParameterList();
        if (parameterList.getParametersCount() != 1) {
          return false;
        }
        final PsiParameter parameter = parameterList.getParameters()[0];
        final PsiType type = parameter.getType();
        return type.equalsToText("java.io.ObjectInputStream");
      }
      if ("writeReplace".equals(name) || "readResolve".equals(name)) {
        if (!MethodUtils.hasInThrows(method, "java.io.ObjectStreamException")) {
          return false;
        }
        final PsiType returnType = method.getReturnType();
        if (returnType == null || !returnType.equalsToText(CommonClassNames.JAVA_LANG_OBJECT)) {
          return false;
        }
        final PsiParameterList parameterList = method.getParameterList();
        return parameterList.getParametersCount() == 0;
      }
      return false;
    }
  }
}

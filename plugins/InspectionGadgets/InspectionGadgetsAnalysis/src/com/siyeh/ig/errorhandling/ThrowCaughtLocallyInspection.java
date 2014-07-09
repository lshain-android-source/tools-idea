/*
 * Copyright 2003-2007 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ig.errorhandling;

import com.intellij.codeInspection.ui.SingleCheckboxOptionsPanel;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ClassUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;

public class ThrowCaughtLocallyInspection extends BaseInspection {

  /**
   * @noinspection PublicField
   */
  public boolean ignoreRethrownExceptions = false;

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message(
      "throw.caught.locally.display.name");
  }

  @Override
  @NotNull
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "throw.caught.locally.problem.descriptor");
  }

  @Override
  @Nullable
  public JComponent createOptionsPanel() {
    return new SingleCheckboxOptionsPanel(InspectionGadgetsBundle.message(
      "throw.caught.locally.ignore.option"), this,
                                          "ignoreRethrownExceptions");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new ThrowCaughtLocallyVisitor();
  }

  private class ThrowCaughtLocallyVisitor
    extends BaseInspectionVisitor {

    @Override
    public void visitThrowStatement(PsiThrowStatement statement) {
      super.visitThrowStatement(statement);
      final PsiExpression exception = statement.getException();
      if (exception == null) {
        return;
      }
      final PsiType exceptionType = exception.getType();
      if (exceptionType == null) {
        return;
      }
      PsiTryStatement containingTryStatement =
        PsiTreeUtil.getParentOfType(statement,
                                    PsiTryStatement.class);
      while (containingTryStatement != null) {
        final PsiCodeBlock tryBlock =
          containingTryStatement.getTryBlock();
        if (tryBlock == null) {
          return;
        }
        if (PsiTreeUtil.isAncestor(tryBlock, statement, true)) {
          final PsiParameter[] catchBlockParameters =
            containingTryStatement.getCatchBlockParameters();
          for (PsiParameter parameter : catchBlockParameters) {
            final PsiType parameterType = parameter.getType();
            if (!parameterType.isAssignableFrom(exceptionType)) {
              continue;
            }
            if (ignoreRethrownExceptions) {
              final PsiCatchSection section =
                (PsiCatchSection)parameter.getParent();
              final PsiCodeBlock catchBlock =
                section.getCatchBlock();
              if (isExceptionRethrown(parameter, catchBlock)) {
                return;
              }
            }
            final PsiClass containingClass =
              ClassUtils.getContainingClass(statement);
            if (PsiTreeUtil.isAncestor(containingClass,
                                       containingTryStatement, true)) {
              registerStatementError(statement);
              return;
            }
          }
        }
        containingTryStatement =
          PsiTreeUtil.getParentOfType(containingTryStatement,
                                      PsiTryStatement.class);
      }
    }

    private boolean isExceptionRethrown(PsiParameter parameter,
                                        PsiCodeBlock catchBlock) {
      final PsiStatement[] statements = catchBlock.getStatements();
      if (statements.length <= 0) {
        return false;
      }
      final PsiStatement lastStatement =
        statements[statements.length - 1];
      if (!(lastStatement instanceof PsiThrowStatement)) {
        return false;
      }
      final PsiThrowStatement throwStatement =
        (PsiThrowStatement)lastStatement;
      final PsiExpression expression = throwStatement.getException();
      if (!(expression instanceof PsiReferenceExpression)) {
        return false;
      }
      final PsiReferenceExpression referenceExpression =
        (PsiReferenceExpression)expression;
      final PsiElement element = referenceExpression.resolve();
      return parameter.equals(element);
    }
  }
}
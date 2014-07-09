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
package com.siyeh.ig.maturity;

import com.intellij.codeInspection.BatchSuppressManager;
import com.intellij.codeInspection.SuppressionUtil;
import com.intellij.psi.JavaTokenType;
import com.intellij.psi.PsiAnnotation;
import com.intellij.psi.PsiComment;
import com.intellij.psi.PsiJavaCodeReferenceElement;
import com.intellij.psi.tree.IElementType;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

public class SuppressionAnnotationInspection extends BaseInspection {

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message(
      "inspection.suppression.annotation.display.name");
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "inspection.suppression.annotation.problem.descriptor");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new SuppressionAnnotationVisitor();
  }

  private static class SuppressionAnnotationVisitor
    extends BaseInspectionVisitor {
    @Override
    public void visitComment(PsiComment comment) {
      super.visitComment(comment);
      final String commentText = comment.getText();
      final IElementType tokenType = comment.getTokenType();
      if (!tokenType.equals(JavaTokenType.END_OF_LINE_COMMENT)
          && !tokenType.equals(JavaTokenType.C_STYLE_COMMENT)) {
        return;
      }
      @NonNls final String strippedComment = commentText.substring(2).trim();
      if (strippedComment.startsWith(SuppressionUtil.SUPPRESS_INSPECTIONS_TAG_NAME)) {
        registerError(comment);
      }
    }

    @Override
    public void visitAnnotation(PsiAnnotation annotation) {
      super.visitAnnotation(annotation);
      final PsiJavaCodeReferenceElement reference =
        annotation.getNameReferenceElement();
      if (reference == null) {
        return;
      }
      @NonNls final String text = reference.getText();
      if ("SuppressWarnings".equals(text) ||
          BatchSuppressManager.SUPPRESS_INSPECTIONS_ANNOTATION_NAME.equals(text)) {
        registerError(annotation);
      }
    }
  }
}

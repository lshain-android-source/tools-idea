/*
 * Copyright 2003-2010 Dave Griffith, Bas Leijdekkers
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
package com.siyeh.ig.classmetrics;

import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiField;
import com.intellij.psi.PsiModifier;
import com.intellij.psi.PsiType;
import com.intellij.util.ui.CheckBox;
import com.intellij.util.ui.UIUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.psiutils.ClassUtils;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import javax.swing.border.LineBorder;
import java.awt.*;

public class FieldCountInspection extends ClassMetricInspection {

  private static final int FIELD_COUNT_LIMIT = 10;
  /**
   * @noinspection PublicField
   */
  public boolean m_countConstantFields = false;

  /**
   * @noinspection PublicField
   */
  public boolean m_considerStaticFinalFieldsConstant = false;

  @Override
  @NotNull
  public String getID() {
    return "ClassWithTooManyFields";
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("too.many.fields.display.name");
  }

  @Override
  @NotNull
  public String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message(
      "too.many.fields.problem.descriptor", infos[0]);
  }

  @Override
  protected int getDefaultLimit() {
    return FIELD_COUNT_LIMIT;
  }

  @Override
  protected String getConfigurationLabel() {
    return InspectionGadgetsBundle.message(
      "too.many.fields.count.limit.option");
  }

  @Override
  public JComponent createOptionsPanel() {
    final String configurationLabel = getConfigurationLabel();
    final JLabel label = new JLabel(configurationLabel);
    final JFormattedTextField valueField = prepareNumberEditor("m_limit");

    final CheckBox includeCheckBox =
      new CheckBox(InspectionGadgetsBundle.message(
        "field.count.inspection.include.constant.fields.in.count.checkbox"),
                   this, "m_countConstantFields");
    final CheckBox considerCheckBox =
      new CheckBox(InspectionGadgetsBundle.message(
        "field.count.inspection.static.final.fields.count.as.constant.checkbox"),
                   this, "m_considerStaticFinalFieldsConstant");

    final GridBagConstraints constraints = new GridBagConstraints();
    constraints.gridx = 0;
    constraints.gridy = 0;
    constraints.weightx = 0.0;
    constraints.weighty = 0.0;
    constraints.insets.right = UIUtil.DEFAULT_HGAP;
    constraints.anchor = GridBagConstraints.WEST;
    constraints.fill = GridBagConstraints.NONE;
    final JPanel panel = new JPanel(new GridBagLayout());
    panel.add(label, constraints);
    constraints.gridx = 1;
    constraints.gridy = 0;
    constraints.gridwidth = 1;
    constraints.weightx = 1.0;
    constraints.insets.right = 0;
    constraints.anchor = GridBagConstraints.NORTHWEST;
    constraints.fill = GridBagConstraints.NONE;
    panel.add(valueField, constraints);
    constraints.gridx = 0;
    constraints.gridy = 1;
    constraints.gridwidth = 2;
    constraints.weightx = 1.0;
    constraints.anchor = GridBagConstraints.NORTHWEST;
    constraints.fill = GridBagConstraints.NONE;
    panel.add(includeCheckBox, constraints);
    constraints.gridy = 2;
    constraints.weighty = 1;
    panel.add(considerCheckBox, constraints);
    return panel;
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new FieldCountVisitor();
  }

  private class FieldCountVisitor extends BaseInspectionVisitor {

    @Override
    public void visitClass(@NotNull PsiClass aClass) {
      // note: no call to super
      final int totalFields = countFields(aClass);
      if (totalFields <= getLimit()) {
        return;
      }
      registerClassError(aClass, Integer.valueOf(totalFields));
    }

    private int countFields(PsiClass aClass) {
      int totalFields = 0;
      final PsiField[] fields = aClass.getFields();
      for (final PsiField field : fields) {
        if (m_countConstantFields) {
          totalFields++;
        }
        else {
          if (!fieldIsConstant(field)) {
            totalFields++;
          }
        }
      }
      return totalFields;
    }

    private boolean fieldIsConstant(PsiField field) {
      if (!field.hasModifierProperty(PsiModifier.STATIC)) {
        return false;
      }
      if (!field.hasModifierProperty(PsiModifier.FINAL)) {
        return false;
      }
      if (m_considerStaticFinalFieldsConstant) {
        return true;
      }
      final PsiType type = field.getType();
      return ClassUtils.isImmutable(type);
    }
  }
}
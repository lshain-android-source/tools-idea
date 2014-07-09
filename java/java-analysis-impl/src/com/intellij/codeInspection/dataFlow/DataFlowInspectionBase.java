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

/*
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Dec 24, 2001
 * Time: 2:46:32 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInsight.AnnotationUtil;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.NullableNotNullManager;
import com.intellij.codeInsight.daemon.GroupNames;
import com.intellij.codeInsight.daemon.impl.quickfix.SimplifyBooleanExpressionFix;
import com.intellij.codeInsight.intention.impl.AddNullableAnnotationFix;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.dataFlow.instructions.*;
import com.intellij.codeInspection.dataFlow.value.DfaConstValue;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.WriteExternalException;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.JavaCodeStyleManager;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.SmartList;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class DataFlowInspectionBase extends BaseJavaBatchLocalInspectionTool {
  private static final Logger LOG = Logger.getInstance("#com.intellij.codeInspection.dataFlow.DataFlowInspection");
  @NonNls private static final String SHORT_NAME = "ConstantConditions";
  public boolean SUGGEST_NULLABLE_ANNOTATIONS = false;
  public boolean DONT_REPORT_TRUE_ASSERT_STATEMENTS = false;
  public boolean IGNORE_ASSERT_STATEMENTS = false;
  public boolean REPORT_CONSTANT_REFERENCE_VALUES = true;

  @Override
  public JComponent createOptionsPanel() {
    throw new RuntimeException("no UI in headless mode");
  }

  @Override
  public void writeSettings(@NotNull Element node) throws WriteExternalException {
    node.addContent(new Element("option").setAttribute("name", "SUGGEST_NULLABLE_ANNOTATIONS").setAttribute("value", String.valueOf(SUGGEST_NULLABLE_ANNOTATIONS)));
    node.addContent(new Element("option").setAttribute("name", "DONT_REPORT_TRUE_ASSERT_STATEMENTS").setAttribute("value", String.valueOf(DONT_REPORT_TRUE_ASSERT_STATEMENTS)));
    if (IGNORE_ASSERT_STATEMENTS) {
      node.addContent(new Element("option").setAttribute("name", "IGNORE_ASSERT_STATEMENTS").setAttribute("value", "true"));
    }
    if (!REPORT_CONSTANT_REFERENCE_VALUES) {
      node.addContent(new Element("option").setAttribute("name", "REPORT_CONSTANT_REFERENCE_VALUES").setAttribute("value", "false"));
    }
  }

  @Override
  @NotNull
  public PsiElementVisitor buildVisitor(@NotNull final ProblemsHolder holder, boolean isOnTheFly) {
    return new JavaElementVisitor() {
      @Override
      public void visitField(PsiField field) {
        analyzeCodeBlock(field, holder);
      }

      @Override
      public void visitMethod(PsiMethod method) {
        analyzeCodeBlock(method.getBody(), holder);
      }

      @Override
      public void visitClassInitializer(PsiClassInitializer initializer) {
        analyzeCodeBlock(initializer.getBody(), holder);
      }
    };
  }

  private void analyzeCodeBlock(@Nullable final PsiElement scope, ProblemsHolder holder) {
    if (scope == null) return;
    final StandardDataFlowRunner dfaRunner = new StandardDataFlowRunner(SUGGEST_NULLABLE_ANNOTATIONS);
    final StandardInstructionVisitor visitor = new DataFlowInstructionVisitor(dfaRunner);
    final RunnerResult rc = dfaRunner.analyzeMethod(scope, visitor, IGNORE_ASSERT_STATEMENTS);
    if (rc == RunnerResult.OK) {
      createDescription(dfaRunner, holder, visitor);
    }
    else if (rc == RunnerResult.TOO_COMPLEX) {
      if (scope.getParent() instanceof PsiMethod) {
        PsiMethod method = (PsiMethod)scope.getParent();
        final PsiIdentifier name = method.getNameIdentifier();
        if (name != null) { // Might be null for synthetic methods like JSP page.
          holder.registerProblem(name, InspectionsBundle.message("dataflow.too.complex"), ProblemHighlightType.WEAK_WARNING);
        }
      }
    }
  }

  @Nullable
  private LocalQuickFix[] createNPEFixes(PsiExpression qualifier, PsiExpression expression) {
    if (qualifier == null || expression == null) return null;
    if (qualifier instanceof PsiMethodCallExpression) return null;
    if (qualifier instanceof PsiLiteralExpression && ((PsiLiteralExpression)qualifier).getValue() == null) return null;

    try {
      final List<LocalQuickFix> fixes = new SmartList<LocalQuickFix>();

      if (PsiUtil.getLanguageLevel(qualifier).isAtLeast(LanguageLevel.JDK_1_4)) {
        final Project project = qualifier.getProject();
        final PsiElementFactory elementFactory = JavaPsiFacade.getInstance(project).getElementFactory();
        final PsiBinaryExpression binary = (PsiBinaryExpression)elementFactory.createExpressionFromText("a != null", null);
        binary.getLOperand().replace(qualifier);
        fixes.add(new AddAssertStatementFix(binary));
      }

      addSurroundWithIfFix(qualifier, fixes);

      if (ReplaceWithTernaryOperatorFix.isAvailable(qualifier, expression)) {
        fixes.add(new ReplaceWithTernaryOperatorFix(qualifier));
      }
      return fixes.toArray(new LocalQuickFix[fixes.size()]);
    }
    catch (IncorrectOperationException e) {
      LOG.error(e);
      return null;
    }
  }

  protected void addSurroundWithIfFix(PsiExpression qualifier, List<LocalQuickFix> fixes) {
  }

  private void createDescription(StandardDataFlowRunner runner, ProblemsHolder holder, StandardInstructionVisitor visitor) {
    Pair<Set<Instruction>, Set<Instruction>> constConditions = runner.getConstConditionalExpressions();
    Set<Instruction> trueSet = constConditions.getFirst();
    Set<Instruction> falseSet = constConditions.getSecond();

    ArrayList<Instruction> allProblems = new ArrayList<Instruction>();
    allProblems.addAll(trueSet);
    allProblems.addAll(falseSet);
    allProblems.addAll(runner.getNPEInstructions());
    allProblems.addAll(runner.getCCEInstructions());
    allProblems.addAll(StandardDataFlowRunner.getRedundantInstanceofs(runner, visitor));

    Collections.sort(allProblems, new Comparator<Instruction>() {
      @Override
      public int compare(Instruction i1, Instruction i2) {
        return i1.getIndex() - i2.getIndex();
      }
    });

    HashSet<PsiElement> reportedAnchors = new HashSet<PsiElement>();

    for (Instruction instruction : allProblems) {
      if (instruction instanceof MethodCallInstruction) {
        reportCallMayProduceNpe(holder, (MethodCallInstruction)instruction, reportedAnchors);
      }
      else if (instruction instanceof FieldReferenceInstruction &&
               reportedAnchors.add(((FieldReferenceInstruction)instruction).getElementToAssert())) {
        reportFieldAccessMayProduceNpe(holder, (FieldReferenceInstruction)instruction);
      }
      else if (instruction instanceof TypeCastInstruction &&
               reportedAnchors.add(((TypeCastInstruction)instruction).getCastExpression().getCastType())) {
        reportCastMayFail(holder, (TypeCastInstruction)instruction);
      }
      else if (instruction instanceof BranchingInstruction) {
        handleBranchingInstruction(holder, visitor, trueSet, falseSet, reportedAnchors, (BranchingInstruction)instruction);
      }
    }

    reportNullableArguments(runner, holder, reportedAnchors);
    reportNullableAssignments(runner, holder, reportedAnchors);
    reportUnboxedNullables(runner, holder, reportedAnchors);
    reportNullableReturns(runner, holder, reportedAnchors);
    reportNullableArgumentsPassedToNonAnnotated(runner, holder, reportedAnchors);

    if (REPORT_CONSTANT_REFERENCE_VALUES) {
      reportConstantReferenceValues(holder, visitor, reportedAnchors);
    }
  }

  private static void reportConstantReferenceValues(ProblemsHolder holder, StandardInstructionVisitor visitor, Set<PsiElement> reportedAnchors) {
    for (Pair<PsiReferenceExpression, DfaConstValue> pair : visitor.getConstantReferenceValues()) {
      PsiReferenceExpression ref = pair.first;
      if (!reportedAnchors.add(ref)) {
        continue;
      }

      final Object value = pair.second.getValue();
      PsiVariable constant = pair.second.getConstant();
      final String presentableName = constant != null ? constant.getName() : String.valueOf(value);
      final String exprText = getConstantValueText(value, constant);
      if (presentableName == null || exprText == null) {
        continue;
      }

      holder.registerProblem(ref, "Value <code>#ref</code> #loc is always '" + presentableName + "'", new LocalQuickFix() {
        @NotNull
        @Override
        public String getName() {
          return "Replace with '" + presentableName + "'";
        }

        @NotNull
        @Override
        public String getFamilyName() {
          return "Replace with constant value";
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
          JavaPsiFacade facade = JavaPsiFacade.getInstance(project);
          PsiElement newElement = descriptor.getPsiElement().replace(facade.getElementFactory().createExpressionFromText(exprText, null));
          newElement = JavaCodeStyleManager.getInstance(project).shortenClassReferences(newElement);
          if (newElement instanceof PsiJavaCodeReferenceElement) {
            PsiJavaCodeReferenceElement ref = (PsiJavaCodeReferenceElement)newElement;
            PsiElement target = ref.resolve();
            String shortName = ref.getReferenceName();
            if (target != null && shortName != null && ref.isQualified() &&
                facade.getResolveHelper().resolveReferencedVariable(shortName, newElement) == target) {
              newElement.replace(facade.getElementFactory().createExpressionFromText(shortName, null));
            }
          }
        }
      });
    }
  }

  private static String getConstantValueText(Object value, @Nullable PsiVariable constant) {
    if (constant != null) {
      return constant instanceof PsiMember ? PsiUtil.getMemberQualifiedName((PsiMember)constant) : constant.getName();
    }

    return value instanceof String ? "\"" + StringUtil.escapeStringCharacters((String)value) + "\"" : String.valueOf(value);
  }

  private void reportNullableArgumentsPassedToNonAnnotated(StandardDataFlowRunner runner, ProblemsHolder holder, Set<PsiElement> reportedAnchors) {
    Set<PsiExpression> exprs = runner.getNullableArgumentsPassedToNonAnnotatedParam();
    for (PsiExpression expr : exprs) {
      if (reportedAnchors.contains(expr)) continue;

      final String text = isNullLiteralExpression(expr)
                          ? "Passing <code>null</code> argument to non annotated parameter"
                          : "Argument <code>#ref</code> #loc might be null but passed to non annotated parameter";
      LocalQuickFix[] fixes = createNPEFixes(expr, expr);
      final PsiElement parent = expr.getParent();
      if (parent instanceof PsiExpressionList) {
        final int idx = ArrayUtilRt.find(((PsiExpressionList)parent).getExpressions(), expr);
        if (idx > -1) {
          final PsiElement gParent = parent.getParent();
          if (gParent instanceof PsiCallExpression) {
            final PsiMethod psiMethod = ((PsiCallExpression)gParent).resolveMethod();
            if (psiMethod != null && psiMethod.getManager().isInProject(psiMethod) && AnnotationUtil.isAnnotatingApplicable(psiMethod)) {
              final PsiParameter[] parameters = psiMethod.getParameterList().getParameters();
              if (idx < parameters.length) {
                final AddNullableAnnotationFix addNullableAnnotationFix = new AddNullableAnnotationFix(parameters[idx]);
                fixes = fixes == null ? new LocalQuickFix[]{addNullableAnnotationFix} : ArrayUtil.append(fixes, addNullableAnnotationFix);
                holder.registerProblem(expr, text, fixes);
                reportedAnchors.add(expr);
              }
            }
          }
        }
      }

    }
  }

  private void reportCallMayProduceNpe(ProblemsHolder holder, MethodCallInstruction mcInstruction, Set<PsiElement> reportedAnchors) {
    if (mcInstruction.getCallExpression() instanceof PsiMethodCallExpression) {
      PsiMethodCallExpression callExpression = (PsiMethodCallExpression)mcInstruction.getCallExpression();
      if (!reportedAnchors.add(callExpression)) return;

      LocalQuickFix[] fix = createNPEFixes(callExpression.getMethodExpression().getQualifierExpression(), callExpression);

      holder.registerProblem(callExpression,
                             InspectionsBundle.message("dataflow.message.npe.method.invocation"),
                             fix);
    }
  }

  private void reportFieldAccessMayProduceNpe(ProblemsHolder holder, FieldReferenceInstruction frInstruction) {
    PsiElement elementToAssert = frInstruction.getElementToAssert();
    PsiExpression expression = frInstruction.getExpression();
    if (expression instanceof PsiArrayAccessExpression) {
      LocalQuickFix[] fix = createNPEFixes((PsiExpression)elementToAssert, expression);
      holder.registerProblem(expression,
                             InspectionsBundle.message("dataflow.message.npe.array.access"),
                             fix);
    }
    else {
      LocalQuickFix[] fix = createNPEFixes((PsiExpression)elementToAssert, expression);
      assert elementToAssert != null;
      holder.registerProblem(elementToAssert,
                             InspectionsBundle.message("dataflow.message.npe.field.access"),
                             fix);
    }
  }

  private static void reportCastMayFail(ProblemsHolder holder, TypeCastInstruction instruction) {
    PsiTypeCastExpression typeCast = instruction.getCastExpression();
    holder.registerProblem(typeCast.getCastType(),
                           InspectionsBundle.message("dataflow.message.cce", typeCast.getOperand().getText()));
  }

  private void handleBranchingInstruction(ProblemsHolder holder,
                                          StandardInstructionVisitor visitor,
                                          Set<Instruction> trueSet,
                                          Set<Instruction> falseSet, HashSet<PsiElement> reportedAnchors, BranchingInstruction instruction) {
    PsiElement psiAnchor = instruction.getPsiAnchor();
    boolean underBinary = isAtRHSOfBooleanAnd(psiAnchor);
    if (instruction instanceof InstanceofInstruction && visitor.isInstanceofRedundant((InstanceofInstruction)instruction)) {
      if (visitor.canBeNull((BinopInstruction)instruction)) {
        holder.registerProblem(psiAnchor,
                               InspectionsBundle.message("dataflow.message.redundant.instanceof"),
                               new RedundantInstanceofFix());
      }
      else {
        final LocalQuickFix localQuickFix = createSimplifyBooleanExpressionFix(psiAnchor, true);
        holder.registerProblem(psiAnchor,
                               InspectionsBundle.message(underBinary ? "dataflow.message.constant.condition.when.reached" : "dataflow.message.constant.condition", Boolean.toString(true)),
                               localQuickFix == null ? null : new LocalQuickFix[]{localQuickFix});
      }
    }
    else if (psiAnchor instanceof PsiSwitchLabelStatement) {
      if (falseSet.contains(instruction)) {
        holder.registerProblem(psiAnchor,
                               InspectionsBundle.message("dataflow.message.unreachable.switch.label"));
      }
    }
    else if (psiAnchor != null && !reportedAnchors.contains(psiAnchor) && !isCompileConstantInIfCondition(psiAnchor)) {
      boolean evaluatesToTrue = trueSet.contains(instruction);
      if (onTheLeftSideOfConditionalAssignemnt(psiAnchor)) {
        holder.registerProblem(
          psiAnchor,
          InspectionsBundle.message("dataflow.message.pointless.assignment.expression", Boolean.toString(evaluatesToTrue)),
          createSimplifyToAssignmentFix()
        );
      }
      else if (!skipReportingConstantCondition(visitor, psiAnchor, evaluatesToTrue)) {
        final LocalQuickFix fix = createSimplifyBooleanExpressionFix(psiAnchor, evaluatesToTrue);
        String message = InspectionsBundle.message(underBinary ?
                                                   "dataflow.message.constant.condition.when.reached" :
                                                   "dataflow.message.constant.condition", Boolean.toString(evaluatesToTrue));
        holder.registerProblem(psiAnchor, message, fix == null ? null : new LocalQuickFix[]{fix});
      }
      reportedAnchors.add(psiAnchor);
    }
  }

  private boolean skipReportingConstantCondition(StandardInstructionVisitor visitor, PsiElement psiAnchor, boolean evaluatesToTrue) {
    return DONT_REPORT_TRUE_ASSERT_STATEMENTS && isAssertionEffectively(psiAnchor, evaluatesToTrue) ||
           visitor.silenceConstantCondition(psiAnchor);
  }

  private void reportNullableArguments(StandardDataFlowRunner runner, ProblemsHolder holder, Set<PsiElement> reportedAnchors) {
    Set<PsiExpression> exprs = runner.getNullableArguments();
    for (PsiExpression expr : exprs) {
      if (!reportedAnchors.add(expr)) continue;

      final String text = isNullLiteralExpression(expr)
                          ? InspectionsBundle.message("dataflow.message.passing.null.argument")
                          : InspectionsBundle.message("dataflow.message.passing.nullable.argument");
      LocalQuickFix[] fixes = createNPEFixes(expr, expr);
      holder.registerProblem(expr, text, fixes);
    }
  }

  private static void reportNullableAssignments(StandardDataFlowRunner runner, ProblemsHolder holder, Set<PsiElement> reportedAnchors) {
    for (PsiExpression expr : runner.getNullableAssignments()) {
      if (!reportedAnchors.add(expr)) continue;

      final String text = isNullLiteralExpression(expr)
                          ? InspectionsBundle.message("dataflow.message.assigning.null")
                          : InspectionsBundle.message("dataflow.message.assigning.nullable");
      holder.registerProblem(expr, text);
    }
  }

  private static void reportUnboxedNullables(StandardDataFlowRunner runner, ProblemsHolder holder, Set<PsiElement> reportedAnchors) {
    for (PsiExpression expr : runner.getUnboxedNullables()) {
      if (!reportedAnchors.add(expr)) continue;
      holder.registerProblem(expr, InspectionsBundle.message("dataflow.message.unboxing"));
    }
  }

  private static void reportNullableReturns(StandardDataFlowRunner runner, ProblemsHolder holder, Set<PsiElement> reportedAnchors) {
    for (PsiReturnStatement statement : runner.getNullableReturns()) {
      final PsiExpression expr = statement.getReturnValue();
      assert expr != null;
      if (!reportedAnchors.add(expr)) continue;

      if (runner.isInNotNullMethod()) {
        final String text = isNullLiteralExpression(expr)
                            ? InspectionsBundle.message("dataflow.message.return.null.from.notnull")
                            : InspectionsBundle.message("dataflow.message.return.nullable.from.notnull");
        holder.registerProblem(expr, text);
      }
      else if (AnnotationUtil.isAnnotatingApplicable(statement)) {
        final String text = isNullLiteralExpression(expr)
                            ? InspectionsBundle.message("dataflow.message.return.null.from.notnullable")
                            : InspectionsBundle.message("dataflow.message.return.nullable.from.notnullable");
        final NullableNotNullManager manager = NullableNotNullManager.getInstance(expr.getProject());
        holder.registerProblem(expr, text, new AnnotateMethodFix(manager.getDefaultNullable(), ArrayUtil.toStringArray(manager.getNotNulls())){
          @Override
          public int shouldAnnotateBaseMethod(PsiMethod method, PsiMethod superMethod, Project project) {
            return 1;
          }
        });
      }
    }
  }

  private static boolean isAssertionEffectively(PsiElement psiAnchor, boolean evaluatesToTrue) {
    PsiElement parent = psiAnchor.getParent();
    if (parent instanceof PsiAssertStatement) {
      return evaluatesToTrue;
    }
    if (parent instanceof PsiIfStatement && psiAnchor == ((PsiIfStatement)parent).getCondition()) {
      PsiStatement thenBranch = ((PsiIfStatement)parent).getThenBranch();
      if (thenBranch instanceof PsiThrowStatement) {
        return !evaluatesToTrue;
      }
      if (thenBranch instanceof PsiBlockStatement) {
        PsiStatement[] statements = ((PsiBlockStatement)thenBranch).getCodeBlock().getStatements();
        if (statements.length == 1 && statements[0] instanceof PsiThrowStatement) {
          return !evaluatesToTrue;
        }
      }
    }
    return false;
  }

  private static boolean isAtRHSOfBooleanAnd(PsiElement expr) {
    PsiElement cur = expr;

    while (cur != null && !(cur instanceof PsiMember)) {
      PsiElement parent = cur.getParent();

      if (parent instanceof PsiBinaryExpression && cur == ((PsiBinaryExpression)parent).getROperand()) {
        return true;
      }

      cur = parent;
    }

    return false;
  }

  private static boolean isCompileConstantInIfCondition(PsiElement element) {
    if (!(element instanceof PsiReferenceExpression)) return false;
    PsiElement resolved = ((PsiReferenceExpression)element).resolve();
    if (!(resolved instanceof PsiField)) return false;
    PsiField field = (PsiField)resolved;

    if (!field.hasModifierProperty(PsiModifier.FINAL)) return false;
    if (!field.hasModifierProperty(PsiModifier.STATIC)) return false;

    PsiElement parent = element.getParent();
    if (parent instanceof PsiPrefixExpression && ((PsiPrefixExpression)parent).getOperationTokenType() == JavaTokenType.EXCL) {
      element = parent;
      parent = parent.getParent();
    }
    return parent instanceof PsiIfStatement && ((PsiIfStatement)parent).getCondition() == element;
  }

  private static boolean isNullLiteralExpression(PsiExpression expr) {
    if (expr instanceof PsiLiteralExpression) {
      final PsiLiteralExpression literalExpression = (PsiLiteralExpression)expr;
      return PsiType.NULL.equals(literalExpression.getType());
    }
    return false;
  }

  private static boolean onTheLeftSideOfConditionalAssignemnt(final PsiElement psiAnchor) {
    final PsiElement parent = psiAnchor.getParent();
    if (parent instanceof PsiAssignmentExpression) {
      final PsiAssignmentExpression expression = (PsiAssignmentExpression)parent;
      if (expression.getLExpression() == psiAnchor) return true;
    }
    return false;
  }

  @Nullable
  private static LocalQuickFix createSimplifyBooleanExpressionFix(PsiElement element, final boolean value) {
    SimplifyBooleanExpressionFix fix = createIntention(element, value);
    if (fix == null) return null;
    final String text = fix.getText();
    return new LocalQuickFix() {
      @Override
      @NotNull
      public String getName() {
        return text;
      }

      @Override
      public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        final PsiElement psiElement = descriptor.getPsiElement();
        if (psiElement == null) return;
        final SimplifyBooleanExpressionFix fix = createIntention(psiElement, value);
        if (fix == null) return;
        try {
          LOG.assertTrue(psiElement.isValid());
          fix.applyFix();
        }
        catch (IncorrectOperationException e) {
          LOG.error(e);
        }
      }

      @Override
      @NotNull
      public String getFamilyName() {
        return InspectionsBundle.message("inspection.data.flow.simplify.boolean.expression.quickfix");
      }
    };
  }

  @NotNull
  private static LocalQuickFix createSimplifyToAssignmentFix() {
    return new LocalQuickFix() {
      @NotNull
      @Override
      public String getName() {
        return InspectionsBundle.message("inspection.data.flow.simplify.to.assignment.quickfix.name");
      }

      @NotNull
      @Override
      public String getFamilyName() {
        return InspectionsBundle.message("inspection.data.flow.simplify.boolean.expression.quickfix");
      }

      @Override
      public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
        final PsiElement psiElement = descriptor.getPsiElement();
        if (psiElement == null) return;

        final PsiAssignmentExpression assignmentExpression = PsiTreeUtil.getParentOfType(psiElement, PsiAssignmentExpression.class);
        if (assignmentExpression == null) {
          return;
        }

        final PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
        final String lExpressionText = assignmentExpression.getLExpression().getText();
        final PsiExpression rExpression = assignmentExpression.getRExpression();
        final String rExpressionText = rExpression != null ? rExpression.getText() : "";
        assignmentExpression.replace(factory.createExpressionFromText(lExpressionText + " = " + rExpressionText, psiElement));
      }
    };
  }

  private static SimplifyBooleanExpressionFix createIntention(PsiElement element, boolean value) {
    if (!(element instanceof PsiExpression)) return null;
    final PsiExpression expression = (PsiExpression)element;
    while (element.getParent() instanceof PsiExpression) {
      element = element.getParent();
    }
    final SimplifyBooleanExpressionFix fix = new SimplifyBooleanExpressionFix(expression, value);
    // simplify intention already active
    if (!fix.isAvailable() ||
        SimplifyBooleanExpressionFix.canBeSimplified((PsiExpression)element)) {
      return null;
    }
    return fix;
  }

  private static class RedundantInstanceofFix implements LocalQuickFix {
    @Override
    @NotNull
    public String getName() {
      return InspectionsBundle.message("inspection.data.flow.redundant.instanceof.quickfix");
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      if (!FileModificationService.getInstance().preparePsiElementForWrite(descriptor.getPsiElement())) return;
      final PsiElement psiElement = descriptor.getPsiElement();
      if (psiElement instanceof PsiInstanceOfExpression) {
        try {
          final PsiExpression compareToNull = JavaPsiFacade.getInstance(psiElement.getProject()).getElementFactory().
            createExpressionFromText(((PsiInstanceOfExpression)psiElement).getOperand().getText() + " != null", psiElement.getParent());
          psiElement.replace(compareToNull);
        }
        catch (IncorrectOperationException e) {
          LOG.error(e);
        }
      }
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return getName();
    }
  }


  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionsBundle.message("inspection.data.flow.display.name");
  }

  @Override
  @NotNull
  public String getGroupDisplayName() {
    return GroupNames.BUGS_GROUP_NAME;
  }

  @Override
  @NotNull
  public String getShortName() {
    return SHORT_NAME;
  }

  private static class DataFlowInstructionVisitor extends StandardInstructionVisitor {
    private final StandardDataFlowRunner myRunner;

    private DataFlowInstructionVisitor(StandardDataFlowRunner runner) {
      myRunner = runner;
    }

    @Override
    protected void onAssigningToNotNullableVariable(AssignInstruction instruction) {
      myRunner.onAssigningToNotNullableVariable(instruction.getRExpression());
    }

    @Override
    protected void onNullableReturn(CheckReturnValueInstruction instruction) {
      myRunner.onNullableReturn(instruction.getReturn());
    }

    @Override
    protected void onInstructionProducesCCE(TypeCastInstruction instruction) {
      myRunner.onInstructionProducesCCE(instruction);
    }

    @Override
    protected void onInstructionProducesNPE(Instruction instruction) {
      if (instruction instanceof MethodCallInstruction &&
          ((MethodCallInstruction)instruction).getMethodType() == MethodCallInstruction.MethodType.UNBOXING) {
        myRunner.onUnboxingNullable(((MethodCallInstruction)instruction).getContext());
      }
      else {
        myRunner.onInstructionProducesNPE(instruction);
      }
    }

    @Override
    protected void onPassingNullParameter(PsiExpression arg) {
      myRunner.onPassingNullParameter(arg);
    }

    @Override
    protected void onPassingNullParameterToNonAnnotated(DataFlowRunner runner, PsiExpression arg) {
      myRunner.onPassingNullParameterToNonAnnotated(arg);
    }
  }
}

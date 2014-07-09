/*
 * Copyright 2000-2011 JetBrains s.r.o.
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
package com.intellij.codeInspection.javaDoc;

import com.intellij.codeHighlighting.HighlightDisplayLevel;
import com.intellij.codeInsight.FileModificationService;
import com.intellij.codeInsight.daemon.QuickFixBundle;
import com.intellij.codeInsight.daemon.impl.quickfix.ImportClassFix;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.codeInsight.lookup.LookupManager;
import com.intellij.codeInspection.*;
import com.intellij.codeInspection.ex.BaseLocalInspectionTool;
import com.intellij.ide.DataManager;
import com.intellij.ide.util.FQNameCellRenderer;
import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.application.Result;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.popup.PopupChooserBuilder;
import com.intellij.openapi.util.AsyncResult;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.proximity.PsiProximityComparator;
import com.intellij.ui.components.JBList;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.*;

public class JavaDocReferenceInspection extends BaseLocalInspectionTool {
  @NonNls public static final String SHORT_NAME = "JavadocReference";

  private static ProblemDescriptor createDescriptor(@NotNull PsiElement element, String template, InspectionManager manager,
                                                    boolean onTheFly) {
    return manager.createProblemDescriptor(element, template, onTheFly, null, ProblemHighlightType.LIKE_UNKNOWN_SYMBOL);
  }

  @Override
  @Nullable
  public ProblemDescriptor[] checkMethod(@NotNull PsiMethod psiMethod, @NotNull InspectionManager manager, boolean isOnTheFly) {
    return checkMember(psiMethod, manager, isOnTheFly);
  }

  @Override
  @Nullable
  public ProblemDescriptor[] checkField(@NotNull PsiField field, @NotNull InspectionManager manager, boolean isOnTheFly) {
    return checkMember(field, manager, isOnTheFly);
  }

  @Override
  @Nullable
  public ProblemDescriptor[] checkClass(@NotNull PsiClass aClass, @NotNull InspectionManager manager, boolean isOnTheFly) {
    return checkMember(aClass, manager, isOnTheFly);
  }

  @Nullable
  private ProblemDescriptor[] checkMember(final PsiDocCommentOwner docCommentOwner, final InspectionManager manager, final boolean isOnTheFly) {
    final ArrayList<ProblemDescriptor> problems = new ArrayList<ProblemDescriptor>();
    final PsiDocComment docComment = docCommentOwner.getDocComment();
    if (docComment == null) return null;

    final Set<PsiJavaCodeReferenceElement> references = new HashSet<PsiJavaCodeReferenceElement>();
    docComment.accept(getVisitor(references, docCommentOwner, problems, manager, isOnTheFly));
    for (PsiJavaCodeReferenceElement reference : references) {
      final List<PsiClass> classesToImport = new ImportClassFix(reference).getClassesToImport();
      final PsiElement referenceNameElement = reference.getReferenceNameElement();
      problems.add(manager.createProblemDescriptor(referenceNameElement != null ? referenceNameElement : reference,
                                                   cannotResolveSymbolMessage("<code>" + reference.getText() + "</code>"),
                                                   !isOnTheFly || classesToImport.isEmpty() ? null : new AddQualifierFix(classesToImport),
                                                   ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, isOnTheFly));
    }

    return problems.isEmpty() ? null : problems.toArray(new ProblemDescriptor[problems.size()]);
  }

  private PsiElementVisitor getVisitor(final Set<PsiJavaCodeReferenceElement> references,
                                       final PsiElement context,
                                       final ArrayList<ProblemDescriptor> problems,
                                       final InspectionManager manager,
                                       final boolean onTheFly) {
    return new JavaElementVisitor() {
      @Override public void visitReferenceExpression(PsiReferenceExpression expression) {
        visitElement(expression);
      }

      @Override public void visitReferenceElement(PsiJavaCodeReferenceElement reference) {
        super.visitReferenceElement(reference);
        JavaResolveResult result = reference.advancedResolve(false);
        if (result.getElement() == null && !result.isPackagePrefixPackageReference()) {
          references.add(reference);
        }
      }

      @Override public void visitDocTag(PsiDocTag tag) {
        super.visitDocTag(tag);
        final JavadocManager javadocManager = JavadocManager.SERVICE.getInstance(tag.getProject());
        final JavadocTagInfo info = javadocManager.getTagInfo(tag.getName());
        if (info == null || !info.isInline()) {
          visitRefInDocTag(tag, javadocManager, context, problems, manager, onTheFly);
        }
      }

      @Override public void visitInlineDocTag(PsiInlineDocTag tag) {
        super.visitInlineDocTag(tag);
        final JavadocManager javadocManager = JavadocManager.SERVICE.getInstance(tag.getProject());
        visitRefInDocTag(tag, javadocManager, context, problems, manager, onTheFly);
      }

      @Override public void visitElement(PsiElement element) {
        PsiElement[] children = element.getChildren();
        for (PsiElement child : children) {
          //do not visit method javadoc twice
          if (!(child instanceof PsiDocCommentOwner)) {
            child.accept(this);
          }
        }
      }
    };
  }

  public static void visitRefInDocTag(final PsiDocTag tag,
                                      final JavadocManager manager,
                                      final PsiElement context,
                                      final ArrayList<ProblemDescriptor> problems,
                                      final InspectionManager inspectionManager,
                                      final boolean onTheFly) {
    final String tagName = tag.getName();
    final PsiDocTagValue value = tag.getValueElement();
    if (value == null) return;
    final JavadocTagInfo info = manager.getTagInfo(tagName);
    if (info != null && !info.isValidInContext(context)) return;
    final String message = info == null || !info.isInline() ? null : info.checkTagValue(value);
    if (message != null){
      problems.add(createDescriptor(value, message, inspectionManager, onTheFly));
    }

    final PsiReference reference = value.getReference();
    if (reference == null) return;
    final PsiElement element = reference.resolve();
    if (element != null) return;
    final int textOffset = value.getTextOffset();
    if (textOffset == value.getTextRange().getEndOffset()) return;
    final PsiDocTagValue valueElement = tag.getValueElement();
    if (valueElement == null) return;

    final CharSequence paramName = value.getContainingFile().getViewProvider().getContents().subSequence(textOffset, value.getTextRange().getEndOffset());
    final String params = "<code>" + paramName + "</code>";
    final List<LocalQuickFix> fixes = new ArrayList<LocalQuickFix>();
    if (onTheFly && "param".equals(tagName)) {
      final PsiDocCommentOwner commentOwner = PsiTreeUtil.getParentOfType(tag, PsiDocCommentOwner.class);
      if (commentOwner instanceof PsiMethod) {
        final PsiMethod method = (PsiMethod)commentOwner;
        final PsiParameter[] parameters = method.getParameterList().getParameters();
        final PsiDocTag[] tags = tag.getContainingComment().getTags();
        final Set<String> unboundParams = new HashSet<String>();
        for (PsiParameter parameter : parameters) {
          if (!JavaDocLocalInspection.isFound(tags, parameter)) {
            unboundParams.add(parameter.getName());
          }
        }
        if (!unboundParams.isEmpty()) {
          fixes.add(new RenameReferenceQuickFix(unboundParams));
        }
      }
    }
    fixes.add(new RemoveTagFix(tagName, paramName));

    problems.add(inspectionManager.createProblemDescriptor(valueElement, reference.getRangeInElement(), cannotResolveSymbolMessage(params),
                                                           ProblemHighlightType.LIKE_UNKNOWN_SYMBOL, onTheFly,
                                                           fixes.toArray(new LocalQuickFix[fixes.size()])));
  }

  private static String cannotResolveSymbolMessage(String params) {
    return InspectionsBundle.message("inspection.javadoc.problem.cannot.resolve", params);
  }

  @Override
  @NotNull
  public String getDisplayName() {
    return InspectionsBundle.message("inspection.javadoc.ref.display.name");
  }

  @Override
  @NotNull
  public String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.javadoc.issues");
  }

  @Override
  @NotNull
  public String getShortName() {
    return SHORT_NAME;
  }

  @Override
  @NotNull
  public HighlightDisplayLevel getDefaultLevel() {
    return HighlightDisplayLevel.ERROR;
  }

  private class AddQualifierFix implements LocalQuickFix{
    private final List<PsiClass> originalClasses;

    public AddQualifierFix(final List<PsiClass> originalClasses) {
      this.originalClasses = originalClasses;
    }

    @Override
    @NotNull
    public String getName() {
      return QuickFixBundle.message("add.qualifier");
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return QuickFixBundle.message("add.qualifier");
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor descriptor) {
      final PsiElement element = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiJavaCodeReferenceElement.class);
      if (element instanceof PsiJavaCodeReferenceElement) {
        final PsiJavaCodeReferenceElement referenceElement = (PsiJavaCodeReferenceElement)element;
        Collections.sort(originalClasses, new PsiProximityComparator(referenceElement.getElement()));
        final JList list = new JBList(originalClasses.toArray(new PsiClass[originalClasses.size()]));
        list.setCellRenderer(new FQNameCellRenderer());
        final Runnable runnable = new Runnable() {
          @Override
          public void run() {
            if (!element.isValid()) return;
            final int index = list.getSelectedIndex();
            if (index < 0) return;
            new WriteCommandAction(project, element.getContainingFile()){
              @Override
              protected void run(final Result result) throws Throwable {
                final PsiClass psiClass = originalClasses.get(index);
                if (psiClass.isValid()) {
                  PsiDocumentManager.getInstance(project).commitAllDocuments();
                  referenceElement.bindToElement(psiClass);
                }
              }
            }.execute();
          }
        };
        final AsyncResult<DataContext> asyncResult = DataManager.getInstance().getDataContextFromFocus();
        asyncResult.doWhenDone(new AsyncResult.Handler<DataContext>() {
          @Override
          public void run(DataContext dataContext) {
            new PopupChooserBuilder(list).
              setTitle(QuickFixBundle.message("add.qualifier.original.class.chooser.title")).
              setItemChoosenCallback(runnable).
              createPopup().
              showInBestPositionFor(dataContext);
          }
        });
      }
    }
  }

  private static class RenameReferenceQuickFix implements LocalQuickFix {
    private final Set<String> myUnboundParams;

    public RenameReferenceQuickFix(Set<String> unboundParams) {
      myUnboundParams = unboundParams;
    }

    @Override
    @NotNull
    public String getName() {
      return "Change to ...";
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return getName();
    }

    @Override
    public void applyFix(@NotNull final Project project, @NotNull final ProblemDescriptor descriptor) {
      final AsyncResult<DataContext> asyncResult = DataManager.getInstance().getDataContextFromFocus();
      asyncResult.doWhenDone(new AsyncResult.Handler<DataContext>() {
        @Override
        public void run(DataContext dataContext) {
          final Editor editor = PlatformDataKeys.EDITOR.getData(dataContext);
          assert editor != null;
          final TextRange textRange = ((ProblemDescriptorBase)descriptor).getTextRange();
          editor.getSelectionModel().setSelection(textRange.getStartOffset(), textRange.getEndOffset());

          final String word = editor.getSelectionModel().getSelectedText();

          if (word == null || StringUtil.isEmptyOrSpaces(word)) {
            return;
          }
          final List<LookupElement> items = new ArrayList<LookupElement>();
          for (String variant : myUnboundParams) {
            items.add(LookupElementBuilder.create(variant));
          }
          LookupManager.getInstance(project).showLookup(editor, items.toArray(new LookupElement[items.size()]));
        }
      });
    }
  }

  private static class RemoveTagFix implements LocalQuickFix {
    private final String myTagName;
    private final CharSequence myParamName;

    public RemoveTagFix(String tagName, CharSequence paramName) {
      myTagName = tagName;
      myParamName = paramName;
    }

    @Override
    @NotNull
    public String getName() {
      return "Remove @" + myTagName + " " + myParamName;
    }

    @Override
    @NotNull
    public String getFamilyName() {
      return getName();
    }

    @Override
    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
      final PsiDocTag myTag = PsiTreeUtil.getParentOfType(descriptor.getPsiElement(), PsiDocTag.class);
      if (myTag == null) return;
      if (!FileModificationService.getInstance().preparePsiElementForWrite(myTag)) return;
      myTag.delete();
    }
  }
}

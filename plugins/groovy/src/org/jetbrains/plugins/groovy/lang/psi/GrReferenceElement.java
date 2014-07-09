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
package org.jetbrains.plugins.groovy.lang.psi;

import com.intellij.codeInsight.completion.CompletionParameters;
import com.intellij.codeInsight.completion.PrefixMatcher;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiPolyVariantReference;
import com.intellij.psi.PsiType;
import com.intellij.util.Consumer;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.groovy.lang.psi.api.GroovyResolveResult;
import org.jetbrains.plugins.groovy.lang.psi.api.types.GrTypeArgumentList;

/**
 * @author ven
 */
public interface GrReferenceElement<Q extends PsiElement> extends GroovyPsiElement, PsiPolyVariantReference, GrQualifiedReference<Q> {
  @Nullable
  String getReferenceName();

  PsiElement resolve();

  GroovyResolveResult advancedResolve();

  @NotNull
  GroovyResolveResult[] multiResolve(boolean incompleteCode);

  @NotNull
  PsiType[] getTypeArguments();

  @Nullable
  GrTypeArgumentList getTypeArgumentList();

  void processVariants(PrefixMatcher matcher, CompletionParameters parameters, Consumer<LookupElement> consumer);

  @Nullable
  String getClassNameText();

  PsiElement handleElementRenameSimple(String newElementName) throws IncorrectOperationException;
}

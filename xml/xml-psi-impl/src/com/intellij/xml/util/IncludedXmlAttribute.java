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
package com.intellij.xml.util;

import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.IncorrectOperationException;
import com.intellij.xml.XmlAttributeDescriptor;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * @author peter
 */
public class IncludedXmlAttribute extends IncludedXmlElement<XmlAttribute> implements XmlAttribute {

  public IncludedXmlAttribute(@NotNull XmlAttribute original, @Nullable XmlTag parent) {
    super(original, parent);
  }

  @Override
  @NonNls
  @NotNull
  public String getName() {
    return getOriginal().getName();
  }

  @Override
  public PsiElement setName(@NonNls @NotNull String name) throws IncorrectOperationException {
    throw new UnsupportedOperationException("Can't modify included elements");
  }

  @NonNls
  @NotNull
  public String getLocalName() {
    return getOriginal().getLocalName();
  }

  @NonNls
  @NotNull
  public String getNamespace() {
    return getOriginal().getNamespace();
  }

  @NonNls
  @NotNull
  public String getNamespacePrefix() {
    return getOriginal().getNamespacePrefix();
  }

  public XmlTag getParent() {
    return (XmlTag)super.getParent();
  }

  public String getValue() {
    return getOriginal().getValue();
  }

  public String getDisplayValue() {
    return getOriginal().getDisplayValue();
  }

  public int physicalToDisplay(int offset) {
    return getOriginal().physicalToDisplay(offset);
  }

  public int displayToPhysical(int offset) {
    return getOriginal().displayToPhysical(offset);
  }

  public TextRange getValueTextRange() {
    return getOriginal().getValueTextRange();
  }

  public boolean isNamespaceDeclaration() {
    return getOriginal().isNamespaceDeclaration();
  }

  @Nullable
  public XmlAttributeDescriptor getDescriptor() {
    return getOriginal().getDescriptor();
  }

  @Nullable
  public XmlAttributeValue getValueElement() {
    return getOriginal().getValueElement();
  }

  public void setValue(String value) throws IncorrectOperationException {
    throw new UnsupportedOperationException("Can't modify included elements");
  }
}

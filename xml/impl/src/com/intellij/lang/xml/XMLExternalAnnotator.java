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
package com.intellij.lang.xml;

import com.intellij.codeInsight.daemon.IdeValidationHost;
import com.intellij.codeInsight.daemon.Validator;
import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.annotation.Annotation;
import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.xml.XmlDocument;
import com.intellij.psi.xml.XmlFile;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.xml.XmlNSDescriptor;
import com.intellij.xml.util.XmlTagUtil;
import org.jetbrains.annotations.NotNull;

/**
 * @author ven
 */
public class XMLExternalAnnotator extends ExternalAnnotator {

  public void annotate(PsiFile file, AnnotationHolder holder) {
    if (!(file instanceof XmlFile)) return;
    final XmlDocument document = ((XmlFile)file).getDocument();
    if (document == null) return;
    XmlTag rootTag = document.getRootTag();
    XmlNSDescriptor nsDescriptor = rootTag == null ? null : rootTag.getNSDescriptor(rootTag.getNamespace(), false);

    if (nsDescriptor instanceof Validator) {
        //noinspection unchecked
        ((Validator<XmlDocument>)nsDescriptor).validate(document, new MyHost(holder));
    }
  }

  private static final Validator.ValidationHost.ErrorType[] types = Validator.ValidationHost.ErrorType.values();

  private static void appendFixes(final Annotation annotation, final IntentionAction... actions) {
    if (actions != null) {
      for(IntentionAction action:actions) annotation.registerFix(action);
    }
  }

  private static class MyHost implements IdeValidationHost {

    private final AnnotationHolder myHolder;

    public MyHost(AnnotationHolder holder) {

      myHolder = holder;
    }

    public void addMessage(PsiElement context, String message, int type) {
      addMessage(context, message, types[type]);
    }

    @Override
    public void addMessage(PsiElement context, String message, @NotNull ErrorType type) {
      addMessageWithFixes(context, message, type);
    }

    public void addMessageWithFixes(final PsiElement context, final String message, @NotNull final ErrorType type, final IntentionAction... fixes) {
      if (message != null && message.length() > 0) {
        if (context instanceof XmlTag) {
          addMessagesForTag((XmlTag)context, message, type, fixes);
        }
        else {
          if (type == Validator.ValidationHost.ErrorType.ERROR) {
            appendFixes(myHolder.createErrorAnnotation(context, message), fixes);
          } else {
            appendFixes(myHolder.createWarningAnnotation(context, message), fixes);
          }
        }
      }
    }

    private void addMessagesForTag(XmlTag tag, String message, ErrorType type, IntentionAction... actions) {
      XmlToken childByRole = XmlTagUtil.getStartTagNameElement(tag);

      addMessagesForTreeChild(childByRole, type, message, actions);

      childByRole = XmlTagUtil.getEndTagNameElement(tag);
      addMessagesForTreeChild(childByRole, type, message, actions);
    }

    private void addMessagesForTreeChild(final XmlToken childByRole, final ErrorType type, final String message, IntentionAction... actions) {
      if (childByRole != null) {
        Annotation annotation;
        if (type == ErrorType.ERROR) {
          annotation = myHolder.createErrorAnnotation(childByRole, message);
        }
        else {
          annotation = myHolder.createWarningAnnotation(childByRole, message);
        }

        appendFixes(annotation, actions);
      }
    }
  }
}

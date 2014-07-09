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

package com.intellij.psi.impl;

import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.UserDataHolderEx;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.NotNull;

public class TextBlock {
  private static final Key<TextBlock> KEY_TEXT_BLOCK = Key.create("KEY_TEXT_BLOCK");
  @SuppressWarnings({"UnusedDeclaration"})
  private Document myDocument; // Will hold a document on a hard reference until there's uncommitted PSI for this document.

  private int myStartOffset = -1;
  private int myTextEndOffset = -1;
  private int myPsiEndOffset = -1;
  private boolean myIsLocked = false;

  public boolean isEmpty() {
    return myStartOffset == -1;
  }

  public void clear() {
    myStartOffset = -1;
    myDocument = null;
  }

  public int getStartOffset() {
    return myStartOffset;
  }

  public int getTextEndOffset() {
    return myTextEndOffset;
  }

  private void lock() {
    myIsLocked = true;
  }

  private void unlock() {
    myIsLocked = false;
  }

  public boolean isLocked() {
    return myIsLocked;
  }

  public int getPsiEndOffset() {
    return myPsiEndOffset;
  }

  public void documentChanged(DocumentEvent e) {
    myDocument = e.getDocument();

    assert !myIsLocked;

    final int offset = e.getOffset();
    if (isEmpty()) {
      myStartOffset = offset;
      myTextEndOffset = offset + e.getNewLength();
      myPsiEndOffset = offset + e.getOldLength();
    }
    else {
      int shift = offset + e.getOldLength() - myTextEndOffset;
      if (shift > 0) {
        myPsiEndOffset += shift;
        myTextEndOffset = offset + e.getNewLength();
      }
      else {
        myTextEndOffset += e.getNewLength() - e.getOldLength();
      }

      myStartOffset = Math.min(myStartOffset, offset);
    }
  }

  public void performAtomically(@NotNull Runnable runnable) {
    assert !isLocked();
    lock();
    try {
      runnable.run();
    }
    finally {
      unlock();
    }
  }

  @NotNull
  public static TextBlock get(@NotNull PsiFile file) {
    TextBlock textBlock = file.getUserData(KEY_TEXT_BLOCK);
    if (textBlock == null){
      textBlock = ((UserDataHolderEx)file).putUserDataIfAbsent(KEY_TEXT_BLOCK, new TextBlock());
    }

    return textBlock;
  }

  @Override
  public String toString() {
    return "TextBlock{" +
           "myStartOffset=" + myStartOffset +
           ", myTextEndOffset=" + myTextEndOffset +
           ", myPsiEndOffset=" + myPsiEndOffset +
           ", myIsLocked=" + myIsLocked +
           '}';
  }
}

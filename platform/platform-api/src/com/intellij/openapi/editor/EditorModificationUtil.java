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
package com.intellij.openapi.editor;

import com.intellij.codeStyle.CodeStyleFacade;
import com.intellij.openapi.editor.actionSystem.EditorActionManager;
import com.intellij.openapi.editor.event.DocumentEvent;
import com.intellij.openapi.editor.event.MockDocumentEvent;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.LineTokenizer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiDocumentManager;
import org.jetbrains.annotations.Nullable;

import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.List;

public class EditorModificationUtil {
  private EditorModificationUtil() {}

  public static void deleteSelectedText(Editor editor) {
    SelectionModel selectionModel = editor.getSelectionModel();
    if (selectionModel.hasBlockSelection()) deleteBlockSelection(editor);
    if(!selectionModel.hasSelection()) return;

    int selectionStart = selectionModel.getSelectionStart();
    int selectionEnd = selectionModel.getSelectionEnd();

    editor.getCaretModel().moveToOffset(selectionStart);
    selectionModel.removeSelection();
    editor.getDocument().deleteString(selectionStart, selectionEnd);
    editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
  }

  public static void deleteBlockSelection(Editor editor) {
    SelectionModel selectionModel = editor.getSelectionModel();
    if (!selectionModel.hasBlockSelection()) return;

    LogicalPosition blockStart = selectionModel.getBlockStart();
    LogicalPosition blockEnd = selectionModel.getBlockEnd();
    if (blockStart == null || blockEnd == null) {
      return;
    }

    int startLine = blockStart.line;
    int endLine = blockEnd.line;

    int[] starts = selectionModel.getBlockSelectionStarts();
    int[] ends = selectionModel.getBlockSelectionEnds();

    for (int i = starts.length - 1; i >= 0; i--) {
      editor.getDocument().deleteString(starts[i], ends[i]);
    }

    editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);

    zeroWidthBlockSelectionAtCaretColumn(editor, startLine, endLine);
  }

  private static void zeroWidthBlockSelectionAtCaretColumn(final Editor editor, final int startLine, final int endLine) {
    int caretColumn = editor.getCaretModel().getLogicalPosition().column;
    editor.getSelectionModel().setBlockSelection(new LogicalPosition(startLine, caretColumn), new LogicalPosition(endLine, caretColumn));
  }

  public static void insertStringAtCaret(Editor editor, String s) {
    insertStringAtCaret(editor, s, false, true);
  }

  public static int insertStringAtCaret(Editor editor, String s, boolean toProcessOverwriteMode, boolean toMoveCaret) {
    final SelectionModel selectionModel = editor.getSelectionModel();
    if (selectionModel.hasSelection()) {
      editor.getCaretModel().moveToOffset(selectionModel.getSelectionStart(), true);
    }

    // There is a possible case that particular soft wraps become hard wraps if the caret is located at soft wrap-introduced virtual
    // space, hence, we need to give editor a chance to react accordingly.
    editor.getSoftWrapModel().beforeDocumentChangeAtCaret();
    int oldOffset = editor.getCaretModel().getOffset();

    String filler = calcStringToFillVirtualSpace(editor);
    if (filler.length() > 0) {
      s = filler + s;
    }

    Document document = editor.getDocument();
    if (editor.isInsertMode() || !toProcessOverwriteMode) {
      if (selectionModel.hasSelection()) {
        oldOffset = selectionModel.getSelectionStart();
        document.replaceString(selectionModel.getSelectionStart(), selectionModel.getSelectionEnd(), s);
      } else {
        document.insertString(oldOffset, s);
      }
    } else {
      deleteSelectedText(editor);
      int lineNumber = editor.getCaretModel().getLogicalPosition().line;
      if (lineNumber >= document.getLineCount()){
        return insertStringAtCaret(editor, s, false, toMoveCaret);
      }

      int endOffset = document.getLineEndOffset(lineNumber);
      document.replaceString(oldOffset, Math.min(endOffset, oldOffset + s.length()), s);
    }

    int offset = oldOffset + s.length();
    if (toMoveCaret){
      editor.getCaretModel().moveToOffset(offset, true);
      editor.getScrollingModel().scrollToCaret(ScrollType.RELATIVE);
      selectionModel.removeSelection();
    }

    return offset;
  }

  @Nullable
  public static TextRange pasteFromClipboard(Editor editor) {
    return pasteFromTransferrable(CopyPasteManager.getInstance().getContents(), editor);
  }

  @Nullable
  public static TextRange pasteFromTransferrable(Transferable content, Editor editor) {
    if (content != null) {
      try {
        String s = getStringContent(content);

        int caretOffset = editor.getCaretModel().getOffset();
        insertStringAtCaret(editor, s, false, true);
        return new TextRange(caretOffset, caretOffset + s.length());
      } catch (Exception exception) {
        editor.getComponent().getToolkit().beep();
      }
    }

    return null;
  }

  private static String getStringContent(final Transferable content) throws UnsupportedFlavorException, IOException {
    RawText raw = RawText.fromTransferable(content);
    String s;
    if (raw != null) {
      s = raw.rawText;
    }
    else {
      s = (String)content.getTransferData(DataFlavor.stringFlavor);
    }

    s = StringUtil.convertLineSeparators(s);
    return s;
  }

  public static void pasteFromClipboardAsBlock(Editor editor) {
    pasteTransferableAsBlock(editor, null);
  }

  public static void pasteTransferableAsBlock(Editor editor, @Nullable Transferable content) {
    if (content == null) {
      content = CopyPasteManager.getInstance().getContents();
    }

    if (content != null) {
      try {
        int caretLine = editor.getCaretModel().getLogicalPosition().line;
        int originalCaretLine = caretLine;

        int selectedLinesCount = 0;
        final SelectionModel selectionModel = editor.getSelectionModel();
        if (selectionModel.hasBlockSelection()) {
          final LogicalPosition start = selectionModel.getBlockStart();
          final LogicalPosition end = selectionModel.getBlockEnd();
          assert start != null;
          assert end != null;
          LogicalPosition caret = new LogicalPosition(Math.min(start.line, end.line), Math.min(start.column, end.column));
          selectedLinesCount = Math.abs(end.line - start.line);
          caretLine = caret.line;

          deleteSelectedText(editor);
          editor.getCaretModel().moveToLogicalPosition(caret);
        }

        LogicalPosition caretToRestore = editor.getCaretModel().getLogicalPosition();
        String s = getStringContent(content);

        String[] lines = LineTokenizer.tokenize(s.toCharArray(), false);
        if (lines.length > 1 || selectedLinesCount == 0) {
          int longestLineLength = 0;
          for (String line : lines) {
            longestLineLength = Math.max(longestLineLength, line.length());
            insertStringAtCaret(editor, line, false, true);
            editor.getCaretModel().moveCaretRelatively(-line.length(), 1, false, false, true);
          }
          caretToRestore = new LogicalPosition(originalCaretLine, caretToRestore.column + longestLineLength);
        }
        else {
          for (int i = 0; i <= selectedLinesCount; i++) {
            insertStringAtCaret(editor, s, false, true);
            editor.getCaretModel().moveCaretRelatively(-s.length(), 1, false, false, true);
          }
          caretToRestore = new LogicalPosition(originalCaretLine, caretToRestore.column + s.length());
        }
        editor.getCaretModel().moveToLogicalPosition(caretToRestore);
        zeroWidthBlockSelectionAtCaretColumn(editor, caretLine, caretLine + selectedLinesCount);
      } catch (Exception exception) {
        editor.getComponent().getToolkit().beep();
      }
    }
  }

  /**
   * Calculates difference in columns between current editor caret position and end of the logical line fragment displayed
   * on a current visual line.
   *
   * @param editor    target editor
   * @return          difference in columns between current editor caret position and end of the logical line fragment displayed
   *                  on a current visual line
   */
  public static int calcAfterLineEnd(Editor editor) {
    Document document = editor.getDocument();
    CaretModel caretModel = editor.getCaretModel();
    LogicalPosition logicalPosition = caretModel.getLogicalPosition();
    int lineNumber = logicalPosition.line;
    int columnNumber = logicalPosition.column;
    if (lineNumber >= document.getLineCount()) {
      return columnNumber;
    }

    int caretOffset = caretModel.getOffset();
    int anchorLineEndOffset = document.getLineEndOffset(lineNumber);
    List<? extends SoftWrap> softWraps = editor.getSoftWrapModel().getSoftWrapsForLine(logicalPosition.line);
    for (SoftWrap softWrap : softWraps) {
      if (!editor.getSoftWrapModel().isVisible(softWrap)) {
        continue;
      }

      int softWrapOffset = softWrap.getStart();
      if (softWrapOffset == caretOffset) {
        // There are two possible situations:
        //     *) caret is located on a visual line before soft wrap-introduced line feed;
        //     *) caret is located on a visual line after soft wrap-introduced line feed;
        VisualPosition position = editor.offsetToVisualPosition(caretOffset - 1);
        VisualPosition visualCaret = caretModel.getVisualPosition();
        if (position.line == visualCaret.line) {
          return visualCaret.column - position.column - 1;
        }
      }
      if (softWrapOffset > caretOffset) {
        anchorLineEndOffset = softWrapOffset;
        break;
      }

      // Same offset corresponds to all soft wrap-introduced symbols, however, current method should behave differently in
      // situations when the caret is located just before the soft wrap and at the next visual line.
      if (softWrapOffset == caretOffset) {
        boolean visuallyBeforeSoftWrap = caretModel.getVisualPosition().line < editor.offsetToVisualPosition(caretOffset).line;
        if (visuallyBeforeSoftWrap) {
          anchorLineEndOffset = softWrapOffset;
          break;
        }
      }
    }

    int lineEndColumnNumber = editor.offsetToLogicalPosition(anchorLineEndOffset).column;
    return columnNumber - lineEndColumnNumber;
  }

  public static String calcStringToFillVirtualSpace(Editor editor) {
    int afterLineEnd = calcAfterLineEnd(editor);
    if (afterLineEnd > 0) {
      return calcStringToFillVirtualSpace(editor, afterLineEnd);
    }

    return "";
  }

  public static String calcStringToFillVirtualSpace(Editor editor, int afterLineEnd) {
    final Project project = editor.getProject();
    StringBuilder buf = new StringBuilder();
    final Document doc = editor.getDocument();
    final int caretOffset = editor.getCaretModel().getOffset();
    boolean atLineStart = caretOffset >= doc.getTextLength() || doc.getLineStartOffset(doc.getLineNumber(caretOffset)) == caretOffset;
    if (atLineStart && project != null) {
      int offset = editor.getCaretModel().getOffset();
      PsiDocumentManager.getInstance(project).commitDocument(doc); // Sync document and PSI before formatting.
      String properIndent = offset >= doc.getTextLength() ? "" : CodeStyleFacade.getInstance(project).getLineIndent(doc, offset);
      if (properIndent != null) {
        int tabSize = editor.getSettings().getTabSize(project);
        for (int i = 0; i < properIndent.length(); i++) {
          if (properIndent.charAt(i) == ' ') {
            afterLineEnd--;
          }
          else if (properIndent.charAt(i) == '\t') {
            if (afterLineEnd < tabSize) {
              break;
            }
            afterLineEnd -= tabSize;
          }
          buf.append(properIndent.charAt(i));
          if (afterLineEnd == 0) break;
        }
      }
    }

    for (int i = 0; i < afterLineEnd; i++) {
      buf.append(' ');
    }

    return buf.toString();
  }

  public static void typeInStringAtCaretHonorBlockSelection(final Editor editor, final String str, final boolean toProcessOverwriteMode)
    throws ReadOnlyFragmentModificationException
  {
    Document doc = editor.getDocument();
    final SelectionModel selectionModel = editor.getSelectionModel();
    if (selectionModel.hasBlockSelection()) {
      RangeMarker guard = selectionModel.getBlockSelectionGuard();
      if (guard != null) {
        DocumentEvent evt = new MockDocumentEvent(doc, editor.getCaretModel().getOffset());
        ReadOnlyFragmentModificationException e = new ReadOnlyFragmentModificationException(evt, guard);
        EditorActionManager.getInstance().getReadonlyFragmentModificationHandler(doc).handle(e);
      }
      else {
        final LogicalPosition start = selectionModel.getBlockStart();
        final LogicalPosition end = selectionModel.getBlockEnd();
        assert start != null;
        assert end != null;

        int column = Math.min(start.column, end.column);
        int startLine = Math.min(start.line, end.line);
        int endLine = Math.max(start.line, end.line);
        deleteBlockSelection(editor);
        for (int i = startLine; i <= endLine; i++) {
          editor.getCaretModel().moveToLogicalPosition(new LogicalPosition(i, column));
          insertStringAtCaret(editor, str, toProcessOverwriteMode, true);
        }
        selectionModel.setBlockSelection(new LogicalPosition(startLine, column + str.length()),
                                         new LogicalPosition(endLine, column + str.length()));
      }
    }
    else {
      insertStringAtCaret(editor, str, toProcessOverwriteMode, true);
    }
  }
}

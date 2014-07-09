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
package com.intellij.openapi.editor.impl;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.editor.CaretModel;
import com.intellij.openapi.editor.FoldRegion;
import com.intellij.openapi.editor.RangeMarker;
import com.intellij.openapi.editor.colors.EditorColors;
import com.intellij.openapi.editor.ex.*;
import com.intellij.openapi.editor.highlighter.HighlighterIterator;
import com.intellij.openapi.editor.markup.*;
import com.intellij.openapi.util.Comparing;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.*;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

public final class IterationState {
  private static final Logger LOG = Logger.getInstance("#com.intellij.openapi.editor.impl.IterationState");
  
  private static final Comparator<RangeHighlighterEx> HIGHLIGHTER_COMPARATOR = new Comparator<RangeHighlighterEx>() {
    @Override
    public int compare(RangeHighlighterEx o1, RangeHighlighterEx o2) {
      final int result = LayerComparator.INSTANCE.compare(o1, o2);
      if (result != 0) {
        return result;
      }
      
      // There is a possible case when more than one highlighter target the same region (e.g. 'identifier under caret' and 'identifier').
      // We want to prefer the one that defines foreground color to the one that doesn't define (has either fore- or background colors
      // while the other one has only foreground color). See IDEA-85697 for concrete example.
      final TextAttributes a1 = o1.getTextAttributes();
      final TextAttributes a2 = o2.getTextAttributes();
      if (a1 == null ^ a2 == null) {
        return a1 == null ? 1 : -1;
      }

      if (a1 == null) {
        return result;
      }
      
      final Color fore1 = a1.getForegroundColor();
      final Color fore2 = a2.getForegroundColor();
      if (fore1 == null ^ fore2 == null) {
        return fore1 == null ? 1 : -1;
      }

      final Color back1 = a1.getBackgroundColor();
      final Color back2 = a2.getBackgroundColor();
      if (back1 == null ^ back2 == null) {
        return back1 == null ? 1 : -1;
      }

      return result;
    }
  };
  
  private final TextAttributes myMergedAttributes = new TextAttributes();

  private final HighlighterIterator myHighlighterIterator;
  private final HighlighterSweep myView;
  private final HighlighterSweep myDoc;

  private int myStartOffset;

  private int myEndOffset;
  private final int myEnd;

  private final int mySelectionStart;

  private final int mySelectionEnd;
  private final List<RangeHighlighterEx> myCurrentHighlighters = new ArrayList<RangeHighlighterEx>();

  private final FoldingModelEx myFoldingModel;

  private final boolean hasSelection;
  private FoldRegion myCurrentFold = null;
  private final TextAttributes myFoldTextAttributes;
  private final TextAttributes mySelectionAttributes;
  private final TextAttributes myCaretRowAttributes;
  private final Color myDefaultBackground;
  private final Color myDefaultForeground;
  private final int myCaretRowStart;
  private final int myCaretRowEnd;
  private final List<TextAttributes> myCachedAttributesList = new ArrayList<TextAttributes>(5);
  private final DocumentEx myDocument;
  private final EditorEx myEditor;
  private final Color myReadOnlyColor;

  /**
   * You MUST CALL {@link #dispose()} afterwards
   */
  public IterationState(@NotNull EditorEx editor, int start, int end, boolean useCaretAndSelection) {
    ApplicationManager.getApplication().assertReadAccessAllowed();
    myDocument = editor.getDocument();
    myStartOffset = start;

    myEnd = end;
    myEditor = editor;

    LOG.assertTrue(myStartOffset <= myEnd);
    myHighlighterIterator = editor.getHighlighter().createIterator(start);

    hasSelection = useCaretAndSelection && editor.getSelectionModel().hasSelection();
    mySelectionStart = hasSelection ? editor.getSelectionModel().getSelectionStart() : -1;
    mySelectionEnd = hasSelection ? editor.getSelectionModel().getSelectionEnd() : -1;

    myFoldingModel = editor.getFoldingModel();
    myFoldTextAttributes = myFoldingModel.getPlaceholderAttributes();
    mySelectionAttributes = editor.getSelectionModel().getTextAttributes();

    myReadOnlyColor = myEditor.getColorsScheme().getColor(EditorColors.READONLY_FRAGMENT_BACKGROUND_COLOR);

    CaretModel caretModel = editor.getCaretModel();
    myCaretRowAttributes = editor.isRendererMode() ? null : caretModel.getTextAttributes();
    myDefaultBackground = editor.getColorsScheme().getDefaultBackground();
    myDefaultForeground = editor.getColorsScheme().getDefaultForeground();

    myCaretRowStart = caretModel.getVisualLineStart();
    myCaretRowEnd = caretModel.getVisualLineEnd();

    MarkupModelEx editorMarkup = (MarkupModelEx)editor.getMarkupModel();
    myView = new HighlighterSweep(editorMarkup, start, myEnd);

    final MarkupModelEx docMarkup = (MarkupModelEx)DocumentMarkupModel.forDocument(editor.getDocument(), editor.getProject(), true);
    myDoc = new HighlighterSweep(docMarkup, start, myEnd);

    myEndOffset = myStartOffset;

    advance();
  }

  public void dispose() {
    myView.dispose();
    myDoc.dispose();
  }

  private class HighlighterSweep {
    private RangeHighlighterEx myNextHighlighter;
    private final PushBackIterator<RangeHighlighterEx> myIterator;
    private final DisposableIterator<RangeHighlighterEx> myDisposableIterator;

    private HighlighterSweep(@NotNull MarkupModelEx markupModel, int start, int end) {
      myDisposableIterator = markupModel.overlappingIterator(start, end);
      myIterator = new PushBackIterator<RangeHighlighterEx>(myDisposableIterator);
      int skipped = 0;
      while (myIterator.hasNext()) {
        RangeHighlighterEx highlighter = myIterator.next();
        if (!skipHighlighter(highlighter)) {
          myNextHighlighter = highlighter;
          break;
        }
        skipped++;
      }
      if (skipped > Math.min(1000, markupModel.getDocument().getTextLength())) {
        int i = 0;
        //LOG.error("Inefficient iteration, limit the number of highlighters to iterate");
      }
    }

    private void advance() {
      if (myNextHighlighter != null) {
        if (myNextHighlighter.getAffectedAreaStartOffset() <= myStartOffset) {
          myCurrentHighlighters.add(myNextHighlighter);
          myNextHighlighter = null;
        }
        
        // There is a possible case that there are two highlighters mapped to offset of the first non-white space symbol
        // on a line. The second one may have HighlighterTargetArea.LINES_IN_RANGE area, so, we should use it for indent
        // background processing (that is the case for the active debugger line that starts with highlighted brace/bracket).
        // So, we check if it's worth to use next highlighter here.
        else if (myIterator.hasNext()) {
          final RangeHighlighterEx lookAhead = myIterator.next();
          if (lookAhead.getAffectedAreaStartOffset() <= myStartOffset) {
            myCurrentHighlighters.add(lookAhead);
          }
          else {
            myIterator.pushBack(lookAhead);
          }
        }
      }

      while (myNextHighlighter == null && myIterator.hasNext()) {
        RangeHighlighterEx highlighter = myIterator.next();
        if (!skipHighlighter(highlighter)) {
          if (highlighter.getAffectedAreaStartOffset() > myStartOffset) {
            myNextHighlighter = highlighter;
          }
          else {
            myCurrentHighlighters.add(highlighter);
          }
        }
      }
    }

    private int getMinSegmentHighlighterEnd() {
      if (myNextHighlighter != null) {
        return myNextHighlighter.getAffectedAreaStartOffset();
      }
      return Integer.MAX_VALUE;
    }

    public void dispose() {
      myDisposableIterator.dispose();
    }
  }

  private boolean skipHighlighter(@NotNull RangeHighlighterEx highlighter) {
    if (!highlighter.isValid() || highlighter.isAfterEndOfLine() || highlighter.getTextAttributes() == null) return true;
    final FoldRegion region = myFoldingModel.getCollapsedRegionAtOffset(highlighter.getAffectedAreaStartOffset());
    if (region != null && region == myFoldingModel.getCollapsedRegionAtOffset(highlighter.getAffectedAreaEndOffset())) return true;
    return !highlighter.getEditorFilter().avaliableIn(myEditor);
  }

  public void advance() {
    myStartOffset = myEndOffset;
    advanceSegmentHighlighters();

    myCurrentFold = myFoldingModel.fetchOutermost(myStartOffset);
    if (myCurrentFold != null) {
      myEndOffset = myCurrentFold.getEndOffset();
    }
    else {
      myEndOffset = Math.min(getHighlighterEnd(myStartOffset), getSelectionEnd(myStartOffset));
      myEndOffset = Math.min(myEndOffset, getMinSegmentHighlightersEnd());
      myEndOffset = Math.min(myEndOffset, getFoldRangesEnd(myStartOffset));
      myEndOffset = Math.min(myEndOffset, getCaretEnd(myStartOffset));
      myEndOffset = Math.min(myEndOffset, getGuardedBlockEnd(myStartOffset));
    }

    reinit();
  }

  private int getHighlighterEnd(int start) {
    while (!myHighlighterIterator.atEnd()) {
      int end = myHighlighterIterator.getEnd();
      if (end > start) {
        return end;
      }
      myHighlighterIterator.advance();
    }
    return myEnd;
  }

  private int getCaretEnd(int start) {
    if (myCaretRowStart > start) {
      return myCaretRowStart;
    }

    if (myCaretRowEnd > start) {
      return myCaretRowEnd;
    }

    return myEnd;
  }

  private int getGuardedBlockEnd(int start) {
    List<RangeMarker> blocks = myDocument.getGuardedBlocks();
    int min = myEnd;
    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < blocks.size(); i++) {
      RangeMarker block = blocks.get(i);
      if (block.getStartOffset() > start) {
        min = Math.min(min, block.getStartOffset());
      }
      else if (block.getEndOffset() > start) {
        min = Math.min(min, block.getEndOffset());
      }
    }
    return min;
  }

  private int getSelectionEnd(int start) {
    if (!hasSelection) {
      return myEnd;
    }
    if (mySelectionStart > start) {
      return mySelectionStart;
    }
    if (mySelectionEnd > start) {
      return mySelectionEnd;
    }
    return myEnd;
  }

  private void advanceSegmentHighlighters() {
    myDoc.advance();
    myView.advance();

    for (int i = myCurrentHighlighters.size() - 1; i >= 0; i--) {
      RangeHighlighterEx highlighter = myCurrentHighlighters.get(i);
      if (highlighter.getAffectedAreaEndOffset() <= myStartOffset) {
        myCurrentHighlighters.remove(i);
      }
    }
  }

  private int getFoldRangesEnd(int startOffset) {
    int end = myEnd;
    FoldRegion[] topLevelCollapsed = myFoldingModel.fetchTopLevel();
    if (topLevelCollapsed != null) {
      for (int i = myFoldingModel.getLastCollapsedRegionBefore(startOffset) + 1;
           i >= 0 && i < topLevelCollapsed.length;
           i++) {
        FoldRegion range = topLevelCollapsed[i];
        if (!range.isValid()) continue;

        int rangeEnd = range.getStartOffset();
        if (rangeEnd > startOffset) {
          if (rangeEnd < end) {
            end = rangeEnd;
          }
          else {
            break;
          }
        }
      }
    }

    return end;
  }

  private int getMinSegmentHighlightersEnd() {
    int end = myEnd;

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < myCurrentHighlighters.size(); i++) {
      RangeHighlighterEx highlighter = myCurrentHighlighters.get(i);
      if (highlighter.getAffectedAreaEndOffset() < end) {
        end = highlighter.getAffectedAreaEndOffset();
      }
    }

    end = Math.min(end, myDoc.getMinSegmentHighlighterEnd());
    end = Math.min(end, myView.getMinSegmentHighlighterEnd());

    return end;
  }

  private void reinit() {
    if (myHighlighterIterator.atEnd()) {
      return;
    }

    boolean isInSelection = hasSelection && myStartOffset >= mySelectionStart && myStartOffset < mySelectionEnd;
    boolean isInCaretRow = myStartOffset >= myCaretRowStart && myStartOffset < myCaretRowEnd;
    boolean isInGuardedBlock = myDocument.getOffsetGuard(myStartOffset) != null;

    TextAttributes syntax = myHighlighterIterator.getTextAttributes();

    TextAttributes selection = isInSelection ? mySelectionAttributes : null;
    TextAttributes caret = isInCaretRow ? myCaretRowAttributes : null;
    TextAttributes fold = myCurrentFold != null ? myFoldTextAttributes : null;
    TextAttributes guard = isInGuardedBlock
                           ? new TextAttributes(null, myReadOnlyColor, null, EffectType.BOXED, Font.PLAIN)
                           : null;

    final int size = myCurrentHighlighters.size();
    if (size > 1) {
      ContainerUtil.quickSort(myCurrentHighlighters, HIGHLIGHTER_COMPARATOR);
    }

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < size; i++) {
      RangeHighlighterEx highlighter = myCurrentHighlighters.get(i);
      if (highlighter.getTextAttributes() == TextAttributes.ERASE_MARKER) {
        syntax = null;
      }
    }

    List<TextAttributes> cachedAttributes = myCachedAttributesList;
    cachedAttributes.clear();

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < size; i++) {
      RangeHighlighterEx highlighter = myCurrentHighlighters.get(i);
      if (selection != null && highlighter.getLayer() < HighlighterLayer.SELECTION) {
        cachedAttributes.add(selection);
        selection = null;
      }

      if (syntax != null && highlighter.getLayer() < HighlighterLayer.SYNTAX) {
        if (fold != null) {
          cachedAttributes.add(fold);
          fold = null;
        }

        cachedAttributes.add(syntax);
        syntax = null;
      }

      if (guard != null && highlighter.getLayer() < HighlighterLayer.GUARDED_BLOCKS) {
        cachedAttributes.add(guard);
        guard = null;
      }

      if (caret != null && highlighter.getLayer() < HighlighterLayer.CARET_ROW) {
        cachedAttributes.add(caret);
        caret = null;
      }

      TextAttributes textAttributes = highlighter.getTextAttributes();
      if (textAttributes != null && textAttributes != TextAttributes.ERASE_MARKER) {
        cachedAttributes.add(textAttributes);
      }
    }

    if (selection != null) cachedAttributes.add(selection);
    if (fold != null) cachedAttributes.add(fold);
    if (guard != null) cachedAttributes.add(guard);
    if (caret != null) cachedAttributes.add(caret);
    if (syntax != null) cachedAttributes.add(syntax);

    Color fore = null;
    Color back = isInGuardedBlock ? myReadOnlyColor : null;
    Color effect = null;
    EffectType effectType = null;
    int fontType = 0;

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < cachedAttributes.size(); i++) {
      TextAttributes attrs = cachedAttributes.get(i);

      if (fore == null) {
        fore = ifDiffers(attrs.getForegroundColor(), myDefaultForeground);
      }

      if (back == null) {
        back = ifDiffers(attrs.getBackgroundColor(), myDefaultBackground);
      }

      if (fontType == Font.PLAIN) {
        fontType = attrs.getFontType();
      }

      if (effect == null) {
        effect = attrs.getEffectColor();
        effectType = attrs.getEffectType();
      }
    }

    if (fore == null) fore = myDefaultForeground;
    if (back == null) back = myDefaultBackground;
    if (effectType == null) effectType = EffectType.BOXED;

    myMergedAttributes.setAttributes(fore, back, effect, null, effectType, fontType);
  }

  @Nullable
  private static Color ifDiffers(final Color c1, final Color c2) {
    return Comparing.equal(c1, c2) ? null : c1;
  }

  public boolean atEnd() {
    return myStartOffset >= myEnd;
  }


  public int getStartOffset() {
    return myStartOffset;
  }

  public int getEndOffset() {
    return myEndOffset;
  }

  @NotNull
  public TextAttributes getMergedAttributes() {
    return myMergedAttributes;
  }

  public FoldRegion getCurrentFold() {
    return myCurrentFold;
  }

  @Nullable
  public Color getPastFileEndBackground() {
    boolean isInCaretRow = myEditor.getCaretModel().getLogicalPosition().line >= myDocument.getLineCount() - 1;

    Color caret = isInCaretRow && myCaretRowAttributes != null ? myCaretRowAttributes.getBackgroundColor() : null;

    ContainerUtil.quickSort(myCurrentHighlighters, LayerComparator.INSTANCE);

    //noinspection ForLoopReplaceableByForEach
    for (int i = 0; i < myCurrentHighlighters.size(); i++) {
      RangeHighlighterEx highlighter = myCurrentHighlighters.get(i);
      if (caret != null && highlighter.getLayer() < HighlighterLayer.CARET_ROW) {
        return caret;
      }

      if (highlighter.getTargetArea() != HighlighterTargetArea.LINES_IN_RANGE
          || myDocument.getLineNumber(highlighter.getEndOffset()) < myDocument.getLineCount() - 1) {
        continue;
      }

      TextAttributes textAttributes = highlighter.getTextAttributes();
      if (textAttributes != null) {
        Color backgroundColor = textAttributes.getBackgroundColor();
        if (backgroundColor != null) return backgroundColor;
      }
    }

    return caret;
  }

  private static class LayerComparator implements Comparator<RangeHighlighterEx> {
    private static final LayerComparator INSTANCE = new LayerComparator();
    @Override
    public int compare(RangeHighlighterEx o1, RangeHighlighterEx o2) {
      int layerDiff = o2.getLayer() - o1.getLayer();
      if (layerDiff != 0) {
        return layerDiff;
      }
      // prefer more specific region
      int o1Length = o1.getEndOffset() - o1.getStartOffset();
      int o2Length = o2.getEndOffset() - o2.getStartOffset();
      return o1Length - o2Length;
    }
  }
  
  private static class PushBackIterator<T> implements Iterator<T> {
    
    private final Iterator<T> myDelegate;
    private T myPushedBack;

    PushBackIterator(Iterator<T> delegate) {
      myDelegate = delegate;
    }

    @Override
    public boolean hasNext() {
      return myPushedBack != null || myDelegate.hasNext();
    }

    @Override
    public T next() {
      if (myPushedBack != null) {
        T result = myPushedBack;
        myPushedBack = null;
        return result;
      }
      return myDelegate.next();
    }

    @Override
    public void remove() {
      if (myPushedBack == null) {
        myDelegate.remove();
      }
      else {
        myPushedBack = null;
      }
    }

    public void pushBack(T element) {
      assert myPushedBack == null : "Pushed already: " + myPushedBack;
      myPushedBack = element;
    }
  }
}

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
package com.intellij.ui.components;

import com.intellij.openapi.wm.IdeGlassPane;
import com.intellij.ui.IdeBorderFactory;
import com.intellij.util.ArrayUtil;
import com.intellij.util.ui.ButtonlessScrollBarUI;

import javax.swing.*;
import javax.swing.border.LineBorder;
import javax.swing.plaf.ScrollBarUI;
import javax.swing.plaf.ScrollPaneUI;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.lang.reflect.Method;

public class JBScrollPane extends JScrollPane {

  private int myViewportBorderWidth = -1;

  public JBScrollPane(int viewportWidth) {
    myViewportBorderWidth = viewportWidth;
    updateViewportBorder();
  }

  public JBScrollPane() {
    setupCorners();
  }

  public JBScrollPane(Component view) {
    super(view);
    setupCorners();
  }

  public JBScrollPane(int vsbPolicy, int hsbPolicy) {
    super(vsbPolicy, hsbPolicy);
    setupCorners();
  }

  public JBScrollPane(Component view, int vsbPolicy, int hsbPolicy) {
    super(view, vsbPolicy, hsbPolicy);
    setupCorners();
  }

  public void setupCorners() {
    setBorder(IdeBorderFactory.createBorder());
    setCorner(UPPER_RIGHT_CORNER, new Corner(UPPER_RIGHT_CORNER));
    setCorner(UPPER_LEFT_CORNER, new Corner(UPPER_LEFT_CORNER));
    setCorner(LOWER_RIGHT_CORNER, new Corner(LOWER_RIGHT_CORNER));
    setCorner(LOWER_LEFT_CORNER, new Corner(LOWER_LEFT_CORNER));
  }

  public void setUI(ScrollPaneUI ui) {
    super.setUI(ui);
    updateViewportBorder();
  }

  private void updateViewportBorder() {
    setViewportBorder(new ViewportBorder(myViewportBorderWidth >= 0 ? myViewportBorderWidth : 1));
  }

  public static ViewportBorder createIndentBorder() {
    return new ViewportBorder(2);
  }

  @Override
  public JScrollBar createVerticalScrollBar() {
    return new MyScrollBar(Adjustable.VERTICAL);
  }

  @Override
  public JScrollBar createHorizontalScrollBar() {
    return new MyScrollBar(Adjustable.HORIZONTAL);
  }

  @Override
  protected JViewport createViewport() {
    return new JBViewport();
  }

  private class MyScrollBar extends ScrollBar implements IdeGlassPane.TopComponent {
    public MyScrollBar(int orientation) {
      super(orientation);
    }

    @Override
    public void updateUI() {
      setUI(ButtonlessScrollBarUI.createNormal());
    }

    @Override
    public boolean canBePreprocessed(MouseEvent e) {
      return JBScrollPane.canBePreprocessed(e, this);
    }
  }


  public static boolean canBePreprocessed(MouseEvent e, JScrollBar bar) {
    if (e.getID() == MouseEvent.MOUSE_MOVED || e.getID() == MouseEvent.MOUSE_PRESSED) {
      ScrollBarUI ui = bar.getUI();
      if (ui instanceof BasicScrollBarUI) {
        BasicScrollBarUI bui = (BasicScrollBarUI)ui;
        try {
          Method m = BasicScrollBarUI.class.getDeclaredMethod("getThumbBounds", ArrayUtil.EMPTY_CLASS_ARRAY);
          m.setAccessible(true);
          Rectangle rect = (Rectangle)m.invoke(bui);
          Point point = SwingUtilities.convertPoint(e.getComponent(), e.getX(), e.getY(), bar);
          return !rect.contains(point);
        }
        catch (Exception e1) {
          return true;
        }
      }
    }
    return true;
  }

  private static class Corner extends JPanel {
    private final String myPos;

    public Corner(String pos) {
      myPos = pos;
    }

    @Override
    protected void paintComponent(Graphics g) {
      g.setColor(ButtonlessScrollBarUI.getTrackBackground());
      g.fillRect(0, 0, getWidth(), getHeight());

      g.setColor(ButtonlessScrollBarUI.getTrackBorderColor());

      int x2 = getWidth() - 1;
      int y2 = getHeight() - 1;

      if (myPos == UPPER_LEFT_CORNER || myPos == UPPER_RIGHT_CORNER) {
        g.drawLine(0, y2, x2, y2);
      }
      if (myPos == LOWER_LEFT_CORNER || myPos == LOWER_RIGHT_CORNER) {
        g.drawLine(0, 0, x2, 0);
      }

      if (myPos == UPPER_LEFT_CORNER || myPos == LOWER_LEFT_CORNER) {
        g.drawLine(x2, 0, x2, y2);
      }

      if (myPos == UPPER_RIGHT_CORNER || myPos == LOWER_RIGHT_CORNER) {
        g.drawLine(0, 0, 0, y2);
      }
    }
  }

  private static class ViewportBorder extends LineBorder {
    public ViewportBorder(int thickness) {
      super(null, thickness);
    }

    @Override
    public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
      updateColor(c);
      super.paintBorder(c, g, x, y, width, height);
    }

    private void updateColor(Component c) {
      if (!(c instanceof JScrollPane)) return;

      JViewport vp = ((JScrollPane)c).getViewport();
      if (vp == null) return;

      Component view = vp.getView();
      if (view == null) return;

      lineColor = view.getBackground();
    }
  }
}

/*
 * Copyright 2000-2013 JetBrains s.r.o.
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
package com.intellij.ui.mac;

import com.apple.eawt.*;
import com.intellij.Patches;
import com.intellij.ide.ui.UISettings;
import com.intellij.ide.ui.UISettingsListener;
import com.intellij.openapi.application.ex.ApplicationInfoEx;
import com.intellij.openapi.application.impl.ApplicationInfoImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.BuildNumber;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.wm.impl.IdeFrameDecorator;
import com.intellij.openapi.wm.impl.IdeFrameImpl;
import com.intellij.ui.CustomProtocolHandler;
import com.intellij.ui.mac.foundation.Foundation;
import com.intellij.ui.mac.foundation.ID;
import com.intellij.ui.mac.foundation.MacUtil;
import com.intellij.util.Function;
import com.sun.jna.Callback;
import com.sun.jna.Pointer;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicInteger;

import static com.intellij.ui.mac.foundation.Foundation.invoke;

/**
 * User: spLeaner
 */
public class MacMainFrameDecorator extends IdeFrameDecorator implements UISettingsListener {
  private static final Logger LOG = Logger.getInstance("#com.intellij.ui.mac.MacMainFrameDecorator");
  private final static boolean ORACLE_BUG_ID_8003173 = SystemInfo.isJavaVersionAtLeast("1.7");

  // Fullscreen listener delivers event too late,
  // so we use method swizzling here
  private final Callback windowWillEnterFullScreenCallBack = new Callback() {
    public void callback(ID self,
                         ID nsNotification)
    {
      enterFullscreen();
      invoke(self, "oldWindowWillEnterFullScreen:", nsNotification);
    }
  };

  private void enterFullscreen() {
    myInFullScreen = true;
    myFrame.storeFullScreenStateIfNeeded(true);
  }

  private final Callback windowWillExitFullScreenCallBack = new Callback() {
    public void callback(ID self,
                         ID nsNotification)
    {
      exitFullscreen();
      invoke(self, "oldWindowWillExitFullScreen:", nsNotification);
    }
  };

  private void exitFullscreen() {
    myInFullScreen = false;
    myFrame.storeFullScreenStateIfNeeded(false);

    JRootPane rootPane = myFrame.getRootPane();
    if (rootPane != null) rootPane.putClientProperty(FULL_SCREEN, null);
  }

  public static final String FULL_SCREEN = "Idea.Is.In.FullScreen.Mode.Now";
  private static boolean HAS_FULLSCREEN_UTILITIES;

  private static Method requestToggleFullScreenMethod;

  static {
    try {
      Class.forName("com.apple.eawt.FullScreenUtilities");
      requestToggleFullScreenMethod = Application.class.getMethod("requestToggleFullScreen", Window.class);
      HAS_FULLSCREEN_UTILITIES = true;
    } catch (Exception e) {
      HAS_FULLSCREEN_UTILITIES = false;
    }
  }
  public static final boolean FULL_SCREEN_AVAILABLE = SystemInfo.isJavaVersionAtLeast("1.6.0_29") && HAS_FULLSCREEN_UTILITIES;

  private static boolean SHOWN = false;

  private static Callback SET_VISIBLE_CALLBACK = new Callback() {
    public void callback(ID caller, ID selector, ID value) {
      SHOWN = value.intValue() == 1;
      SwingUtilities.invokeLater(CURRENT_SETTER);
    }
  };

  private static Callback IS_VISIBLE = new Callback() {
    public boolean callback(ID caller) {
      return SHOWN;
    }
  };

  private static AtomicInteger UNIQUE_COUNTER = new AtomicInteger(0);

  public static final Runnable TOOLBAR_SETTER = new Runnable() {
    @Override
    public void run() {
      final UISettings settings = UISettings.getInstance();
      settings.SHOW_MAIN_TOOLBAR = SHOWN;
      settings.fireUISettingsChanged();
    }
  };

  public static final Runnable NAVBAR_SETTER = new Runnable() {
    @Override
    public void run() {
      final UISettings settings = UISettings.getInstance();
      settings.SHOW_NAVIGATION_BAR = SHOWN;
      settings.fireUISettingsChanged();
    }
  };

  public static final Function<Object, Boolean> NAVBAR_GETTER = new Function<Object, Boolean>() {
    @Override
    public Boolean fun(Object o) {
      return UISettings.getInstance().SHOW_NAVIGATION_BAR;
    }
  };

  public static final Function<Object, Boolean> TOOLBAR_GETTER = new Function<Object, Boolean>() {
    @Override
    public Boolean fun(Object o) {
      return UISettings.getInstance().SHOW_MAIN_TOOLBAR;
    }
  };

  private static Runnable CURRENT_SETTER = null;
  private static Function<Object, Boolean> CURRENT_GETTER = null;
  private static CustomProtocolHandler ourProtocolHandler = null;

  private boolean myInFullScreen;

  public MacMainFrameDecorator(@NotNull final IdeFrameImpl frame, final boolean navBar) {
    super(frame);

    if (CURRENT_SETTER == null) {
      CURRENT_SETTER = navBar ? NAVBAR_SETTER : TOOLBAR_SETTER;
      CURRENT_GETTER = navBar ? NAVBAR_GETTER : TOOLBAR_GETTER;
      SHOWN = CURRENT_GETTER.fun(null);
    }

    UISettings.getInstance().addUISettingsListener(this, this);

    final ID pool = invoke("NSAutoreleasePool", "new");

    //if (ORACLE_BUG_ID_8003173) {
    //  replaceNativeFullscreenListenerCallback();
    //}

    int v = UNIQUE_COUNTER.incrementAndGet();
    if (Patches.APPLE_BUG_ID_10514018) {
      frame.addWindowListener(new WindowAdapter() {
        @Override
        public void windowDeiconified(WindowEvent e) {
          if (e.getWindow() == frame && frame.getState() == Frame.ICONIFIED) {
            frame.setState(Frame.NORMAL);
          }
        }
      });
    }

    try {
      if (SystemInfo.isMacOSLion) {
        if (!FULL_SCREEN_AVAILABLE) return;

        FullScreenUtilities.setWindowCanFullScreen(frame, true);

        FullScreenUtilities.addFullScreenListenerTo(frame, new FullScreenAdapter() {
          @Override
          public void windowEnteredFullScreen(AppEvent.FullScreenEvent event) {
            // We can get the notification when the frame has been disposed
            if (myFrame == null/*|| ORACLE_BUG_ID_8003173*/) return;
            enterFullscreen();
            myFrame.validate();
          }

          @Override
          public void windowExitedFullScreen(AppEvent.FullScreenEvent event) {
            // We can get the notification when the frame has been disposed
            if (myFrame == null/* || ORACLE_BUG_ID_8003173*/) return;
            exitFullscreen();
            myFrame.validate();
          }
        });
      }
      else {
        final ID window = MacUtil.findWindowForTitle(frame.getTitle());
        if (window == null) return;

        // toggle toolbar
        String className = "IdeaToolbar" + v;
        final ID ownToolbar = Foundation.allocateObjcClassPair(Foundation.getObjcClass("NSToolbar"), className);
        Foundation.registerObjcClassPair(ownToolbar);

        final ID toolbar = invoke(invoke(className, "alloc"), "initWithIdentifier:", Foundation.nsString(className));
        Foundation.cfRetain(toolbar);

        invoke(toolbar, "setVisible:", 0); // hide native toolbar by default

        Foundation.addMethod(ownToolbar, Foundation.createSelector("setVisible:"), SET_VISIBLE_CALLBACK, "v*");
        Foundation.addMethod(ownToolbar, Foundation.createSelector("isVisible"), IS_VISIBLE, "B*");

        Foundation.executeOnMainThread(new Runnable() {
          @Override
          public void run() {
            invoke(window, "setToolbar:", toolbar);
            invoke(window, "setShowsToolbarButton:", 1);
          }
        }, true, true);
      }
    }
    finally {
      invoke(pool, "release");
    }

    if (ourProtocolHandler == null) {
      // install uri handler
      final ID mainBundle = invoke("NSBundle", "mainBundle");
      final ID urlTypes = invoke(mainBundle, "objectForInfoDictionaryKey:", Foundation.nsString("CFBundleURLTypes"));
      final ApplicationInfoEx info = ApplicationInfoImpl.getShadowInstance();
      final BuildNumber build = info != null ? info.getBuild() : null;
      if (urlTypes.equals(ID.NIL) && build != null && !build.isSnapshot()) {
        LOG.warn("no url bundle present. \n" +
                 "To use platform protocol handler to open external links specify required protocols in the mac app layout section of the build file\n" +
                 "Example: args.urlSchemes = [\"your-protocol\"] will handle following links: your-protocol://open?file=file&line=line");
        return;
      }
      ourProtocolHandler = new CustomProtocolHandler();
      Application.getApplication().setOpenURIHandler(new OpenURIHandler() {
        @Override
        public void openURI(AppEvent.OpenURIEvent event) {
          ourProtocolHandler.openLink(event.getURI());
        }
      });
    }
  }

  private void replaceNativeFullscreenListenerCallback() {
    ID awtWindow = Foundation.getObjcClass("AWTWindow");

    Pointer windowWillEnterFullScreenMethod = Foundation.createSelector("windowWillEnterFullScreen:");
    ID originalWindowWillEnterFullScreen = Foundation.class_replaceMethod(awtWindow, windowWillEnterFullScreenMethod,
                                                                          windowWillEnterFullScreenCallBack, "v@::@");

    Foundation.addMethodByID(awtWindow, Foundation.createSelector("oldWindowWillEnterFullScreen:"),
                             originalWindowWillEnterFullScreen, "v@::@");

    Pointer  windowWillExitFullScreenMethod = Foundation.createSelector("windowWillExitFullScreen:");
    ID originalWindowWillExitFullScreen = Foundation.class_replaceMethod(awtWindow, windowWillExitFullScreenMethod,
                                                                         windowWillExitFullScreenCallBack, "v@::@");

    Foundation.addMethodByID(awtWindow, Foundation.createSelector("oldWindowWillExitFullScreen:"),
                             originalWindowWillExitFullScreen, "v@::@");
  }

  @Override
  public void uiSettingsChanged(final UISettings source) {
    if (CURRENT_GETTER != null) {
      SHOWN = CURRENT_GETTER.fun(null);
    }
  }

  @Override
  public boolean isInFullScreen() {
    return myInFullScreen;
  }

  @Override
  public void toggleFullScreen(boolean state) {
    if (!SystemInfo.isMacOSLion || myFrame == null) return;
    if (SystemInfo.isJavaVersionAtLeast("1.7")) {
      try {
        requestToggleFullScreenMethod.invoke(Application.getApplication(),myFrame);
      }
      catch (IllegalAccessException e) {
        LOG.error(e);
      }
      catch (InvocationTargetException e) {
        LOG.error(e);
      }
    } else if (myInFullScreen != state) {
      final ID window = MacUtil.findWindowForTitle(myFrame.getTitle());
      if (window == null) return;
      Foundation.executeOnMainThread(new Runnable() {
        @Override
        public void run() {
          invoke(window, "toggleFullScreen:", window);
        }
      }, true, true);
    }
  }
}

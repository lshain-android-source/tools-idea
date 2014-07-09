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

/*
 * Class Breakpoint
 * @author Jeka
 */
package com.intellij.debugger.ui.breakpoints;

import com.intellij.debugger.*;
import com.intellij.debugger.engine.*;
import com.intellij.debugger.engine.evaluation.*;
import com.intellij.debugger.engine.evaluation.expression.EvaluatorBuilderImpl;
import com.intellij.debugger.engine.evaluation.expression.ExpressionEvaluator;
import com.intellij.debugger.engine.events.SuspendContextCommandImpl;
import com.intellij.debugger.engine.requests.RequestManagerImpl;
import com.intellij.debugger.jdi.StackFrameProxyImpl;
import com.intellij.debugger.jdi.ThreadReferenceProxyImpl;
import com.intellij.debugger.requests.ClassPrepareRequestor;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.*;
import com.intellij.psi.PsiClass;
import com.intellij.ui.AppUIUtil;
import com.intellij.util.StringBuilderSpinAllocator;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.ReferenceType;
import com.sun.jdi.Value;
import com.sun.jdi.VoidValue;
import com.sun.jdi.event.LocatableEvent;
import org.jdom.Element;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.util.List;

public abstract class Breakpoint extends FilteredRequestor implements ClassPrepareRequestor {
  public boolean ENABLED = true;
  public boolean LOG_ENABLED = false;
  public boolean LOG_EXPRESSION_ENABLED = false;
  public boolean REMOVE_AFTER_HIT = false;
  private TextWithImports  myLogMessage; // an expression to be evaluated and printed
  @NonNls private static final String LOG_MESSAGE_OPTION_NAME = "LOG_MESSAGE";
  public static final Breakpoint[] EMPTY_ARRAY = new Breakpoint[0];
  protected boolean myCachedVerifiedState = false;

  protected Breakpoint(@NotNull Project project) {
    super(project);
    myLogMessage = new TextWithImportsImpl(CodeFragmentKind.EXPRESSION, "");
    //noinspection AbstractMethodCallInConstructor
    final BreakpointDefaults defaults = DebuggerManagerEx.getInstanceEx(project).getBreakpointManager().getBreakpointDefaults(getCategory());
    SUSPEND_POLICY = defaults.getSuspendPolicy();
    CONDITION_ENABLED = defaults.isConditionEnabled();
  }

  public abstract PsiClass getPsiClass();
  /**
   * Request for creating all needed JPDA requests in the specified VM
   * @param debuggerProcess the requesting process
   */
  public abstract void createRequest(DebugProcessImpl debuggerProcess);

  /**
   * Request for creating all needed JPDA requests in the specified VM
   * @param debuggerProcess the requesting process
   */
  @Override
  public abstract void processClassPrepare(DebugProcess debuggerProcess, final ReferenceType referenceType);

  public abstract String getDisplayName ();
  
  public String getShortName() {
    return getDisplayName();
  }

  @Nullable
  public String getClassName() {
    return null;
  }

  public void markVerified(boolean isVerified) {
    myCachedVerifiedState = isVerified;
  }

  @Nullable
  public String getShortClassName() {
    final String className = getClassName();
    if (className != null) {
      final int dotIndex = className.lastIndexOf('.');
      return dotIndex >= 0 && dotIndex + 1 < className.length()? className.substring(dotIndex + 1) : className;
    }
    return className;
  }

  @Nullable
  public String getPackageName() {
    return null;
  }

  public abstract Icon getIcon();

  public abstract void reload();

  /**
   * returns UI representation
   */
  public abstract String getEventMessage(LocatableEvent event);

  public abstract boolean isValid();

  public abstract Key<? extends Breakpoint> getCategory();

  /**
   * Associates breakpoint with class.
   *    Create requests for loaded class and registers callback for loading classes
   * @param debugProcess the requesting process
   */
  protected void createOrWaitPrepare(DebugProcessImpl debugProcess, String classToBeLoaded) {
    debugProcess.getRequestsManager().callbackOnPrepareClasses(this, classToBeLoaded);

    List list = debugProcess.getVirtualMachineProxy().classesByName(classToBeLoaded);
    for (final Object aList : list) {
      ReferenceType refType = (ReferenceType)aList;
      if (refType.isPrepared()) {
        processClassPrepare(debugProcess, refType);
      }
    }
  }

  protected void createOrWaitPrepare(final DebugProcessImpl debugProcess, final SourcePosition classPosition) {
    debugProcess.getRequestsManager().callbackOnPrepareClasses(this, classPosition);

    List list = debugProcess.getPositionManager().getAllClasses(classPosition);
    for (final Object aList : list) {
      ReferenceType refType = (ReferenceType)aList;
      if (refType.isPrepared()) {
        processClassPrepare(debugProcess, refType);
      }
    }
  }

  protected ObjectReference getThisObject(SuspendContextImpl context, LocatableEvent event) throws EvaluateException {
    ThreadReferenceProxyImpl thread = context.getThread();
    if(thread != null) {
      StackFrameProxyImpl stackFrameProxy = thread.frame(0);
      if(stackFrameProxy != null) {
        return stackFrameProxy.thisObject();
      }
    }
    return null;
  }

  @Override
  public boolean processLocatableEvent(final SuspendContextCommandImpl action, final LocatableEvent event) throws EventProcessingException {
    final SuspendContextImpl context = action.getSuspendContext();
    if(!isValid()) {
      context.getDebugProcess().getRequestsManager().deleteRequest(this);
      return false;
    }

    final String[] title = {DebuggerBundle.message("title.error.evaluating.breakpoint.condition") };

    try {
      final StackFrameProxyImpl frameProxy = context.getThread().frame(0);
      if (frameProxy == null) {
        // might be if the thread has been collected
        return false;
      }

      final EvaluationContextImpl evaluationContext = new EvaluationContextImpl(
        action.getSuspendContext(),
        frameProxy,
        getThisObject(context, event)
      );

      if(!evaluateCondition(evaluationContext, event)) {
        return false;
      }

      title[0] = DebuggerBundle.message("title.error.evaluating.breakpoint.action");
      runAction(evaluationContext, event);
    }
    catch (final EvaluateException ex) {
      if(ApplicationManager.getApplication().isUnitTestMode()) {
        System.out.println(ex.getMessage());
        return false;
      }

      throw new EventProcessingException(title[0], ex.getMessage(), ex);
    } 

    return true;
  }

  private void runAction(final EvaluationContextImpl context, LocatableEvent event) {
    final DebugProcessImpl debugProcess = context.getDebugProcess();
    if (LOG_ENABLED || LOG_EXPRESSION_ENABLED) {
      final StringBuilder buf = StringBuilderSpinAllocator.alloc();
      try {
        if (LOG_ENABLED) {
          buf.append(getEventMessage(event));
          buf.append("\n");
        }
        final TextWithImports expressionToEvaluate = getLogMessage();
        if (LOG_EXPRESSION_ENABLED && expressionToEvaluate != null && !"".equals(expressionToEvaluate.getText())) {
          if(!debugProcess.isAttached()) {
            return;
          }
  
          try {
            ExpressionEvaluator evaluator = DebuggerInvocationUtil.commitAndRunReadAction(getProject(), new EvaluatingComputable<ExpressionEvaluator>() {
              @Override
              public ExpressionEvaluator compute() throws EvaluateException {
                return EvaluatorBuilderImpl.build(expressionToEvaluate, ContextUtil.getContextElement(context), ContextUtil.getSourcePosition(context));
              }
            });
            final Value eval = evaluator.evaluate(context);
            final String result = eval instanceof VoidValue ? "void" : DebuggerUtils.getValueAsString(context, eval);
            buf.append(result);
          }
          catch (EvaluateException e) {
            buf.append(DebuggerBundle.message("error.unable.to.evaluate.expression"));
            buf.append(" \"");
            buf.append(expressionToEvaluate);
            buf.append("\"");
            buf.append(" : ");
            buf.append(e.getMessage());
          }
          buf.append("\n");
        }
        if (buf.length() > 0) {
          debugProcess.printToConsole(buf.toString());
        }
      }
      finally {
        StringBuilderSpinAllocator.dispose(buf);
      }
    }
    if (REMOVE_AFTER_HIT) {
      handleTemporaryBreakpointHit(debugProcess);
    }
  }

  private void handleTemporaryBreakpointHit(final DebugProcessImpl debugProcess) {
    debugProcess.addDebugProcessListener(new DebugProcessAdapter() {
      @Override
      public void resumed(SuspendContext suspendContext) {
        removeBreakpoint();
      }

      @Override
      public void processDetached(DebugProcess process, boolean closedByUser) {
        removeBreakpoint();
      }

      private void removeBreakpoint() {
        AppUIUtil.invokeOnEdt(new Runnable() {
          @Override
          public void run() {
            DebuggerManagerEx.getInstanceEx(myProject).getBreakpointManager().removeBreakpoint(Breakpoint.this);
          }
        });
        debugProcess.removeDebugProcessListener(this);
      }
    });
  }

  public final void updateUI() {
    updateUI(EmptyRunnable.getInstance());
  }

  public void updateUI(@NotNull Runnable afterUpdate) {
  }

  public void delete() {
    RequestManagerImpl.deleteRequests(this);
  }

  @Override
  public void readExternal(Element parentNode) throws InvalidDataException {
    super.readExternal(parentNode);
    String logMessage = JDOMExternalizerUtil.readField(parentNode, LOG_MESSAGE_OPTION_NAME);
    if (logMessage != null) {
      setLogMessage(new TextWithImportsImpl(CodeFragmentKind.EXPRESSION, logMessage));
    }
  }

  @Override
  public void writeExternal(Element parentNode) throws WriteExternalException {
    super.writeExternal(parentNode);
    JDOMExternalizerUtil.writeField(parentNode, LOG_MESSAGE_OPTION_NAME, getLogMessage().toExternalForm());
  }

  public TextWithImports getLogMessage() {
    return myLogMessage;
  }

  public void setLogMessage(TextWithImports logMessage) {
    myLogMessage = logMessage;
  }
}

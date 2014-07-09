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
package com.intellij.debugger.engine.events;

import com.intellij.debugger.engine.DebuggerManagerThreadImpl;
import com.intellij.debugger.engine.SuspendContextImpl;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.containers.Stack;

/**
 * Created by IntelliJ IDEA.
 * User: lex
 * Date: Feb 24, 2004
 * Time: 7:01:31 PM
 * Performs contextAction when evaluation is available in suspend context
 */
public abstract class SuspendContextCommandImpl extends DebuggerCommandImpl {
  private static final Logger LOG = Logger.getInstance("#com.intellij.debugger.engine.SuspendContextCommand");
  private final SuspendContextImpl mySuspendContext;

  protected SuspendContextCommandImpl(SuspendContextImpl suspendContext) {
    mySuspendContext = suspendContext;
  }

  public abstract void contextAction() throws Exception;

  public final void action() throws Exception {
    if(LOG.isDebugEnabled()) {
      LOG.debug("trying " + this);
    }

    final SuspendContextImpl suspendContext = getSuspendContext();

    if (suspendContext == null) {
      if (LOG.isDebugEnabled()) {
        LOG.debug("skip processing - context is null " + this);
      }
      notifyCancelled();
      return;
    }

    if(suspendContext.myInProgress) {
      suspendContext.postponeCommand(this);
    }
    else {
      try {
        if(!suspendContext.isResumed()) {
          suspendContext.myInProgress = true;
          contextAction();
        }
        else {
          notifyCancelled();
        }
      }
      finally{
        suspendContext.myInProgress = false;
        SuspendContextCommandImpl postponed = suspendContext.pollPostponedCommand();
        if (postponed != null) {
          final Stack<SuspendContextCommandImpl> stack = new Stack<SuspendContextCommandImpl>();
          while (postponed != null) {
            stack.push(postponed);
            postponed = suspendContext.pollPostponedCommand();
          }
          final DebuggerManagerThreadImpl managerThread = suspendContext.getDebugProcess().getManagerThread();
          while (!stack.isEmpty()) {
            managerThread.pushBack(stack.pop());
          }
        }
      }
    }
  }

  public SuspendContextImpl getSuspendContext() {
    return mySuspendContext;
  }
}

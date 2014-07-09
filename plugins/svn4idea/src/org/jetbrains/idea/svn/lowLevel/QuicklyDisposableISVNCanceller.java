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
package org.jetbrains.idea.svn.lowLevel;

import com.intellij.openapi.progress.ProcessCanceledException;
import org.tmatesoft.svn.core.ISVNCanceller;
import org.tmatesoft.svn.core.SVNCancelException;

/**
 * Created with IntelliJ IDEA.
 * User: Irina.Chernushina
 * Date: 8/15/12
 * Time: 3:30 PM
 */
public class QuicklyDisposableISVNCanceller extends QuicklyDisposableProxy<ISVNCanceller> implements ISVNCanceller {
  public QuicklyDisposableISVNCanceller(ISVNCanceller canceller) {
    super(canceller);
  }

  public void checkCancelled() throws SVNCancelException {
    try {
      getRef().checkCancelled();
    } catch (ProcessCanceledException e) {
      throw new SVNCancelException();
    }
  }
}

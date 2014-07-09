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
 * Created by IntelliJ IDEA.
 * User: max
 * Date: May 13, 2002
 * Time: 7:50:36 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.openapi.editor.actions;

import com.intellij.openapi.actionSystem.DataContext;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorModificationUtil;
import com.intellij.openapi.editor.actionSystem.EditorAction;
import com.intellij.openapi.editor.actionSystem.EditorWriteActionHandler;
import com.intellij.openapi.editor.ex.EditorEx;
import com.intellij.util.Producer;

import java.awt.datatransfer.Transferable;

public class PasteAction extends EditorAction {
  public static final String TRANSFERABLE_PROVIDER = "PasteTransferableProvider";
  
  public PasteAction() {
    super(new Handler());
  }

  private static class Handler extends EditorWriteActionHandler {
    @Override
    public void executeWriteAction(Editor editor, DataContext dataContext) {
      Producer<Transferable> producer = (Producer<Transferable>)dataContext.getData(TRANSFERABLE_PROVIDER);

      if (editor.isColumnMode() || editor.getSelectionModel().hasBlockSelection()) {
        EditorModificationUtil.pasteTransferableAsBlock(editor, producer == null ? null : producer.produce());
      }
      else {
        editor.putUserData(EditorEx.LAST_PASTED_REGION,
                           producer == null ? EditorModificationUtil.pasteFromClipboard(editor) :
                           EditorModificationUtil.pasteFromTransferrable(producer.produce(), editor));
      }
    }
  }
}

/*
 * Copyright 2000-2010 JetBrains s.r.o.
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
package org.intellij.plugins.intelliLang.inject.config;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.IndexNotReadyException;
import com.intellij.psi.impl.source.jsp.JspManager;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;

/**
* @author Gregory.Shrago
*/
public class JspSupportProxyImpl extends JspSupportProxy {
  @NotNull
  @Override
  public String[] getPossibleTldUris(Module module) {
    try {
      return JspManager.getInstance(module.getProject()).getPossibleTldUris(module);
    }
    catch (IndexNotReadyException e) {
      return ArrayUtil.EMPTY_STRING_ARRAY;
    }
  }
}

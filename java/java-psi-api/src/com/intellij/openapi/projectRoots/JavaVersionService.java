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

/*
 * @author max
 */
package com.intellij.openapi.projectRoots;

import com.intellij.openapi.components.ServiceManager;
import com.intellij.psi.PsiElement;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

public class JavaVersionService {
  public static JavaVersionService getInstance() {
    return ServiceManager.getService(JavaVersionService.class);
  }

  public boolean isAtLeast(@NotNull PsiElement element, @NotNull JavaSdkVersion version) {
    return PsiUtil.getLanguageLevel(element).isAtLeast(version.getMaxLanguageLevel());
  }
}
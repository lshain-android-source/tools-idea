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
package com.intellij.compiler.impl.javaCompiler.api;

import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;

/**
* User: cdr
*/
@SuppressWarnings({"Since15"})
class JavaIoFile extends SimpleJavaFileObject {
  private final File myFile;
  @Nullable
  private final String myEncoding;

  JavaIoFile(@NotNull File file, @NotNull Kind kind, @Nullable String encoding) {
    super(convertToURI(file.getPath()), kind);
    myFile = file;
    myEncoding = encoding;
  }

  // File.toURI() asks for File.isDirectory which is too expensive
  @NotNull
  private static URI convertToURI(@NotNull String path) {
    if (File.separatorChar != '/') {
      path = path.replace(File.separatorChar, '/');
    }
    if (!StringUtil.startsWithChar(path, '/')) {
      path = "/" + path;
    }

    if (path.startsWith("//")) {
      path = "//" + path;
    }
    try {
      return new URI("file", null, path, null);
    }
    catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
    return FileUtil.loadFile(myFile, myEncoding);
  }

  @Override
  public InputStream openInputStream() throws IOException {
    return new BufferedInputStream(new FileInputStream(myFile));
  }

  @Override
  public OutputStream openOutputStream() throws IOException {
    return new BufferedOutputStream(new FileOutputStream(myFile));
  }

  @Override
  public String toString() {
    return toUri().toString();
  }

  @Override
  public int hashCode() {
    return toUri().hashCode();
  }

  @Override
  public boolean equals(Object obj) {
    return obj instanceof JavaFileObject && toUri().equals(((JavaFileObject)obj).toUri());
  }
}

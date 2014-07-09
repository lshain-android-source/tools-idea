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
package com.intellij.util.xml.stubs;

import com.intellij.psi.stubs.IndexSink;
import com.intellij.psi.stubs.ObjectStubSerializer;
import com.intellij.psi.stubs.StubInputStream;
import com.intellij.psi.stubs.StubOutputStream;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

/**
 * @author Dmitry Avdeev
 *         Date: 8/3/12
 */
public class ElementStubSerializer implements ObjectStubSerializer<ElementStub, ElementStub> {

  final static ObjectStubSerializer INSTANCE = new ElementStubSerializer();

  @Override
  public void serialize(@NotNull ElementStub stub, @NotNull StubOutputStream dataStream) throws IOException {
    dataStream.writeName(stub.getName());
    dataStream.writeName(stub.getNamespaceKey());
    dataStream.writeBoolean(stub.isCustom());
  }

  @NotNull
  @Override
  public ElementStub deserialize(@NotNull StubInputStream dataStream, ElementStub parentStub) throws IOException {
    return new ElementStub(parentStub, dataStream.readName(), dataStream.readName(), dataStream.readBoolean());
  }

  @Override
  public void indexStub(@NotNull ElementStub stub, @NotNull IndexSink sink) {
  }

  @NotNull
  @Override
  public String getExternalId() {
    return "ElementStubSerializer";
  }

  @Override
  public String toString() {
    return "Element";
  }
}

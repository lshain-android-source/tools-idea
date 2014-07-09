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
 * User: cdr
 * Date: Sep 20, 2002
 */
package com.intellij.psi.controlFlow;

public abstract class BranchingInstruction extends InstructionBase {
  public int offset;
  public final Role role;

  public enum Role {
    THEN, ELSE, END
  }

  public BranchingInstruction(int offset, Role role) {
    this.offset = offset;
    this.role = role;
  }

  @Override
  public void accept(ControlFlowInstructionVisitor visitor, int offset, int nextOffset) {
    visitor.visitBranchingInstruction(this, offset, nextOffset);
  }
}

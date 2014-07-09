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
 * Date: Jan 11, 2002
 * Time: 3:05:34 PM
 * To change template for new class use
 * Code Style | Class Templates options (Tools | IDE Options).
 */
package com.intellij.codeInspection.dataFlow;

import com.intellij.codeInspection.dataFlow.instructions.FlushVariableInstruction;
import com.intellij.codeInspection.dataFlow.instructions.Instruction;
import com.intellij.codeInspection.dataFlow.value.DfaValueFactory;
import com.intellij.codeInspection.dataFlow.value.DfaVariableValue;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiVariable;
import gnu.trove.TObjectIntHashMap;

import java.util.ArrayList;
import java.util.List;

public class ControlFlow {
  private final List<Instruction> myInstructions = new ArrayList<Instruction>();
  private final TObjectIntHashMap<PsiElement> myElementToStartOffsetMap = new TObjectIntHashMap<PsiElement>();
  private final TObjectIntHashMap<PsiElement> myElementToEndOffsetMap = new TObjectIntHashMap<PsiElement>();
  private DfaVariableValue[] myFields;
  private final DfaValueFactory myFactory;

  public ControlFlow(final DfaValueFactory factory) {
    myFactory = factory;
  }

  public Instruction[] getInstructions(){
    return myInstructions.toArray(new Instruction[myInstructions.size()]);
  }

  public int getInstructionCount() {
    return myInstructions.size();
  }

  public void startElement(PsiElement psiElement) {
    myElementToStartOffsetMap.put(psiElement, myInstructions.size());
  }

  public void finishElement(PsiElement psiElement) {
    myElementToEndOffsetMap.put(psiElement, myInstructions.size());
  }

  public void addInstruction(Instruction instruction) {
    instruction.setIndex(myInstructions.size());
    myInstructions.add(instruction);
  }

  public void removeVariable(PsiVariable variable) {
    DfaVariableValue var = myFactory.getVarFactory().createVariableValue(variable, false);
    addInstruction(new FlushVariableInstruction(var));
  }

  public int getStartOffset(PsiElement element){
    if (!myElementToStartOffsetMap.containsKey(element)) return -1;
    return myElementToStartOffsetMap.get(element);
  }

  public int getEndOffset(PsiElement element){
    if (!myElementToEndOffsetMap.containsKey(element)) return -1;
    return myElementToEndOffsetMap.get(element);
  }

  public DfaVariableValue[] getFields() {
    return myFields;
  }

  public void setFields(DfaVariableValue[] fields) {
    myFields = fields;
  }


  public String toString() {
    StringBuilder result = new StringBuilder();
    final List<Instruction> instructions = myInstructions;

    for (int i = 0; i < instructions.size(); i++) {
      Instruction instruction = instructions.get(i);
      result.append(Integer.toString(i)).append(": ").append(instruction.toString());
      result.append("\n");
    }
    return result.toString();
  }
}

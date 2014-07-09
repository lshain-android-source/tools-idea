/*
 * Copyright 2007 Sascha Weinreuter
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.plugins.relaxNG.compact;

import com.intellij.lang.ASTNode;
import com.intellij.lang.ParserDefinition;
import com.intellij.lang.PsiParser;
import com.intellij.lexer.Lexer;
import com.intellij.openapi.project.Project;
import com.intellij.psi.FileViewProvider;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.TokenType;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.tree.IFileElementType;
import com.intellij.psi.tree.TokenSet;
import com.intellij.util.NotNullFunction;
import org.intellij.plugins.relaxNG.compact.lexer.CompactSyntaxLexerAdapter;
import org.intellij.plugins.relaxNG.compact.parser.RncParser;
import org.intellij.plugins.relaxNG.compact.psi.RncElementVisitor;
import org.intellij.plugins.relaxNG.compact.psi.impl.RncElementImpl;
import org.intellij.plugins.relaxNG.compact.psi.impl.RncFileImpl;
import org.jetbrains.annotations.NotNull;

/**
 * Created by IntelliJ IDEA.
 * User: sweinreuter
 * Date: 10.08.2007
 */
public class RncParserDefinition implements ParserDefinition {
  public static final IFileElementType FILE_ELEMENT_TYPE = new IFileElementType(RngCompactLanguage.INSTANCE);
  private static final TokenSet myCommentTypes = TokenSet.orSet(RncTokenTypes.COMMENTS, RncTokenTypes.DOC_TOKENS);

  @NotNull
  public Lexer createLexer(Project project) {
    return new CompactSyntaxLexerAdapter();
  }

  public PsiParser createParser(Project project) {
    return new RncParser();
  }

  public IFileElementType getFileNodeType() {
    return FILE_ELEMENT_TYPE;
  }

  @NotNull
  public TokenSet getWhitespaceTokens() {
    return TokenSet.create(TokenType.WHITE_SPACE);
  }

  @NotNull
  public TokenSet getCommentTokens() {
    return myCommentTypes;
  }

  @NotNull
  public TokenSet getStringLiteralElements() {
    return TokenSet.create(RncTokenTypes.LITERAL);
  }

  @NotNull
  @SuppressWarnings({ "unchecked" })
  public PsiElement createElement(ASTNode node) {
    final IElementType type = node.getElementType();

    if (type instanceof NotNullFunction) {
      return ((NotNullFunction<ASTNode, PsiElement>)type).fun(node);
    }

    return new MyRncElement(node);
  }

  public PsiFile createFile(FileViewProvider viewProvider) {
    return new RncFileImpl(viewProvider);
  }

  public SpaceRequirements spaceExistanceTypeBetweenTokens(ASTNode left, ASTNode right) {
    return SpaceRequirements.MAY;
  }

  private static class MyRncElement extends RncElementImpl {
    public MyRncElement(ASTNode node) {
      super(node);
    }

    public void accept(@NotNull RncElementVisitor visitor) {
      visitor.visitElement(this);
    }
  }
}

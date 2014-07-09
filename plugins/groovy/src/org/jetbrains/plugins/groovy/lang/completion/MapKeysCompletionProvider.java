package org.jetbrains.plugins.groovy.lang.completion;

import com.intellij.codeInsight.completion.*;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.LookupElementBuilder;
import com.intellij.psi.CommonClassNames;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiType;
import com.intellij.util.ProcessingContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.plugins.groovy.extensions.GroovyMapContentProvider;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrExpression;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrMethodCall;
import org.jetbrains.plugins.groovy.lang.psi.api.statements.expressions.GrReferenceExpression;
import org.jetbrains.plugins.groovy.lang.psi.impl.GrMapType;
import org.jetbrains.plugins.groovy.lang.psi.impl.GroovyPsiManager;

import static com.intellij.patterns.PlatformPatterns.psiElement;

/**
 * @author Sergey Evdokimov
 */
class MapKeysCompletionProvider extends CompletionProvider<CompletionParameters> {

  public static void register(CompletionContributor contributor) {
    MapKeysCompletionProvider provider = new MapKeysCompletionProvider();

    contributor.extend(CompletionType.BASIC, psiElement().withParent(psiElement(GrReferenceExpression.class)), provider);
  }

  @Override
  protected void addCompletions(@NotNull CompletionParameters parameters,
                                ProcessingContext context,
                                @NotNull CompletionResultSet result) {
    PsiElement element = parameters.getPosition();
    GrReferenceExpression expression = (GrReferenceExpression)element.getParent();

    GrExpression qualifierExpression = expression.getQualifierExpression();
    if (qualifierExpression == null) return;

    PsiType mapType = qualifierExpression.getType();

    if (!GroovyPsiManager.isInheritorCached(mapType, CommonClassNames.JAVA_UTIL_MAP)) {
      return;
    }

    PsiElement resolve = null;

    if (qualifierExpression instanceof GrMethodCall) {
      resolve = ((GrMethodCall)qualifierExpression).resolveMethod();
    }
    else if (qualifierExpression instanceof GrReferenceExpression) {
      resolve = ((GrReferenceExpression)qualifierExpression).resolve();
    }

    for (GroovyMapContentProvider provider : GroovyMapContentProvider.EP_NAME.getExtensions()) {
      provider.addKeyVariants(qualifierExpression, resolve, result);
    }

    if (mapType instanceof GrMapType) {
      for (String key : ((GrMapType)mapType).getStringKeys()) {
        LookupElement lookup = LookupElementBuilder.create(key);
        lookup = PrioritizedLookupElement.withPriority(lookup, 1);
        result.addElement(lookup);
      }
    }
  }
}

package com.intellij.codeInsight.completion.methodChains.search;

import com.intellij.codeInsight.NullableNotNullManager;
import com.intellij.codeInsight.completion.JavaChainLookupElement;
import com.intellij.codeInsight.completion.methodChains.ChainCompletionStringUtil;
import com.intellij.codeInsight.completion.methodChains.completion.context.ChainCompletionContext;
import com.intellij.codeInsight.completion.methodChains.completion.context.ContextRelevantStaticMethod;
import com.intellij.codeInsight.completion.methodChains.completion.context.ContextRelevantVariableGetter;
import com.intellij.codeInsight.completion.methodChains.completion.lookup.ChainCompletionNewVariableLookupElement;
import com.intellij.codeInsight.completion.methodChains.completion.lookup.WeightableChainLookupElement;
import com.intellij.codeInsight.completion.methodChains.completion.lookup.sub.GetterLookupSubLookupElement;
import com.intellij.codeInsight.completion.methodChains.completion.lookup.sub.SubLookupElement;
import com.intellij.codeInsight.completion.methodChains.completion.lookup.sub.VariableSubLookupElement;
import com.intellij.codeInsight.lookup.LookupElement;
import com.intellij.codeInsight.lookup.VariableLookupItem;
import com.intellij.psi.*;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.TIntObjectHashMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.intellij.codeInsight.completion.methodChains.completion.lookup.ChainCompletionLookupElementUtil.createLookupElement;
import static com.intellij.psi.CommonClassNames.JAVA_LANG_STRING;

/**
 * @author Dmitry Batkovich <dmitry.batkovich@jetbrains.com>
 */
public class MethodsChainLookupRangingHelper {

  public static List<LookupElement> chainsToWeightableLookupElements(final List<MethodsChain> chains,
                                                                     final ChainCompletionContext context) {
    final List<LookupElement> lookupElements = new ArrayList<LookupElement>(chains.size());
    for (final MethodsChain chain : chains) {
      final LookupElement lookupElement = chainToWeightableLookupElement(chain, context);
      if (lookupElement != null) {
        lookupElements.add(lookupElement);
      }
    }
    return lookupElements;
  }

  @SuppressWarnings("ConstantConditions")
  @Nullable
  private static WeightableChainLookupElement chainToWeightableLookupElement(final MethodsChain chain,
                                                                             final ChainCompletionContext context) {
    final int chainSize = chain.size();
    assert chainSize != 0;
    final int lastMethodWeight = chain.getChainWeight();
    int unreachableParametersCount = 0;
    int notMatchedStringVars = 0;
    Boolean isFirstMethodStatic = null;
    Boolean hasCallingVariableInContext = null;
    LookupElement chainLookupElement = null;

    final NullableNotNullManager nullableNotNullManager = NullableNotNullManager.getInstance(context.getProject());

    for (final PsiMethod[] psiMethods : chain.getPath()) {
      final PsiMethod method =
        MethodChainsSearchUtil.getMethodWithMinNotPrimitiveParameters(psiMethods, Collections.singleton(context.getTargetQName()));
      if (method == null) {
        return null;
      }
      if (isFirstMethodStatic == null) {
        isFirstMethodStatic = psiMethods[0].hasModifierProperty(PsiModifier.STATIC);
      }
      final MethodProcResult procResult =
        processMethod(method, context, lastMethodWeight, chainLookupElement == null, nullableNotNullManager);
      if (procResult == null) {
        return null;
      }
      if (hasCallingVariableInContext == null) {
        hasCallingVariableInContext = procResult.hasCallingVariableInContext();
      }
      unreachableParametersCount += procResult.getUnreachableParametersCount();
      notMatchedStringVars += procResult.getNotMatchedStringVars();
      chainLookupElement = chainLookupElement == null
                           ? procResult.getLookupElement()
                           : new JavaChainLookupElement(chainLookupElement, procResult.getLookupElement());
    }

    final ChainRelevance relevance = new ChainRelevance(chainSize,
                                                        lastMethodWeight,
                                                        unreachableParametersCount,
                                                        notMatchedStringVars,
                                                        hasCallingVariableInContext,
                                                        isFirstMethodStatic);

    return new WeightableChainLookupElement(chainLookupElement, relevance);
  }


  private static MethodProcResult processMethod(@NotNull final PsiMethod method,
                                                final ChainCompletionContext context,
                                                final int weight,
                                                final boolean isHeadMethod,
                                                final NullableNotNullManager nullableNotNullManager) {
    int unreachableParametersCount = 0;
    int notMatchedStringVars = 0;
    boolean hasCallingVariableInContext = false;
    final PsiParameterList parameterList = method.getParameterList();
    final TIntObjectHashMap<SubLookupElement> parametersMap = new TIntObjectHashMap<SubLookupElement>(parameterList.getParametersCount());
    final PsiParameter[] parameters = parameterList.getParameters();
    for (int i = 0; i < parameters.length; i++) {
      final PsiParameter parameter = parameters[i];
      final String typeQName = parameter.getType().getCanonicalText();
      if (typeQName != null) {
        if (JAVA_LANG_STRING.equals(typeQName)) {
          final PsiVariable relevantStringVar = context.findRelevantStringInContext(parameter.getName());
          if (relevantStringVar == null) {
            notMatchedStringVars++;
          }
          else {
            parametersMap.put(i, new VariableSubLookupElement(relevantStringVar));
          }
        }
        else if (!ChainCompletionStringUtil.isPrimitiveOrArrayOfPrimitives(typeQName)) {
          final Collection<PsiVariable> contextVariables = context.getVariables(typeQName);
          final PsiVariable contextVariable = ContainerUtil.getFirstItem(contextVariables, null);
          if (contextVariable != null) {
            if (contextVariables.size() == 1) parametersMap.put(i, new VariableSubLookupElement(contextVariable));
            continue;
          }
          final Collection<ContextRelevantVariableGetter> relevantVariablesGetters = context.getRelevantVariablesGetters(typeQName);
          final ContextRelevantVariableGetter contextVariableGetter = ContainerUtil.getFirstItem(relevantVariablesGetters, null);
          if (contextVariableGetter != null) {
            if (relevantVariablesGetters.size() == 1) parametersMap.put(i, contextVariableGetter.createSubLookupElement());
            continue;
          }
          final Collection<PsiMethod> containingClassMethods = context.getContainingClassMethods(typeQName);
          final PsiMethod contextRelevantGetter = ContainerUtil.getFirstItem(containingClassMethods, null);
          if (contextRelevantGetter != null) {
            if (containingClassMethods.size() == 1) parametersMap.put(i, new GetterLookupSubLookupElement(method.getName()));
            continue;
          }
          final ContextRelevantStaticMethod contextRelevantStaticMethod =
            ContainerUtil.getFirstItem(context.getRelevantStaticMethods(typeQName, weight), null);
          if (contextRelevantStaticMethod != null) {
            //
            // In most cases it is not really relevant
            //
            //parametersMap.put(i, contextRelevantStaticMethod.createLookupElement());
            continue;
          }
          if (!nullableNotNullManager.isNullable(parameter, true)) {
            unreachableParametersCount++;
          }
        }
      }
    }
    final LookupElement lookupElement;
    if (isHeadMethod) {
      if (method.hasModifierProperty(PsiModifier.STATIC)) {
        lookupElement = createLookupElement(method, parametersMap);
      }
      else if (method.isConstructor()) {
        return null;
      }
      else {
        final PsiClass containingClass = method.getContainingClass();
        final String classQName = containingClass.getQualifiedName();
        if (classQName == null) return null;
        final Object e = ContainerUtil.getFirstItem(context.getContextRefElements(classQName), null);
        if (e != null) {
          final LookupElement firstChainElement;
          if (e instanceof PsiVariable) {
            hasCallingVariableInContext = true;
            firstChainElement = new VariableLookupItem((PsiVariable)e);
          }
          else if (e instanceof PsiMethod) {
            firstChainElement = createLookupElement((PsiMethod)e, null);
          }
          else if (e instanceof LookupElement) {
            firstChainElement = (LookupElement)e;
          }
          else {
            throw new AssertionError();
          }
          lookupElement = new JavaChainLookupElement(firstChainElement, createLookupElement(method, parametersMap));
        }
        else lookupElement = context.getContainingClassQNames().contains(classQName)
                             ? createLookupElement(method, parametersMap)
                             : new JavaChainLookupElement(ChainCompletionNewVariableLookupElement.create(containingClass),
                                                          createLookupElement(method, parametersMap));
      }
    }
    else {
      lookupElement = createLookupElement(method, parametersMap);
    }
    return new MethodProcResult(lookupElement, unreachableParametersCount, notMatchedStringVars, hasCallingVariableInContext);
  }

  private static class MethodProcResult {
    private final LookupElement myMethodLookup;
    private final int myUnreachableParametersCount;
    private final int myNotMatchedStringVars;
    private final boolean myHasCallingVariableInContext;

    private MethodProcResult(final LookupElement methodLookup,
                             final int unreachableParametersCount,
                             final int notMatchedStringVars,
                             final boolean hasCallingVariableInContext) {
      myMethodLookup = methodLookup;
      myUnreachableParametersCount = unreachableParametersCount;
      myNotMatchedStringVars = notMatchedStringVars;
      myHasCallingVariableInContext = hasCallingVariableInContext;
    }

    private boolean hasCallingVariableInContext() {
      return myHasCallingVariableInContext;
    }

    private LookupElement getLookupElement() {
      return myMethodLookup;
    }

    private int getUnreachableParametersCount() {
      return myUnreachableParametersCount;
    }

    private int getNotMatchedStringVars() {
      return myNotMatchedStringVars;
    }
  }
}


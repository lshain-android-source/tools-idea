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
package com.intellij.openapi.util;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.extensions.ExtensionPointName;
import com.intellij.openapi.extensions.Extensions;
import com.intellij.openapi.extensions.KeyedFactoryEPBean;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * @author yole
 */
public abstract class KeyedExtensionFactory<T, KeyT> {
  private final Class<T> myInterfaceClass;
  private final ExtensionPointName<KeyedFactoryEPBean> myEpName;

  public KeyedExtensionFactory(@NotNull final Class<T> interfaceClass, @NonNls @NotNull final String epName) {
    myInterfaceClass = interfaceClass;
    myEpName = new ExtensionPointName<KeyedFactoryEPBean>(epName);
  }

  public T get() {
    final KeyedFactoryEPBean[] epBeans = Extensions.getExtensions(myEpName);
    InvocationHandler handler = new InvocationHandler() {
      public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        //noinspection unchecked
        KeyT keyArg = (KeyT) args [0];
        String key = getKey(keyArg);
        Object result = getByKey(epBeans, key, method, args);
        if (result == null) {
          result = getByKey(epBeans, null, method, args);
        }
        return result;
      }
    };
    //noinspection unchecked
    return (T)Proxy.newProxyInstance(myInterfaceClass.getClassLoader(), new Class<?>[] { myInterfaceClass }, handler );
  }

  public T getByKey(KeyT key) {
    final KeyedFactoryEPBean[] epBeans = Extensions.getExtensions(myEpName);
    for (KeyedFactoryEPBean epBean : epBeans) {
      if (Comparing.strEqual(getKey(key), epBean.key)) {
        try {
          if (epBean.implementationClass != null) {
            return (T)epBean.instantiate(epBean.implementationClass, ApplicationManager.getApplication().getPicoContainer());
          }
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    return null;
  }

  private T getByKey(final KeyedFactoryEPBean[] epBeans, final String key, final Method method, final Object[] args) {
    Object result = null;
    for(KeyedFactoryEPBean epBean: epBeans) {
      if (Comparing.strEqual(epBean.key, key, true)) {
        try {
          if (epBean.implementationClass != null) {
            result = epBean.instantiate(epBean.implementationClass, ApplicationManager.getApplication().getPicoContainer());
          }
          else {
            Object factory = epBean.instantiate(epBean.factoryClass, ApplicationManager.getApplication().getPicoContainer());
            result = method.invoke(factory, args);
          }
          if (result != null) {
            break;
          }
        }
        catch (RuntimeException e) {
          throw e;
        }
        catch (Exception e) {
          throw new RuntimeException(e);
        }
      }
    }
    //noinspection ConstantConditions
    return (T)result;
  }

  public abstract String getKey(KeyT key);
}


package com.siyeh.igfixes.migration.unnecessary_boxing;

class Cast {
  Double foo(String s) {
    return (double) (s.isEmpty() ? 1 : 2);
  }
}
interface I {
  void m(int i);
}

class Foo {
  I ii = (<error descr="'@Override' not applicable to parameter">@Override</error> final int k) -> {
    int j = k;
  };
  I ii1 = (final int k) -> {
    <error descr="Incompatible types. Found: 'int', required: 'java.lang.String'">String s = k;</error>
  };

  void bazz() {
    bar((<error descr="Incompatible parameter types in lambda expression">String s</error>) -> {
      System.out.println(s);});
    bar((int i) -> {System.out.println(i);});
  }

  void bar(I i) { }
}

class ReturnTypeCompatibility {
  interface I1<L> {
    L m(L x);
  }

  static <P> void call(I1<P> i2) {
    i2.m(null);
  }

  public static void main(String[] args) {
    call((String i)->{ return i;});
    call(<error descr="Cyclic inference">i->{ return i;}</error>);
    call(<error descr="Cyclic inference">i->""</error>);
    call<error descr="'call(ReturnTypeCompatibility.I1<java.lang.Integer>)' in 'ReturnTypeCompatibility' cannot be applied to '(<lambda expression>)'">((int i)->{ return i;})</error>;
  }
}
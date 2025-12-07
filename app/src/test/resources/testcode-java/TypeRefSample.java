import java.util.function.Function;

public class TypeRefSample {

    public enum MyEnum {
        FIRST,
        SECOND;

        public void enumMethod() {}
    }

    public static class StaticInner {
        public void innerMethod() {}
    }

    public class Inner {
        public void instanceInnerMethod() {}
    }

    private int intField;
    private StaticInner innerField;
    private static MyEnum enumStaticField;

    static {
        StaticInner si = new StaticInner();
        enumStaticField = MyEnum.FIRST;
        si.innerMethod();
    }

    public TypeRefSample() {
        this(0);
    }

    public TypeRefSample(int intParam) {
        this.intField = intParam;
        ContextManager.Context.foo();
    }

    public void instanceMethod(StaticInner paramInner, MyEnum paramEnum) {
        StaticInner localInner = new StaticInner();
        MyEnum localEnum = MyEnum.SECOND;

        paramInner.innerMethod();
        localInner.innerMethod();

        Runnable r = () -> paramInner.innerMethod();
        Function<Integer, Integer> f = x -> x + intField;

        paramEnum.enumMethod();
        localEnum.enumMethod();

        ChainedA a = new ChainedA();
        a.b().c();
    }

    public static void staticMethod() {
        StaticInner si = new StaticInner();
        si.innerMethod();

        UtilClass.staticUtilMethod();

        MyEnum e = MyEnum.FIRST;
    }

    public static class ChainedA {
        public ChainedB b() {
            return new ChainedB();
        }
    }

    public static class ChainedB {
        public void c() {}
    }

    public static class UtilClass {
        public static void staticUtilMethod() {}
    }
}

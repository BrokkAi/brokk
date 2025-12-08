import java.io.Closeable;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

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

    // Nested interface with static constants (like IgnoreNode.MatchResult)
    public interface Result {
        int SUCCESS = 0;
        int FAILURE = 1;
        int PENDING = 2;

        void process();
    }

    // Nested class with static field
    public static class Status {
        public static final String ACTIVE = "active";
        public static final String INACTIVE = "inactive";

        public String getStatus() { return ACTIVE; }
    }

    // Base type for instanceof pattern matching
    public interface Repo {
        String getName();
    }

    public static class GitRepo implements Repo {
        public String getName() { return "git"; }
        public String getTopLevel() { return "/top"; }
        public String getWorkTree() { return "/work"; }
    }

    public static class SvnRepo implements Repo {
        public String getName() { return "svn"; }
    }

    private int intField;
    private StaticInner innerField;
    private static MyEnum enumStaticField;
    private Repo repo;
    private List<String> items;

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

    // Pattern matching instanceof - gitRepo should be referenceable
    public void patternMatchingMethod(Repo repoParam) {
        if (repoParam instanceof GitRepo gitRepo) {
            String top = gitRepo.getTopLevel();
            String work = gitRepo.getWorkTree();
            gitRepo.getName();
        }

        if (repoParam instanceof SvnRepo svnRepo) {
            svnRepo.getName();
        }
    }

    // Explicit this.field access
    public void explicitThisAccess() {
        int local = this.intField;
        StaticInner inner = this.innerField;
        this.instanceMethod(inner, MyEnum.FIRST);
    }

    // Stream operations with chained lambdas
    public List<String> streamMethod(List<String> input) {
        return input.stream()
                .map(s -> s.trim())
                .filter(s -> !s.isEmpty())
                .map(s -> s.toUpperCase())
                .collect(Collectors.toList());
    }

    // Optional usage
    public void optionalMethod() {
        Optional<String> opt = Optional.of("value");
        opt.ifPresent(v -> System.out.println(v));
        String result = opt.orElse("default");
        Optional<String> empty = Optional.empty();
        empty.orElseGet(() -> "computed");
    }

    // Static field access from nested class (like MatchResult.IGNORED)
    public void nestedStaticAccess() {
        int success = Result.SUCCESS;
        int failure = Result.FAILURE;
        String active = Status.ACTIVE;
        String inactive = Status.INACTIVE;
    }

    // Try-with-resources pattern
    public void tryWithResources(Closeable resource) throws IOException {
        try (Closeable r = resource) {
            r.close();
        }
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

import java.util.*;

class AnonymousUsage {
    public void foo() {
        new Runnable() {
            public void run() {
                System.out.println(new A().method2("test"));
            }
        }.run();
    }

    class NestedClass {

        private Object getSomething() {
            Map<String, String> map = new HashMap<>();
            map.get("foo").ifPresent(s -> map.put("foo", "test")); // made up method
            return skeletonsMap;
        }

    }
    
}
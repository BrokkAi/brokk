import java.util.function.Function;

public class MethodReferenceUsage {
    
    public String transform(String input) {
        return input.toUpperCase();
    }

    public void demonstrateCall() {
        // Method call
        String result1 = transform("hello");
    }

    public void demonstrateInstanceReference() {
        Function<String, String> ref1 = this::transform;
        String result2 = ref1.apply("world");
    }

    public void demonstrateReferenceParameter() {
        processString("test", this::transform);
    }

    private void processString(String input, Function<String, String> processor) {
        processor.apply(input);
    }
}

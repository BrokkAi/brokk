import java.util.List;
import java.util.ArrayList;

// Test file to verify all class usage patterns
public class ClassUsagePatterns extends BaseClass {

    // Variable declarations
    private BaseClass field1;
    private static BaseClass field2 = null;

    // Constructor with parameters
    public ClassUsagePatterns(BaseClass param1, List<BaseClass> param2) {
        super();
        // Constructor calls
        this.field1 = new BaseClass();

        // Generics
        List<BaseClass> list = new ArrayList<>();
        list.add(param1);

        // Casts
        Object obj = param1;
        BaseClass casted = (BaseClass) obj;

        // Static access
        BaseClass.staticMethod();
    }

    // Method with class in signature
    public BaseClass processBase(BaseClass input) {
        return input;
    }

    // Method returning class type
    public List<BaseClass> getList() {
        return new ArrayList<>();
    }
}

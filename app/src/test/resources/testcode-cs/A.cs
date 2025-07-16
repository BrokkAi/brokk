namespace TestNamespace 
{
    public class A 
    {
        // Field
        public int MyField;

        // Property
        public string MyProperty { get; set; }

        // Method
        public void MethodA() 
        {
            // Method body
        }

        // Overloaded Method
        public void MethodA(int param)
        {
            // Overloaded method body
            int x = param + 1;
        }

        // Constructor
        public A() 
        {
            MyField = 0;
            MyProperty = "default";
        }
    }
}

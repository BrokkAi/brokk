// Global using directive
global using System;

// Top-level attribute definition (should be ignored by skeleton if query is correct)
[AttributeUsage(AttributeTargets.Class)]
public class MyTestAttribute : Attribute {}

// Top-level class
public class TopLevelClass
{
    public int TopField;
    public void TopMethod() {}
}

namespace NS1
{
    [MyTest]
    public class NamespacedClass
    {
        public string NsField;
        public static NamespacedClass Create() { return new NamespacedClass(); }
    }

    public interface INamespacedInterface
    {
      void DoWork();
    }
}

// Another top-level class to ensure multiple top-level items are handled
public struct TopLevelStruct
{
    public double Value;
}

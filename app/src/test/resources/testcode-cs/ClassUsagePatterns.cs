// Test file to verify C# class usage patterns

using System;
using System.Collections.Generic;

public class BaseClass
{
    public static void StaticMethod()
    {
        Console.WriteLine("static");
    }

    public void InstanceMethod()
    {
        Console.WriteLine("instance");
    }
}

public class ExtendedClass : BaseClass
{
    // Field with type
    private BaseClass field;

    public ExtendedClass(BaseClass param)
    {
        // Constructor call
        this.field = new BaseClass();

        // Static access
        BaseClass.StaticMethod();
    }

    // Method with type annotations
    public BaseClass ProcessBase(BaseClass input)
    {
        return input;
    }

    // Generics
    public List<BaseClass> GetList()
    {
        return new List<BaseClass>();
    }
}

public class UsageExample
{
    // Variable declaration
    private BaseClass myBase = new BaseClass();
}

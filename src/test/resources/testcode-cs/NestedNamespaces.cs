namespace Outer
{
    namespace Inner
    {
        public class MyNestedClass
        {
            public void NestedMethod() {}
        }

        public interface IMyNestedInterface {}
    }

    public class OuterClass
    {
        public int FieldInOuter;
    }
}

namespace AnotherTopLevelNs {
    public class AnotherClass {}
}

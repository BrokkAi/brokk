public class CyclicMethods {
    public void methodA() {
        System.out.println("In method A");
        methodB();
    }

    public void methodB() {
        System.out.println("In method B");
        methodC();
    }

    public void methodC() {
        System.out.println("In method C");
        methodA(); // creates a cycle A->B->C->A
    }
}
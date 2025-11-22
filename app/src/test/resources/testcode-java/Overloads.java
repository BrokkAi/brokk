public class Overloads {

    public void process() {
        System.out.println("no args");
    }

    public void process(int x) {
        System.out.println("int: " + x);
    }

    public void process(String s) {
        System.out.println("String: " + s);
    }

    public void process(int x, String s) {
        System.out.println("int and String: " + x + ", " + s);
    }

    public void handle(int[] arr) {
        System.out.println("int array");
    }

    public void handle(String[] arr) {
        System.out.println("String array");
    }
}

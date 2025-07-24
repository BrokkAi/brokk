public class E {
    public int iField = 10;
    public static int sField = 100;

    public static void sMethod() {
        System.out.println("Static method in E");
    }

    public void iMethod() {
        System.out.println("Instance method in E");
    }

    public int dMethod() {
        return D.field1;
    }
}

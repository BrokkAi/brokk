class AnonymousUsage {
    public void foo() {
        new Runnable() {
            public void run() {
                System.out.println(new A().method2());
            }
        }.run();
    }
}
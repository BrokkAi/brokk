class F {
    public static final Runnable HELLO_FIELD = new Runnable() {
        @Override
        public void run() {
            System.out.println("Hello World");
        }
    };
}
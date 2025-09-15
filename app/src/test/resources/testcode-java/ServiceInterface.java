public interface ServiceInterface {

    // Abstract method
    void processData(String data);

    // Default method with implementation
    default String formatMessage(String message) {
        return "[INFO] " + message;
    }

    // Default method calling another default method
    default void logMessage(String message) {
        System.out.println(formatMessage(message));
    }

    // Static method in interface
    static String getVersion() {
        return "1.0.0";
    }
}
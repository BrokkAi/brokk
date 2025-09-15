public class ServiceImpl implements ServiceInterface {

    private String name;

    public ServiceImpl(String name) {
        this.name = name;
    }

    @Override
    public void processData(String data) {
        logMessage("Processing: " + data + " for " + name);
    }

    // Override default method with custom implementation
    @Override
    public String formatMessage(String message) {
        return "[" + name + "] " + message;
    }

    // Method that uses static interface method
    public void printVersion() {
        System.out.println("Service version: " + ServiceInterface.getVersion());
    }
}
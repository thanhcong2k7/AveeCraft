package android.os;

public class Build {
    public static class VERSION {
        public static final int SDK_INT = 30;
    }
    public static final String MANUFACTURER = "Desktop";
    public static final String MODEL = System.getProperty("os.name", "JVM");
}

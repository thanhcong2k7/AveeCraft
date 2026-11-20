package android.os;

public class SystemClock {
    private static final long START_NANOS = System.nanoTime();
    public static long uptimeMillis() {
        return (System.nanoTime() - START_NANOS) / 1_000_000L;
    }
    public static long elapsedRealtime() {
        return uptimeMillis();
    }
}

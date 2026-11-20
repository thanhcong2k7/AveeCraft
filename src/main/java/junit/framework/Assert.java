package junit.framework;

public class Assert {
    public static void assertTrue(boolean condition) {
        if (!condition) throw new AssertionError("assertTrue failed");
    }
    public static void assertTrue(String msg, boolean condition) {
        if (!condition) throw new AssertionError(msg);
    }
    public static void assertFalse(boolean condition) {
        if (condition) throw new AssertionError("assertFalse failed");
    }
    public static void assertNotNull(Object obj) {
        if (obj == null) throw new AssertionError("assertNotNull failed");
    }
    public static void assertEquals(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected=" + expected + " actual=" + actual);
    }
    public static void assertEquals(String msg, int expected, int actual) {
        if (expected != actual) throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
    }
    public static void fail(String msg) {
        throw new AssertionError(msg);
    }
    public static void assertEquals(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError("expected=" + expected + " actual=" + actual);
        }
    }
    public static void assertEquals(String msg, Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(msg + ": expected=" + expected + " actual=" + actual);
        }
    }
}

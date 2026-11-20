package android.os;

import java.util.concurrent.ConcurrentLinkedQueue;

public class Looper {
    private static final Looper MAIN = new Looper(Thread.currentThread());
    private final Thread thread;
    final ConcurrentLinkedQueue<Runnable> queue = new ConcurrentLinkedQueue<>();

    private Looper(Thread t) { this.thread = t; }
    public static Looper getMainLooper() { return MAIN; }
    public static Looper myLooper() { return Thread.currentThread() == MAIN.thread ? MAIN : null; }
    public Thread getThread() { return thread; }

    public static void drainMainQueue() {
        Runnable r;
        while ((r = MAIN.queue.poll()) != null) {
            try { r.run(); } catch (Throwable t) { t.printStackTrace(); }
        }
    }
}

package android.os;

public class Handler {
    private final Looper looper;

    public Handler() {
        this.looper = Looper.getMainLooper();
    }

    public Handler(Looper looper) {
        this.looper = looper != null ? looper : Looper.getMainLooper();
    }

    public boolean post(Runnable r) {
        if (r != null) {
            looper.queue.add(r);
            return true;
        }
        return false;
    }

    public void removeCallbacksAndMessages(Object token) {}
}

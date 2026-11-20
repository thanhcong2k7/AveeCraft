package android.os;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public abstract class AsyncTask<Params, Progress, Result> {
    public enum Status { PENDING, RUNNING, FINISHED }
    public static final Executor THREAD_POOL_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AsyncTask-Worker");
        t.setDaemon(true);
        return t;
    });

    private volatile Status status = Status.PENDING;
    private volatile boolean cancelled = false;

    protected abstract Result doInBackground(Params... params);
    protected void onPreExecute() {}
    protected void onPostExecute(Result result) {}
    protected void onCancelled(Result result) {}
    protected void onCancelled() {}

    public final Status getStatus() { return status; }
    public final boolean isCancelled() { return cancelled; }
    public final boolean cancel(boolean mayInterruptIfRunning) { cancelled = true; return true; }

    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> executeOnExecutor(Executor exec, Params... params) {
        status = Status.RUNNING;
        onPreExecute();
        exec.execute(() -> {
            Result res = null;
            try {
                if (!cancelled) res = doInBackground(params);
            } finally {
                status = Status.FINISHED;
                final Result fRes = res;
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (cancelled) onCancelled(fRes);
                    else onPostExecute(fRes);
                });
            }
        });
        return this;
    }

    @SafeVarargs
    public final AsyncTask<Params, Progress, Result> execute(Params... params) {
        return executeOnExecutor(THREAD_POOL_EXECUTOR, params);
    }
}

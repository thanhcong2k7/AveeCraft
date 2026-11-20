package com.google.firebase.crashlytics;
public class FirebaseCrashlytics {
    private static final FirebaseCrashlytics INSTANCE = new FirebaseCrashlytics();
    public static FirebaseCrashlytics getInstance() { return INSTANCE; }
    public void log(String msg) {}
    public void recordException(Throwable t) {}
}

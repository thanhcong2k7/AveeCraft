package com.daaw.avee;

import android.content.Context;

public class PlayerCore {
    private static final PlayerCore INSTANCE = new PlayerCore();
    private final Context appContext = new Context();

    public static PlayerCore s() {
        return INSTANCE;
    }

    public Context getAppContext() {
        return appContext;
    }
}

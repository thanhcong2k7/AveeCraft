package com.daaw.avee.Design;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class GeneralDesign {
    public static boolean isFirstLaunch = false;
    public static boolean celebrationContentAvailable = false;
    public static String getCurrentDateString() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }
}

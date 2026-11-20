package com.daaw.avee.Design;

public class AppDesign {
    private static volatile long trackPositionMs = 0L;

    public static void setTrackPositionMs(long ms) {
        trackPositionMs = Math.max(0L, ms);
    }

    public static long getTrackPositionForRendering() {
        return trackPositionMs;
    }
}

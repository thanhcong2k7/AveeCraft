package com.daaw.avee.Design;

public class VisualizerExporterDesign {
    private static volatile boolean exporting = false;
    public static boolean isExporting() { return exporting; }
    public static void setExporting(boolean e) { exporting = e; }
}

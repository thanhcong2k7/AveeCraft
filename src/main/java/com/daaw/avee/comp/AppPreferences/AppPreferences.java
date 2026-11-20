package com.daaw.avee.comp.AppPreferences;

import java.io.File;

public class AppPreferences {
    private static final AppPreferences INSTANCE = new AppPreferences();
    private static volatile File activeWorkspaceFolder = new File(System.getProperty("java.io.tmpdir", "/tmp"), "avee_editor_ws");

    static {
        activeWorkspaceFolder.mkdirs();
    }

    public static AppPreferences createOrGetInstance() {
        return INSTANCE;
    }

    public static void setActiveWorkspaceFolder(File dir) {
        if (dir != null) {
            dir.mkdirs();
            activeWorkspaceFolder = dir;
        }
    }

    public static File getActiveWorkspaceFolder() {
        return activeWorkspaceFolder;
    }

    public static File GetVisualizerFolderFromIdentifier(int id) {
        return activeWorkspaceFolder;
    }
}

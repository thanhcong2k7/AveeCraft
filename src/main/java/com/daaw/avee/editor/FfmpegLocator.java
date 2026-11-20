package com.daaw.avee.editor;

import java.io.File;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves bundled FFmpeg / FFprobe binaries inside standalone AveeCraft distributions
 * before falling back to system PATH.
 */
public final class FfmpegLocator {
    private static volatile String cachedFfmpeg;
    private static volatile String cachedFfprobe;

    private FfmpegLocator() {}

    public static String ffmpeg() {
        String c = cachedFfmpeg;
        if (c == null) {
            c = resolveTool("ffmpeg", "avee.ffmpeg");
            cachedFfmpeg = c;
        }
        return c;
    }

    public static String ffprobe() {
        String c = cachedFfprobe;
        if (c == null) {
            c = resolveTool("ffprobe", "avee.ffprobe");
            cachedFfprobe = c;
        }
        return c;
    }

    private static String resolveTool(String toolName, String sysProp) {
        String override = System.getProperty(sysProp);
        if (override != null && !override.trim().isEmpty()) {
            File f = new File(override.trim());
            if (f.isFile()) {
                ensureExecutable(f);
                return f.getAbsolutePath();
            }
        }

        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String exeName = isWin ? (toolName + ".exe") : toolName;

        List<File> dirs = new ArrayList<>();
        try {
            URI loc = FfmpegLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            File codeFile = new File(loc);
            File codeDir = codeFile.isDirectory() ? codeFile : codeFile.getParentFile();
            addCandidateDirs(dirs, codeDir);
        } catch (Throwable ignored) {}

        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isEmpty()) {
            File jh = new File(javaHome);
            addCandidateDirs(dirs, jh.getParentFile());
            if (jh.getParentFile() != null) {
                addCandidateDirs(dirs, jh.getParentFile().getParentFile());
            }
        }

        addCandidateDirs(dirs, new File("."));

        for (File dir : dirs) {
            if (dir == null) continue;
            File candidate = new File(dir, exeName);
            if (candidate.isFile()) {
                ensureExecutable(candidate);
                return candidate.getAbsolutePath();
            }
        }
        return toolName;
    }

    private static void addCandidateDirs(List<File> out, File base) {
        if (base == null) return;
        out.add(new File(base, "ffmpeg"));
        out.add(new File(new File(base, "app"), "ffmpeg"));
        out.add(new File(new File(new File(base, "lib"), "app"), "ffmpeg"));
        out.add(new File(base, "bin"));
        out.add(base);
    }

    private static void ensureExecutable(File f) {
        try {
            if (!f.canExecute()) {
                f.setExecutable(true, false);
            }
        } catch (Throwable ignored) {}
    }
}

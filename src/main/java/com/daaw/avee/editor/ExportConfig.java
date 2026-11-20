package com.daaw.avee.editor;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Properties;

public class ExportConfig {
    public static class PresetProfile {
        public final String label;
        public final int width;
        public final int height;
        public final int fps;
        public final float videoBitrateMbps;
        public final float audioBitrateKbps;
        public final boolean stereo;
        public final int codecProfileIdx; // 0=High, 1=Main, 2=Baseline

        public PresetProfile(String label, int width, int height, int fps, float videoBitrateMbps,
                             float audioBitrateKbps, boolean stereo, int codecProfileIdx) {
            this.label = label;
            this.width = width;
            this.height = height;
            this.fps = fps;
            this.videoBitrateMbps = videoBitrateMbps;
            this.audioBitrateKbps = audioBitrateKbps;
            this.stereo = stereo;
            this.codecProfileIdx = codecProfileIdx;
        }
    }

    // Mirrors Android VisualizerExporterDesign exportSettingsProfiles + useful desktop presets
    public static final PresetProfile[] PRESETS = {
            new PresetProfile("Custom", 1920, 1080, 60, 18.0f, 320.0f, true, 0),
            new PresetProfile("1080p @60 18.0Mbps 384Kbps H", 1920, 1080, 60, 18.0f, 384.0f, true, 0),
            new PresetProfile("1080p @60 18.0Mbps 384Kbps L", 1920, 1080, 60, 18.0f, 384.0f, true, 1),
            new PresetProfile("1080p @30 12.0Mbps 384Kbps H", 1920, 1080, 30, 12.0f, 384.0f, true, 0),
            new PresetProfile("1080p @30 12.0Mbps 384Kbps L", 1920, 1080, 30, 12.0f, 384.0f, true, 1),
            new PresetProfile("720p @60 12.0Mbps 384Kbps L", 1280, 720, 60, 12.0f, 384.0f, true, 1),
            new PresetProfile("720p @30 7.5Mbps 256Kbps L", 1280, 720, 30, 7.5f, 256.0f, true, 1),
            new PresetProfile("1080x1920 Vertical @60 18.0Mbps H", 1080, 1920, 60, 18.0f, 384.0f, true, 0),
            new PresetProfile("2160p 4K @60 35.0Mbps H", 3840, 2160, 60, 35.0f, 384.0f, true, 0)
    };

    public static final String[] CODECS = {"H.264 (AVC)", "HEVC (H.265)"};
    public static final String[] CODEC_PROFILES = {"High", "Main", "Baseline"};

    public int presetIndex = 1; // 1080p @60 by default
    public GpuBackend.GpuPreference gpuPreference = GpuBackend.GpuPreference.AUTO;
    public int videoCodecIdx = 0;       // 0=H.264, 1=HEVC
    public int videoCodecProfileIdx = 0; // 0=High, 1=Main, 2=Baseline

    public String destinationDir = defaultExportDir();
    public String filename = "aveecraft_export";

    public int width = 1920;
    public int height = 1080;
    public boolean forceCompatibleWH = true;
    public int fps = 60;
    public float videoBitrateMbps = 18.0f;
    public boolean highQualityBlur = true;
    public boolean hideAppLogo = false;

    public boolean exportAudio = true;
    public boolean audioStereo = true;
    public float audioBitrateKbps = 384.0f;
    public long startMs = 0L;
    public long endMs = 15000L;
    public boolean previewOnly = false;

    // Populated by ExportManager before launching ExportWorker
    public String vizPath = "";
    public String audioPath = "";
    public boolean syntheticBeat = false;
    public String outFilePath = "";

    public static String defaultExportDir() {
        File videos = new File(System.getProperty("user.home", "."), "Videos");
        if (videos.isDirectory()) return videos.getAbsolutePath();
        return System.getProperty("user.home", ".");
    }

    public void applyPreset(int idx) {
        if (idx <= 0 || idx >= PRESETS.length) {
            this.presetIndex = 0;
            return;
        }
        PresetProfile p = PRESETS[idx];
        this.presetIndex = idx;
        this.width = p.width;
        this.height = p.height;
        this.fps = p.fps;
        this.videoBitrateMbps = p.videoBitrateMbps;
        this.audioBitrateKbps = p.audioBitrateKbps;
        this.audioStereo = p.stereo;
        this.videoCodecProfileIdx = p.codecProfileIdx;
    }

    public File resolvedOutputFile() {
        String name = (filename == null || filename.trim().isEmpty()) ? "aveecraft_export" : filename.trim();
        if (!name.toLowerCase().endsWith(".mp4")) {
            name = name + ".mp4";
        }
        File dir = new File(destinationDir != null && !destinationDir.isEmpty() ? destinationDir : defaultExportDir());
        if (previewOnly) {
            int dot = name.lastIndexOf('.');
            name = (dot > 0 ? name.substring(0, dot) : name) + "_preview.mp4";
        }
        return new File(dir, name);
    }

    public int normalizedWidth() {
        int w = Math.max(64, width);
        if (forceCompatibleWH) {
            w = Math.max(16, (w + 4) / 8 * 8);
        } else if ((w & 1) != 0) {
            w++;
        }
        return w;
    }

    public int normalizedHeight() {
        int h = Math.max(64, height);
        if (forceCompatibleWH) {
            h = Math.max(16, (h + 4) / 8 * 8);
        } else if ((h & 1) != 0) {
            h++;
        }
        return h;
    }

    public void saveTo(File f) throws IOException {
        Properties p = new Properties();
        p.setProperty("gpuPreference", gpuPreference.name());
        p.setProperty("videoCodecIdx", String.valueOf(videoCodecIdx));
        p.setProperty("videoCodecProfileIdx", String.valueOf(videoCodecProfileIdx));
        p.setProperty("width", String.valueOf(normalizedWidth()));
        p.setProperty("height", String.valueOf(normalizedHeight()));
        p.setProperty("fps", String.valueOf(Math.max(1, fps)));
        p.setProperty("videoBitrateMbps", String.valueOf(videoBitrateMbps));
        p.setProperty("highQualityBlur", String.valueOf(highQualityBlur));
        p.setProperty("hideAppLogo", String.valueOf(hideAppLogo));
        p.setProperty("exportAudio", String.valueOf(exportAudio));
        p.setProperty("audioStereo", String.valueOf(audioStereo));
        p.setProperty("audioBitrateKbps", String.valueOf(audioBitrateKbps));
        p.setProperty("startMs", String.valueOf(startMs));
        p.setProperty("endMs", String.valueOf(endMs));
        p.setProperty("previewOnly", String.valueOf(previewOnly));
        p.setProperty("vizPath", vizPath != null ? vizPath : "");
        p.setProperty("audioPath", audioPath != null ? audioPath : "");
        p.setProperty("syntheticBeat", String.valueOf(syntheticBeat));
        p.setProperty("outFilePath", outFilePath != null ? outFilePath : resolvedOutputFile().getAbsolutePath());
        try (FileOutputStream fos = new FileOutputStream(f)) {
            p.store(fos, "AveeDesktop ExportConfig");
        }
    }

    public static ExportConfig loadFrom(File f) throws IOException {
        Properties p = new Properties();
        try (FileInputStream fis = new FileInputStream(f)) {
            p.load(fis);
        }
        ExportConfig c = new ExportConfig();
        try {
            c.gpuPreference = GpuBackend.GpuPreference.valueOf(p.getProperty("gpuPreference", "AUTO"));
        } catch (Exception ignored) {}
        c.videoCodecIdx = Integer.parseInt(p.getProperty("videoCodecIdx", "0"));
        c.videoCodecProfileIdx = Integer.parseInt(p.getProperty("videoCodecProfileIdx", "0"));
        c.width = Integer.parseInt(p.getProperty("width", "1920"));
        c.height = Integer.parseInt(p.getProperty("height", "1080"));
        c.fps = Integer.parseInt(p.getProperty("fps", "60"));
        c.videoBitrateMbps = Float.parseFloat(p.getProperty("videoBitrateMbps", "18.0"));
        c.highQualityBlur = Boolean.parseBoolean(p.getProperty("highQualityBlur", "true"));
        c.hideAppLogo = Boolean.parseBoolean(p.getProperty("hideAppLogo", "false"));
        c.exportAudio = Boolean.parseBoolean(p.getProperty("exportAudio", "true"));
        c.audioStereo = Boolean.parseBoolean(p.getProperty("audioStereo", "true"));
        c.audioBitrateKbps = Float.parseFloat(p.getProperty("audioBitrateKbps", "384.0"));
        c.startMs = Long.parseLong(p.getProperty("startMs", "0"));
        c.endMs = Long.parseLong(p.getProperty("endMs", "15000"));
        c.previewOnly = Boolean.parseBoolean(p.getProperty("previewOnly", "false"));
        c.vizPath = p.getProperty("vizPath", "");
        c.audioPath = p.getProperty("audioPath", "");
        c.syntheticBeat = Boolean.parseBoolean(p.getProperty("syntheticBeat", "false"));
        c.outFilePath = p.getProperty("outFilePath", "");
        return c;
    }
}

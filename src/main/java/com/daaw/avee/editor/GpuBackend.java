package com.daaw.avee.editor;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class GpuBackend {
    public enum GpuPreference {
        AUTO("Auto (Fastest Available)"),
        INTEGRATED("Integrated GPU"),
        EXTERNAL("External / Dedicated GPU"),
        CPU("CPU Software (libx264 / libx265)");

        public final String baseLabel;
        GpuPreference(String baseLabel) { this.baseLabel = baseLabel; }
    }

    public static class ProbeResult {
        public boolean hasIntegrated;
        public String integratedName = "Integrated GPU";
        public String vaapiNode = "/dev/dri/renderD128";
        public boolean hasVaapiH264;
        public boolean hasVaapiHevc;
        public boolean hasQsvH264;
        public boolean hasQsvHevc;
        public boolean hasAmfH264;
        public boolean hasAmfHevc;

        public boolean hasExternal;
        public String externalName = "Dedicated GPU";
        public boolean hasNvencH264;
        public boolean hasNvencHevc;

        public boolean hasLibx264;
        public boolean hasLibx265;
    }

    private static volatile ProbeResult cachedProbe;

    public static ProbeResult probe() {
        if (cachedProbe != null) return cachedProbe;
        ProbeResult res = new ProbeResult();
        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        try {
            // 1. Check encoders from ffmpeg
            Process encProc = new ProcessBuilder("ffmpeg", "-hide_banner", "-encoders")
                    .redirectErrorStream(true).start();
            String encText;
            try (BufferedReader br = new BufferedReader(new InputStreamReader(encProc.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = br.readLine()) != null) sb.append(line).append('\n');
                encText = sb.toString();
            }
            encProc.waitFor();
            res.hasLibx264 = encText.contains("libx264");
            res.hasLibx265 = encText.contains("libx265");
            boolean listNvencH264 = encText.contains("h264_nvenc");
            boolean listNvencHevc = encText.contains("hevc_nvenc");
            boolean listVaapiH264 = encText.contains("h264_vaapi");
            boolean listVaapiHevc = encText.contains("hevc_vaapi");
            boolean listQsvH264 = encText.contains("h264_qsv");
            boolean listQsvHevc = encText.contains("hevc_qsv");
            boolean listAmfH264 = encText.contains("h264_amf");
            boolean listAmfHevc = encText.contains("hevc_amf");

            // 2. Inspect GPUs (Win32_VideoController on Windows, lspci on Linux)
            try {
                Process gpuProc = isWin
                        ? new ProcessBuilder("powershell", "-NoProfile", "-Command",
                                "(Get-CimInstance Win32_VideoController).Name").redirectErrorStream(true).start()
                        : new ProcessBuilder("lspci").redirectErrorStream(true).start();
                try (BufferedReader br = new BufferedReader(new InputStreamReader(gpuProc.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        String lower = line.toLowerCase(Locale.ROOT).trim();
                        if (lower.isEmpty()) continue;
                        if (isWin || lower.contains("vga compatible") || lower.contains("3d controller") || lower.contains("display controller")) {
                            String desc = (!isWin && line.contains(": ")) ? line.substring(line.indexOf(": ") + 2).trim() : line.trim();
                            if (lower.contains("nvidia")) {
                                res.hasExternal = true;
                                res.externalName = desc;
                            } else if (lower.contains("intel") || lower.contains("amd") || lower.contains("radeon")) {
                                res.hasIntegrated = true;
                                res.integratedName = desc;
                            }
                        }
                    }
                }
                gpuProc.waitFor();
            } catch (Throwable ignored) {}

            // 3. Pick DRM render node for Intel/AMD integrated VA-API on Linux
            if (!isWin) {
                File byPath = new File("/dev/dri/by-path");
                if (byPath.isDirectory()) {
                    File[] links = byPath.listFiles();
                    if (links != null) {
                        for (File l : links) {
                            if (l.getName().contains("00:02.0-render")) {
                                try {
                                    res.vaapiNode = l.getCanonicalPath();
                                } catch (Exception ignored) {}
                            }
                        }
                    }
                }
            }

            // 4. Quick real encode sanity checks so unavailable drivers don't claim hardware support
            if (listNvencH264) {
                res.hasNvencH264 = testEncoder("-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-c:v", "h264_nvenc", "-f", "null", "-");
                if (res.hasNvencH264) res.hasExternal = true;
            }
            if (listNvencHevc) {
                res.hasNvencHevc = res.hasNvencH264 && testEncoder("-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-c:v", "hevc_nvenc", "-f", "null", "-");
            }
            if (!isWin && listVaapiH264 && new File(res.vaapiNode).exists()) {
                res.hasVaapiH264 = testEncoder("-init_hw_device", "vaapi=va:" + res.vaapiNode, "-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-vf", "format=nv12,hwupload", "-c:v", "h264_vaapi", "-rc_mode", "CQP", "-qp", "20", "-f", "null", "-");
                if (res.hasVaapiH264) res.hasIntegrated = true;
            }
            if (!isWin && listVaapiHevc && res.hasVaapiH264) {
                res.hasVaapiHevc = testEncoder("-init_hw_device", "vaapi=va:" + res.vaapiNode, "-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-vf", "format=nv12,hwupload", "-c:v", "hevc_vaapi", "-rc_mode", "CQP", "-qp", "20", "-f", "null", "-");
            }
            if (isWin && listQsvH264) {
                res.hasQsvH264 = testEncoder("-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-c:v", "h264_qsv", "-f", "null", "-");
                if (res.hasQsvH264) res.hasIntegrated = true;
            }
            if (isWin && listQsvHevc && res.hasQsvH264) {
                res.hasQsvHevc = testEncoder("-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-c:v", "hevc_qsv", "-f", "null", "-");
            }
            if (isWin && listAmfH264) {
                res.hasAmfH264 = testEncoder("-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-c:v", "h264_amf", "-f", "null", "-");
                if (res.hasAmfH264) res.hasIntegrated = true;
            }
            if (isWin && listAmfHevc && res.hasAmfH264) {
                res.hasAmfHevc = testEncoder("-f", "lavfi", "-i", "color=c=black:s=256x144:d=0.05", "-c:v", "hevc_amf", "-f", "null", "-");
            }
        } catch (Throwable ignored) {
        }
        cachedProbe = res;
        return res;
    }

    private static boolean testEncoder(String... args) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add("ffmpeg");
            cmd.add("-v");
            cmd.add("error");
            for (String a : args) cmd.add(a);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Throwable t) {
            return false;
        }
    }

    public static GpuPreference resolveEffectiveGpu(GpuPreference pref, ProbeResult p) {
        boolean intHw = p.hasVaapiH264 || p.hasQsvH264 || p.hasAmfH264;
        if (pref == null || pref == GpuPreference.AUTO) {
            if (p.hasExternal && p.hasNvencH264) return GpuPreference.EXTERNAL;
            if (p.hasIntegrated && intHw) return GpuPreference.INTEGRATED;
            return GpuPreference.CPU;
        }
        if (pref == GpuPreference.EXTERNAL && !p.hasExternal) {
            return (p.hasIntegrated && intHw) ? GpuPreference.INTEGRATED : GpuPreference.CPU;
        }
        if (pref == GpuPreference.INTEGRATED && !p.hasIntegrated) {
            return (p.hasExternal && p.hasNvencH264) ? GpuPreference.EXTERNAL : GpuPreference.CPU;
        }
        return pref;
    }

    public static String[] getUiLabels(ProbeResult p) {
        GpuPreference effAuto = resolveEffectiveGpu(GpuPreference.AUTO, p);
        String intHwTag = p.hasVaapiH264 ? "VAAPI" : (p.hasQsvH264 ? "QSV" : (p.hasAmfH264 ? "AMF" : "SW"));
        boolean hasIntHw = p.hasVaapiH264 || p.hasQsvH264 || p.hasAmfH264;
        String autoTarget = effAuto == GpuPreference.EXTERNAL ? shortGpuName(p.externalName) + " / NVENC"
                : effAuto == GpuPreference.INTEGRATED ? shortGpuName(p.integratedName) + " / " + intHwTag
                : "CPU libx264";
        String intLabel = p.hasIntegrated
                ? "Integrated GPU (" + shortGpuName(p.integratedName) + " + " + intHwTag + ")"
                : "Integrated GPU (Not detected)";
        String extLabel = p.hasExternal
                ? "External GPU (" + shortGpuName(p.externalName) + (p.hasNvencH264 ? " + NVENC" : " + SW") + ")"
                : "External / Dedicated GPU (Not detected)";
        return new String[]{
                "Auto (" + autoTarget + ")",
                intLabel,
                extLabel,
                "CPU Software (libx264 / libx265)"
        };
    }

    private static String shortGpuName(String s) {
        if (s == null) return "GPU";
        if (s.contains("[") && s.contains("]")) {
            return s.substring(s.indexOf('[') + 1, s.indexOf(']')).trim();
        }
        return s.length() > 30 ? s.substring(0, 30) + "..." : s;
    }
}

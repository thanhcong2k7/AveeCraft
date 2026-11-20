package com.daaw.avee.editor;

import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import imgui.ImGui;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public class ExportDialogPanel {
    private boolean open = false;
    private final ExportConfig cfg = new ExportConfig();

    private final ImInt presetCombo = new ImInt(cfg.presetIndex);
    private final ImInt gpuCombo = new ImInt(0);
    private final ImInt codecCombo = new ImInt(0);
    private final ImInt profileCombo = new ImInt(0);
    private final ImString destDirStr = new ImString(cfg.destinationDir, 512);
    private final ImString fileNameStr = new ImString(cfg.filename, 256);
    private final ImInt widthVal = new ImInt(cfg.width);
    private final ImInt heightVal = new ImInt(cfg.height);
    private final ImBoolean compatWhChk = new ImBoolean(cfg.forceCompatibleWH);
    private final ImInt fpsVal = new ImInt(cfg.fps);
    private final float[] bitrateArr = new float[]{cfg.videoBitrateMbps};
    private final ImBoolean hqBlurChk = new ImBoolean(cfg.highQualityBlur);
    private final ImBoolean hideLogoChk = new ImBoolean(cfg.hideAppLogo);

    private final ImBoolean exportAudioChk = new ImBoolean(cfg.exportAudio);
    private final ImBoolean stereoChk = new ImBoolean(cfg.audioStereo);
    private final float[] audioBitrateArr = new float[]{cfg.audioBitrateKbps};
    private final float[] startSecArr = new float[]{0f};
    private final float[] endSecArr = new float[]{15f};

    private GpuBackend.ProbeResult probeCache;
    private String[] gpuLabels;

    // Active export background job state
    private volatile boolean exporting = false;
    private volatile Process workerProc = null;
    private volatile int progressCur = 0;
    private volatile int progressTotal = 1;
    private volatile float renderFps = 0f;
    private volatile String activeGpuBanner = "";
    private volatile String activeEncoderBanner = "";
    private volatile String statusLine = "";
    private volatile String errorLine = "";
    private volatile String completedPath = "";
    private volatile long exportStartNs = 0L;

    public void openDialog(DesktopAudioEngine audio) {
        if (probeCache == null) {
            new Thread(() -> {
                GpuBackend.ProbeResult p = GpuBackend.probe();
                probeCache = p;
                gpuLabels = GpuBackend.getUiLabels(p);
            }, "AveeGpuProbe").start();
        }
        cfg.highQualityBlur = audio != null && audio.isHighQualityBlur();
        hqBlurChk.set(cfg.highQualityBlur);
        if (audio != null && audio.hasAudioLoaded()) {
            long durMs = audio.getDurationMs();
            if (durMs > 0) {
                endSecArr[0] = durMs / 1000f;
            }
            String title = audio.getTitle();
            if (title != null && !title.trim().isEmpty()) {
                fileNameStr.set(title.replaceAll("[^a-zA-Z0-9._ -]", "_").trim() + " exported");
            }
        }
        errorLine = "";
        completedPath = "";
        statusLine = "";
        open = true;
    }

    public boolean isExporting() {
        return exporting;
    }

    public void render(CustomScene scene, RendererCore rendererCore, DesktopAudioEngine audio,
                       int currentPreviewW, int currentPreviewH) {
        if (!open) return;

        if (probeCache != null && gpuLabels == null) {
            gpuLabels = GpuBackend.getUiLabels(probeCache);
        }
        String[] activeGpuLabels = gpuLabels != null ? gpuLabels : new String[]{
                "Auto (Probing GPUs...)",
                "Integrated GPU",
                "External / Dedicated GPU",
                "CPU Software (libx264 / libx265)"
        };

        ImGui.setNextWindowSize(560f, 640f, imgui.flag.ImGuiCond.FirstUseEver);
        ImBoolean openRef = new ImBoolean(open);
        int flags = ImGuiWindowFlags.NoCollapse;
        if (ImGui.begin("Export Visualizer Video##AveeExportModal", openRef, flags)) {
            if (exporting) {
                renderExportingView();
            } else {
                renderConfigView(scene, rendererCore, audio, activeGpuLabels, currentPreviewW, currentPreviewH);
            }
        }
        ImGui.end();
        if (!openRef.get() && !exporting) {
            open = false;
        }
    }

    private void renderConfigView(CustomScene scene, RendererCore rendererCore, DesktopAudioEngine audio,
                                  String[] activeGpuLabels, int curW, int curH) {
        if (ImGui.collapsingHeader("Output & Hardware Acceleration", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            if (ImGui.combo("GPU Engine##exp_gpu", gpuCombo, activeGpuLabels)) {
                cfg.gpuPreference = GpuBackend.GpuPreference.values()[gpuCombo.get()];
            }
            if (probeCache != null) {
                GpuBackend.GpuPreference eff = GpuBackend.resolveEffectiveGpu(cfg.gpuPreference, probeCache);
                String activeText = eff == GpuBackend.GpuPreference.EXTERNAL
                        ? "Active pipeline: External GL (" + probeCache.externalName + ") + " + (codecCombo.get() == 1 && probeCache.hasNvencHevc ? "hevc_nvenc" : "h264_nvenc")
                        : eff == GpuBackend.GpuPreference.INTEGRATED
                        ? "Active pipeline: Integrated GL (" + probeCache.integratedName + ") + " + (probeCache.hasVaapiH264 ? (codecCombo.get() == 1 && probeCache.hasVaapiHevc ? "hevc_vaapi" : "h264_vaapi") : "libx264")
                        : "Active pipeline: Software CPU (" + (codecCombo.get() == 1 ? "libx265" : "libx264") + ")";
                ImGui.textDisabled(activeText);
            }

            ImGui.inputText("Folder##exp_dir", destDirStr);
            ImGui.sameLine();
            if (ImGui.button("Browse...##exp_dir_btn")) {
                String chosen = TinyFileDialogs.tinyfd_selectFolderDialog("Select Export Destination Folder", destDirStr.get());
                if (chosen != null && !chosen.trim().isEmpty()) {
                    destDirStr.set(chosen.trim());
                }
            }
            ImGui.inputText("Filename (.mp4)##exp_file", fileNameStr);
        }

        if (ImGui.collapsingHeader("Video Settings (Presets & Codec)", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            String[] presetNames = new String[ExportConfig.PRESETS.length];
            for (int i = 0; i < ExportConfig.PRESETS.length; i++) presetNames[i] = ExportConfig.PRESETS[i].label;
            if (ImGui.combo("Preset##exp_preset", presetCombo, presetNames)) {
                if (presetCombo.get() > 0) {
                    cfg.applyPreset(presetCombo.get());
                    widthVal.set(cfg.width);
                    heightVal.set(cfg.height);
                    fpsVal.set(cfg.fps);
                    bitrateArr[0] = cfg.videoBitrateMbps;
                    audioBitrateArr[0] = cfg.audioBitrateKbps;
                    stereoChk.set(cfg.audioStereo);
                    profileCombo.set(cfg.videoCodecProfileIdx);
                }
            }
            if (ImGui.button("Match Preview (" + curW + "x" + curH + ")")) {
                widthVal.set(curW);
                heightVal.set(curH);
                presetCombo.set(0);
            }
            ImGui.combo("Video Codec##exp_codec", codecCombo, ExportConfig.CODECS);
            ImGui.combo("Codec Profile##exp_prof", profileCombo, ExportConfig.CODEC_PROFILES);

            if (ImGui.inputInt("Width (px)##exp_w", widthVal)) {
                widthVal.set(Math.max(64, Math.min(7680, widthVal.get())));
                presetCombo.set(0);
            }
            if (ImGui.inputInt("Height (px)##exp_h", heightVal)) {
                heightVal.set(Math.max(64, Math.min(4320, heightVal.get())));
                presetCombo.set(0);
            }
            ImGui.checkbox("Force Compatible W/H (multiple of 16)", compatWhChk);
            if (ImGui.inputInt("Frame Rate (FPS)##exp_fps", fpsVal)) {
                fpsVal.set(Math.max(1, Math.min(240, fpsVal.get())));
                presetCombo.set(0);
            }
            if (ImGui.sliderFloat("Bitrate (Mbps)##exp_vbr", bitrateArr, 1.0f, 60.0f, "%.1f")) {
                presetCombo.set(0);
            }
            ImGui.checkbox("High Quality Blur", hqBlurChk);
            ImGui.sameLine();
            ImGui.checkbox("Hide App Logo", hideLogoChk);
        }

        if (ImGui.collapsingHeader("Audio & Time Range", imgui.flag.ImGuiTreeNodeFlags.DefaultOpen)) {
            ImGui.checkbox("Export Audio", exportAudioChk);
            ImGui.sameLine();
            ImGui.checkbox("Stereo", stereoChk);
            ImGui.sliderFloat("Audio Bitrate (Kbps)##exp_abr", audioBitrateArr, 96.0f, 512.0f, "%.0f");

            float maxTrackSec = (audio != null && audio.hasAudioLoaded())
                    ? Math.max(1f, audio.getDurationMs() / 1000f)
                    : 600f;
            ImGui.sliderFloat("Start Time (s)##exp_start", startSecArr, 0f, maxTrackSec, "%.1f");
            ImGui.sliderFloat("End Time (s)##exp_end", endSecArr, 0.5f, maxTrackSec, "%.1f");
            if (endSecArr[0] < startSecArr[0] + 0.5f) {
                endSecArr[0] = startSecArr[0] + 0.5f;
            }
            if (audio != null && audio.hasAudioLoaded()) {
                if (ImGui.button("Use Full Song (" + formatSec(maxTrackSec) + ")")) {
                    startSecArr[0] = 0f;
                    endSecArr[0] = maxTrackSec;
                }
            } else {
                ImGui.textDisabled("No audio file loaded: will render with synthetic test signal.");
            }
        }

        ImGui.separator();
        if (!errorLine.isEmpty()) {
            ImGui.textColored(1f, 0.35f, 0.35f, 1f, errorLine);
        } else if (!completedPath.isEmpty()) {
            ImGui.textColored(0.35f, 1f, 0.45f, 1f, "Exported: " + completedPath);
        } else if (!statusLine.isEmpty()) {
            ImGui.textDisabled(statusLine);
        }

        if (ImGui.button("Export Preview (5s)", 170f, 30f)) {
            startExportJob(true, scene, rendererCore, audio);
        }
        ImGui.sameLine();
        if (ImGui.button("Export Video", 170f, 30f)) {
            startExportJob(false, scene, rendererCore, audio);
        }
        ImGui.sameLine();
        if (ImGui.button("Close", 100f, 30f)) {
            open = false;
        }
    }

    private void renderExportingView() {
        ImGui.text("Exporting Video with Async PBO + Hardware Acceleration...");
        ImGui.separator();
        if (!activeGpuBanner.isEmpty()) ImGui.text("OpenGL GPU: " + activeGpuBanner);
        if (!activeEncoderBanner.isEmpty()) ImGui.text("FFmpeg Encoder: " + activeEncoderBanner);
        ImGui.spacing();

        float frac = progressTotal > 0 ? Math.max(0f, Math.min(1f, (float) progressCur / progressTotal)) : 0f;
        ImGui.progressBar(frac, -1f, 24f, String.format(Locale.US, "%d / %d frames (%.1f%%)", progressCur, progressTotal, frac * 100f));

        double elapsedSec = (System.nanoTime() - exportStartNs) / 1_000_000_000.0;
        double etaSec = (renderFps > 0.1f && progressTotal > progressCur)
                ? (progressTotal - progressCur) / renderFps : 0.0;
        ImGui.text(String.format(Locale.US, "Speed: %.1f fps   Elapsed: %.1fs   ETA: %.1fs", renderFps, elapsedSec, etaSec));
        if (!statusLine.isEmpty()) {
            ImGui.textDisabled(statusLine);
        }
        ImGui.spacing();
        if (ImGui.button("Cancel Export", 160f, 32f)) {
            cancelExport();
        }
    }

    private void startExportJob(boolean previewOnly, CustomScene scene, RendererCore rendererCore, DesktopAudioEngine audio) {
        if (scene == null) {
            errorLine = "No visualizer scene loaded.";
            return;
        }
        syncUiToConfig(previewOnly, audio);
        try {
            File tmpViz = File.createTempFile("avee_export_scene_", ".viz");
            tmpViz.deleteOnExit();
            VizArchiveManager.saveViz(tmpViz, scene, rendererCore);
            cfg.vizPath = tmpViz.getAbsolutePath();

            File outFile = cfg.resolvedOutputFile();
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            cfg.outFilePath = outFile.getAbsolutePath();

            File tmpCfg = File.createTempFile("avee_export_cfg_", ".properties");
            tmpCfg.deleteOnExit();
            cfg.saveTo(tmpCfg);

            launchWorkerSubprocess(tmpCfg, cfg);
        } catch (Exception e) {
            errorLine = "Failed to start export: " + e.getMessage();
            e.printStackTrace();
        }
    }

    private void syncUiToConfig(boolean previewOnly, DesktopAudioEngine audio) {
        cfg.gpuPreference = GpuBackend.GpuPreference.values()[gpuCombo.get()];
        cfg.destinationDir = destDirStr.get().trim();
        cfg.filename = fileNameStr.get().trim();
        cfg.videoCodecIdx = codecCombo.get();
        cfg.videoCodecProfileIdx = profileCombo.get();
        cfg.width = widthVal.get();
        cfg.height = heightVal.get();
        cfg.forceCompatibleWH = compatWhChk.get();
        cfg.fps = fpsVal.get();
        cfg.videoBitrateMbps = bitrateArr[0];
        cfg.highQualityBlur = hqBlurChk.get();
        cfg.hideAppLogo = hideLogoChk.get();
        cfg.exportAudio = exportAudioChk.get();
        cfg.audioStereo = stereoChk.get();
        cfg.audioBitrateKbps = audioBitrateArr[0];
        cfg.startMs = Math.round(startSecArr[0] * 1000.0);
        cfg.endMs = Math.round(endSecArr[0] * 1000.0);
        cfg.previewOnly = previewOnly;
        cfg.audioPath = (audio != null && audio.getAudioFile() != null) ? audio.getAudioFile().getAbsolutePath() : "";
        cfg.syntheticBeat = audio == null || audio.isSyntheticBeat();
    }

    private void launchWorkerSubprocess(File cfgFile, ExportConfig activeCfg) throws Exception {
        String binDir = System.getProperty("java.home") + File.separator + "bin" + File.separator;
        File winJavaw = new File(binDir + "javaw.exe");
        File winJava = new File(binDir + "java.exe");
        String javaBin = winJavaw.isFile() ? winJavaw.getAbsolutePath()
                : (winJava.isFile() ? winJava.getAbsolutePath() : (binDir + "java"));
        String cp = System.getProperty("java.class.path");
        List<String> cmd = new ArrayList<>();
        cmd.add(javaBin);
        cmd.add("-cp");
        cmd.add(cp);
        cmd.add("com.daaw.avee.editor.ExportWorker");
        cmd.add(cfgFile.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        boolean isWin = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        GpuBackend.ProbeResult probe = GpuBackend.probe();
        GpuBackend.GpuPreference eff = GpuBackend.resolveEffectiveGpu(activeCfg.gpuPreference, probe);
        if (!isWin) {
            if (eff == GpuBackend.GpuPreference.EXTERNAL) {
                pb.environment().put("__NV_PRIME_RENDER_OFFLOAD", "1");
                pb.environment().put("__GLX_VENDOR_LIBRARY_NAME", "nvidia");
            } else if (eff == GpuBackend.GpuPreference.INTEGRATED) {
                pb.environment().put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                pb.environment().put("DRI_PRIME", "0");
            }
        }

        exporting = true;
        progressCur = 0;
        progressTotal = Math.max(1, (int) Math.round(((activeCfg.previewOnly ? Math.min(5000L, activeCfg.endMs - activeCfg.startMs) : Math.max(1000L, activeCfg.endMs - activeCfg.startMs)) / 1000.0) * activeCfg.fps));
        renderFps = 0f;
        activeGpuBanner = "Initializing OpenGL context...";
        activeEncoderBanner = "Preparing FFmpeg...";
        statusLine = "Warming up textures & shaders...";
        errorLine = "";
        completedPath = "";
        exportStartNs = System.nanoTime();

        Process p = pb.start();
        workerProc = p;
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("GPU ")) {
                        activeGpuBanner = line.substring(4);
                    } else if (line.startsWith("ENCODER ")) {
                        activeEncoderBanner = line.substring(8);
                    } else if (line.startsWith("PROGRESS ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 4) {
                            progressCur = Integer.parseInt(parts[1]);
                            progressTotal = Integer.parseInt(parts[2]);
                            renderFps = Float.parseFloat(parts[3]);
                            statusLine = "Rendering & encoding frames...";
                        }
                    } else if (line.startsWith("DONE ")) {
                        completedPath = line.substring(5);
                        statusLine = "Export completed!";
                    } else if (line.startsWith("ERROR ")) {
                        errorLine = line.substring(6);
                    }
                }
            } catch (Exception e) {
                errorLine = "Worker I/O error: " + e.getMessage();
            } finally {
                try {
                    int rc = p.waitFor();
                    if (rc != 0 && errorLine.isEmpty()) {
                        errorLine = "Export stopped (exit=" + rc + ")";
                    }
                } catch (InterruptedException ignored) {}
                workerProc = null;
                exporting = false;
            }
        }, "AveeExportMonitor");
        t.setDaemon(true);
        t.start();
    }

    public void cancelExport() {
        Process p = workerProc;
        if (p != null) {
            p.destroyForcibly();
        }
        statusLine = "Cancelling export...";
    }

    private static String formatSec(float sec) {
        int s = Math.max(0, Math.round(sec));
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }
}

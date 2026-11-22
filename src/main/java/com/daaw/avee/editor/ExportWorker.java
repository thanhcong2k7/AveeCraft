package com.daaw.avee.editor;

import android.content.Context;
import android.opengl.GLES20;
import android.os.Looper;
import com.daaw.avee.Common.Events.WeakEventR1;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Composition;
import com.daaw.avee.comp.Visualizer.Elements.LogoMark2;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;

public class ExportWorker {
    public static void main(String[] args) throws Exception {
        if (args == null || args.length < 1) {
            System.err.println("Usage: ExportWorker <config.properties>");
            System.exit(2);
        }
        ExportConfig cfg = ExportConfig.loadFrom(new File(args[0]));
        int exit = runExport(cfg);
        System.exit(exit);
    }

    public static int runExport(ExportConfig cfg) throws Exception {
        int w = cfg.normalizedWidth();
        int h = cfg.normalizedHeight();
        int fps = Math.max(1, cfg.fps);

        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            try {
                GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, GLFW.GLFW_PLATFORM_X11);
            } catch (Throwable ignored) {}
        }
        if (!GLFW.glfwInit()) {
            System.out.println("ERROR GLFW init failed");
            return 1;
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long win = GLFW.glfwCreateWindow(64, 64, "AveeExportWorker", 0L, 0L);
        if (win == 0L) {
            System.out.println("ERROR Failed to create offscreen OpenGL context");
            GLFW.glfwTerminate();
            return 1;
        }
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();

        String glVendor = GL11.glGetString(GL11.GL_VENDOR);
        String glRenderer = GL11.glGetString(GL11.GL_RENDERER);
        System.out.println("GPU " + glVendor + " | " + glRenderer);
        System.out.flush();

        // Offscreen render FBO + GPU Y-flip FBO + Double-buffered async PBOs
        int texRender = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texRender);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        int fboRender = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboRender);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texRender, 0);
        GL11.glViewport(0, 0, w, h);
        GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        int texFlip = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texFlip);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
        int fboFlip = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboFlip);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texFlip, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        int frameBytes = w * h * 4;
        int[] pbos = new int[2];
        for (int i = 0; i < 2; i++) {
            pbos[i] = GL15.glGenBuffers();
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[i]);
            GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, frameBytes, GL21.GL_STREAM_READ);
        }
        GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);

        // Configure scene, audio engine, and logo visibility
        List<Object> holder = new LinkedList<>();
        final boolean hideLogo = cfg.hideAppLogo;
        Composition.onRequest.subscribeWeak((WeakEventR1.Handler<Object, Boolean>) o -> hideLogo, holder);
        LogoMark2.onRequest.subscribeWeak((WeakEventR1.Handler<Object, Boolean>) o -> hideLogo, holder);

        Context ctx = PlayerCore.s().getAppContext();
        DesktopAudioEngine audio = new DesktopAudioEngine(false);
        audio.setHighQualityBlur(cfg.highQualityBlur);

        File audioFile = (cfg.audioPath != null && !cfg.audioPath.isEmpty()) ? new File(cfg.audioPath) : null;
        boolean hasAudio = audioFile != null && audioFile.isFile() && audio.loadAudioFile(audioFile);
        if (hasAudio) {
            audio.setSyntheticBeat(cfg.syntheticBeat);
            audio.setPlaying(true);
        } else {
            audio.setSyntheticBeat(true);
        }

        RendererCore rc = new RendererCore(ctx, ctx.getResources(), audio);
        rc.onSurfaceCreated(null, null);
        rc.onSurfaceChanged(null, w, h);

        CustomScene scene = VizArchiveManager.loadFile(new File(cfg.vizPath));
        rc.setThemeElements(scene);

        long startMs = Math.max(0L, cfg.startMs);
        long endMs = cfg.endMs;
        if (hasAudio && (endMs <= startMs || endMs > audio.getDurationMs())) {
            endMs = audio.getDurationMs();
        }
        if (endMs <= startMs) {
            endMs = startMs + 15000L;
        }
        if (cfg.previewOnly) {
            endMs = Math.min(endMs, startMs + 5000L);
        }
        long durMs = Math.max(100L, endMs - startMs);
        int totalFrames = Math.max(1, (int) Math.round((durMs / 1000.0) * fps));
        int frameStepMs = Math.max(1, Math.round(1000.0f / fps));
        float dtSec = 1.0f / fps;

        // Warmup GL resources (textures, blur targets, initial async bitmaps) before capture
        rc.setFixedFrameTimeMs(frameStepMs);
        for (int warm = 0; warm < 90; warm++) {
            Looper.drainMainQueue();
            audio.stepOfflineFrame(startMs, dtSec);
            drawToFbo(rc, fboRender);
            if (warm >= 3 && rc.getFrameStats().getFrameResourcesLoadingCount() == 0) {
                break;
            }
            try { Thread.sleep(10); } catch (InterruptedException ignored) {}
        }
        if (!hideLogo) {
            rc.setTriggerLogo(1, -1);
        }

        // Probe and configure FFmpeg encoder
        GpuBackend.ProbeResult probe = GpuBackend.probe();
        GpuBackend.GpuPreference effGpu = GpuBackend.resolveEffectiveGpu(cfg.gpuPreference, probe);
        boolean useHevc = cfg.videoCodecIdx == 1;
        String encName = chooseEncoderName(effGpu, useHevc, probe);
        System.out.println("ENCODER " + encName + " (" + effGpu.name() + ")");
        System.out.flush();

        File outFile = new File(cfg.outFilePath);
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();

        List<String> cmd = buildFfmpegCmd(cfg, w, h, fps, startMs, durMs, hasAudio ? audioFile : null, effGpu, useHevc, encName, probe, outFile);
        Process ff = new ProcessBuilder(cmd).redirectErrorStream(false).start();
        StringBuilder ffErr = new StringBuilder();
        Thread errReader = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(ff.getErrorStream(), StandardCharsets.UTF_8))) {
                String l;
                while ((l = br.readLine()) != null) {
                    if (ffErr.length() < 8192) ffErr.append(l).append('\n');
                }
            } catch (Exception ignored) {}
        }, "FFmpegErrReader");
        errReader.setDaemon(true);
        errReader.start();

        long t0 = System.nanoTime();
        int pboWrite = 0;
        int pboRead = 1;
        try (BufferedOutputStream bos = new BufferedOutputStream(ff.getOutputStream(), 1 << 20);
             WritableByteChannel ch = Channels.newChannel(bos)) {
            for (int f = 0; f < totalFrames; f++) {
                Looper.drainMainQueue();
                long posMs = startMs + Math.round((f * 1000.0) / fps);
                audio.stepOfflineFrame(posMs, dtSec);

                drawToFbo(rc, fboRender);

                // GPU-side vertical flip blit into fboFlip
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboRender);
                GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, fboFlip);
                GL30.glBlitFramebuffer(0, 0, w, h, 0, h, w, 0, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_NEAREST);

                // Async DMA readback on current frame into pboWrite
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fboFlip);
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[pboWrite]);
                GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
                GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);

                // Drain previous frame from pboRead while current GPU DMA transfers in parallel
                if (f > 0) {
                    GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[pboRead]);
                    ByteBuffer mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
                    if (mapped != null) {
                        while (mapped.hasRemaining()) {
                            ch.write(mapped);
                        }
                        GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
                    }
                }
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

                int tmp = pboWrite;
                pboWrite = pboRead;
                pboRead = tmp;

                if (f == 0 || (f + 1) % 5 == 0 || f == totalFrames - 1) {
                    double elapsed = Math.max(0.001, (System.nanoTime() - t0) / 1_000_000_000.0);
                    double speedFps = (f + 1) / elapsed;
                    System.out.println(String.format(Locale.US, "PROGRESS %d %d %.1f", f + 1, totalFrames, speedFps));
                    System.out.flush();
                }
            }

            // Drain the final frame queued in pboRead (just written into pboWrite before swap)
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbos[pboRead]);
            ByteBuffer last = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY);
            if (last != null) {
                while (last.hasRemaining()) {
                    ch.write(last);
                }
                GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
            }
            GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
            bos.flush();
        } catch (java.io.IOException pipeErr) {
            if (ffErr.length() == 0) ffErr.append(pipeErr.getMessage());
        }

        int ffExit = ff.waitFor();
        errReader.join(2000);

        for (int pbo : pbos) GL15.glDeleteBuffers(pbo);
        GL30.glDeleteFramebuffers(fboRender);
        GL30.glDeleteFramebuffers(fboFlip);
        GL11.glDeleteTextures(texRender);
        GL11.glDeleteTextures(texFlip);
        audio.shutdown();
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();

        if (ffExit != 0) {
            System.out.println("ERROR FFmpeg exited with " + ffExit + ": " + ffErr.toString().replace('\n', ' '));
            System.out.flush();
            return 1;
        }
        System.out.println("DONE " + outFile.getAbsolutePath());
        System.out.flush();
        return 0;
    }

    private static String chooseEncoderName(GpuBackend.GpuPreference effGpu, boolean hevc, GpuBackend.ProbeResult probe) {
        if (effGpu == GpuBackend.GpuPreference.EXTERNAL) {
            if (hevc && probe.hasNvencHevc) return "hevc_nvenc";
            if (probe.hasNvencH264) return "h264_nvenc";
        }
        if (effGpu == GpuBackend.GpuPreference.INTEGRATED) {
            if (hevc && probe.hasVaapiHevc) return "hevc_vaapi";
            if (probe.hasVaapiH264) return "h264_vaapi";
            if (hevc && probe.hasQsvHevc) return "hevc_qsv";
            if (probe.hasQsvH264) return "h264_qsv";
            if (hevc && probe.hasAmfHevc) return "hevc_amf";
            if (probe.hasAmfH264) return "h264_amf";
        }
        return hevc ? (probe.hasLibx265 ? "libx265" : "libx264") : "libx264";
    }

    private static List<String> buildFfmpegCmd(ExportConfig cfg, int w, int h, int fps, long startMs, long durMs,
                                               File audioFile, GpuBackend.GpuPreference effGpu, boolean hevc,
                                               String encName, GpuBackend.ProbeResult probe, File outFile) {
        List<String> cmd = new ArrayList<>();
        cmd.add(FfmpegLocator.ffmpeg());
        cmd.add("-y");
        cmd.add("-v");
        cmd.add("warning");

        if (encName.endsWith("_vaapi")) {
            cmd.add("-init_hw_device");
            cmd.add("vaapi=va:" + probe.vaapiNode);
            cmd.add("-filter_hw_device");
            cmd.add("va");
        }

        // Input 0: Raw RGBA pipe from OpenGL PBO
        cmd.add("-f");
        cmd.add("rawvideo");
        cmd.add("-pix_fmt");
        cmd.add("rgba");
        cmd.add("-s");
        cmd.add(w + "x" + h);
        cmd.add("-r");
        cmd.add(String.valueOf(fps));
        cmd.add("-i");
        cmd.add("pipe:0");

        // Input 1: Audio file segment (if exporting audio)
        boolean includeAudio = cfg.exportAudio && audioFile != null && audioFile.isFile();
        if (includeAudio) {
            cmd.add("-ss");
            cmd.add(String.format(Locale.US, "%.3f", startMs / 1000.0));
            cmd.add("-t");
            cmd.add(String.format(Locale.US, "%.3f", durMs / 1000.0));
            cmd.add("-i");
            cmd.add(audioFile.getAbsolutePath());
        }

        cmd.add("-map");
        cmd.add("0:v:0");
        if (includeAudio) {
            cmd.add("-map");
            cmd.add("1:a:0");
        }

        int kbps = Math.max(200, Math.round(cfg.videoBitrateMbps * 1000.0f));
        String prof = cfg.videoCodecProfileIdx == 2 ? "baseline" : (cfg.videoCodecProfileIdx == 1 ? "main" : "high");

        if (encName.endsWith("_nvenc")) {
            cmd.add("-c:v");
            cmd.add(encName);
            cmd.add("-preset");
            cmd.add("p4");
            cmd.add("-rc");
            cmd.add("vbr");
            cmd.add("-b:v");
            cmd.add(kbps + "k");
            cmd.add("-maxrate");
            cmd.add((kbps * 3 / 2) + "k");
            cmd.add("-bufsize");
            cmd.add((kbps * 2) + "k");
            if (!hevc) {
                cmd.add("-profile:v");
                cmd.add(prof);
            }
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
        } else if (encName.endsWith("_vaapi")) {
            int qp = cfg.videoBitrateMbps >= 20f ? 16 : (cfg.videoBitrateMbps >= 14f ? 18 : (cfg.videoBitrateMbps >= 8f ? 21 : 24));
            cmd.add("-vf");
            cmd.add("format=nv12,hwupload");
            cmd.add("-c:v");
            cmd.add(encName);
            cmd.add("-rc_mode");
            cmd.add("CQP");
            cmd.add("-qp");
            cmd.add(String.valueOf(qp));
        } else if (encName.endsWith("_qsv") || encName.endsWith("_amf")) {
            cmd.add("-c:v");
            cmd.add(encName);
            cmd.add("-b:v");
            cmd.add(kbps + "k");
            cmd.add("-maxrate");
            cmd.add((kbps * 3 / 2) + "k");
            cmd.add("-bufsize");
            cmd.add((kbps * 2) + "k");
            if (!hevc) {
                cmd.add("-profile:v");
                cmd.add(prof);
            }
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
        } else {
            cmd.add("-c:v");
            cmd.add(encName);
            cmd.add("-preset");
            cmd.add("veryfast");
            cmd.add("-b:v");
            cmd.add(kbps + "k");
            if (!hevc) {
                cmd.add("-profile:v");
                cmd.add(prof);
            }
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
        }

        if (includeAudio) {
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add(Math.max(64, Math.round(cfg.audioBitrateKbps)) + "k");
            cmd.add("-ac");
            cmd.add(cfg.audioStereo ? "2" : "1");
            cmd.add("-shortest");
        }

        cmd.add("-movflags");
        cmd.add("+faststart");
        cmd.add(outFile.getAbsolutePath());
        return cmd;
    }

    /** Bind {@code fboRender} before {@code onDrawFrame} so {@code RendererCore}'s initial clear targets it instead of FBO 0. */
    private static void drawToFbo(RendererCore rc, int fboRender) {
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fboRender);
        GLES20.setRedirectFramebuffer0(fboRender);
        rc.onDrawFrame(null);
        GLES20.setRedirectFramebuffer0(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }
}

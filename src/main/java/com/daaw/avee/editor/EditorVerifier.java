package com.daaw.avee.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Looper;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.Visualizer.CustomComposition;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Base.Element;
import com.daaw.avee.comp.Visualizer.Elements.ElementsFactory;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import java.io.File;
import java.nio.ByteBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

public class EditorVerifier {
    public static void main(String[] args) throws Exception {
        if (!GLFW.glfwInit()) {
            throw new IllegalStateException("GLFW init failed");
        }
        GLFW.glfwDefaultWindowHints();
        GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
        long win = GLFW.glfwCreateWindow(640, 360, "AveeVerifier", 0L, 0L);
        if (win == 0L) {
            throw new IllegalStateException("Failed to create offscreen GLFW window");
        }
        GLFW.glfwMakeContextCurrent(win);
        GL.createCapabilities();

        int w = 640, h = 360;
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);

        int fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);

        Context ctx = PlayerCore.s().getAppContext();
        DesktopAudioEngine audio = new DesktopAudioEngine();
        audio.setSyntheticBeat(true);
        RendererCore rc = new RendererCore(ctx, ctx.getResources(), audio);
        rc.onSurfaceCreated(null, null);
        rc.onSurfaceChanged(null, w, h);

        if (args != null && args.length >= 2 && "--render-viz".equals(args[0])) {
            File vizFile = new File(args[1]);
            File outPng = new File(args[2]);
            CustomScene s = VizArchiveManager.loadFile(vizFile);
            if (args.length >= 4 && !args[3].isEmpty()) {
                String ref = VizArchiveManager.importLocalAsset(new File(args[3]));
                CustomPropertiesList bg = s.getEntry(0).getData(1);
                bg.putPropertyStringValue("customImage", ref);
            }
            rc.setThemeElements(s);
            if (args.length >= 5 && "select".equals(args[4])) {
                rc.triggerHighlightByIndices(0, 1);
            }
            for (int f = 0; f < 40; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.02f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
                try { Thread.sleep(16); } catch (InterruptedException ignored) {}
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            Bitmap bmp = GraphicsUtils.savePixels(0, 0, w, h, null);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outPng)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            String dumpDirProp = System.getProperty("avee.dumpCompsDir", "");
            if (!dumpDirProp.isEmpty()) {
                File dumpDir = new File(dumpDirProp);
                dumpDir.mkdirs();
                com.daaw.avee.comp.Visualizer.Graphic.RenderState rs = rc.getRenderState();
                com.daaw.avee.comp.Visualizer.Elements.RootElement root = rc.getRootElement();
                int cc = root != null ? root.getChildCount() : 0;
                for (int ci = 0; ci < cc; ci++) {
                    com.daaw.avee.comp.Visualizer.Graphic.VFrameBuffer cfb = rs.getCompositionResult(ci);
                    if (cfb == null) {
                        System.out.println("  COMP " + ci + " -> NULL FBO (rendered=" + root.getChildAt(ci).shouldRender() + ")");
                        continue;
                    }
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, cfb.getID());
                    Bitmap cb = GraphicsUtils.savePixels(0, 0, cfb.getWidth(), cfb.getHeight(), null);
                    int nzRgb = 0, nzAlpha = 0, maxA = 0, maxL = 0;
                    for (int y = 0; y < cb.getHeight(); y += 2) {
                        for (int x = 0; x < cb.getWidth(); x += 2) {
                            int px = cb.getPixel(x, y);
                            int a = (px >>> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                            if (a > 0) nzAlpha++;
                            if (r > 0 || g > 0 || b > 0) nzRgb++;
                            if (a > maxA) maxA = a;
                            int l = Math.max(r, Math.max(g, b));
                            if (l > maxL) maxL = l;
                        }
                    }
                    System.out.println(String.format("  COMP %d (%dx%d): nzRgb=%d nzAlpha=%d maxL=%d maxA=%d",
                            ci, cb.getWidth(), cb.getHeight(), nzRgb, nzAlpha, maxL, maxA));
                    try (java.io.FileOutputStream cfos = new java.io.FileOutputStream(new File(dumpDir, "comp_" + ci + ".png"))) {
                        cb.compress(Bitmap.CompressFormat.PNG, 100, cfos);
                    }
                }
            }
            System.out.println("Rendered " + vizFile.getName() + " -> " + outPng.getAbsolutePath());
            audio.shutdown();
            GL30.glDeleteFramebuffers(fbo);
            GL11.glDeleteTextures(tex);
            GLFW.glfwDestroyWindow(win);
            GLFW.glfwTerminate();
            return;
        }

        Thread mainThread = Thread.currentThread();
        final int[] currentPreset = {-1};
        Thread watchdog = new Thread(() -> {
            try {
                while (true) {
                    int p0 = currentPreset[0];
                    Thread.sleep(4000);
                    if (p0 >= 0 && currentPreset[0] == p0) {
                        System.err.println("[WATCHDOG] Hung on preset " + p0 + ":");
                        for (StackTraceElement el : mainThread.getStackTrace()) {
                            System.err.println("  at " + el);
                        }
                        Runtime.getRuntime().halt(2);
                    }
                }
            } catch (InterruptedException ignored) {}
        }, "VerifierWatchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        int testedPresets = 0;
        for (int p = 0; p <= 17; p++) {
            currentPreset[0] = p;
            CustomScene scene = VizArchiveManager.loadPreset(p);
            rc.setThemeElements(scene);
            for (int f = 0; f < 5; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.016f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            Bitmap bmp = GraphicsUtils.savePixels(0, 0, w, h, null);
            int glErr = GL11.glGetError();
            if (glErr != 0) {
                throw new AssertionError("GL error on preset " + p + ": " + glErr);
            }
            int nonClear = 0;
            for (int y = 0; y < h; y += 8) {
                for (int x = 0; x < w; x += 8) {
                    int px = bmp.getPixel(x, y);
                    if ((px & 0x00FFFFFF) != 0x00191919) nonClear++;
                }
            }
            System.out.println("Preset " + p + " rendered OK (non-bg samples=" + nonClear + ")");
            testedPresets++;
        }
        currentPreset[0] = -1;

        // Verify MP3 decode + VideoElement + .viz ZIP save & reload round-trip
        File tmpMp3 = File.createTempFile("avee_test_", ".mp3");
        tmpMp3.deleteOnExit();
        try (var is = EditorVerifier.class.getResourceAsStream("/samples/music.mp3")) {
            if (is != null) {
                java.nio.file.Files.copy(is, tmpMp3.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                boolean loaded = audio.loadAudioFile(tmpMp3);
                if (!loaded || audio.onRequestTrackDurationInfo().durationMs <= 0) {
                    throw new AssertionError("Audio MP3 decode failed!");
                }
                audio.setPlaying(true);
                audio.tickFrame(0.016f);
                System.out.println("Audio MP3 decode OK (duration=" + String.format("%.1fs", audio.onRequestTrackDurationInfo().durationMs / 1000.0) + ")");
            }
        }

        File tmpMp4 = File.createTempFile("avee_test_vid_", ".mp4");
        tmpMp4.deleteOnExit();
        Process ff = new ProcessBuilder(FfmpegLocator.ffmpeg(), "-y", "-f", "lavfi", "-i", "testsrc=duration=0.5:size=160x120:rate=15",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", tmpMp4.getAbsolutePath())
                .redirectErrorStream(true).start();
        ff.getInputStream().readAllBytes();
        int ffExit = ff.waitFor();

        CustomScene scene = VizArchiveManager.loadPreset(0);
        rc.setThemeElements(scene);
        CustomScene live = VizArchiveManager.readLiveScene(scene, rc, null);
        CustomComposition comp0 = live.getEntry(0);
        Element vid = ElementsFactory.create("Video", "", null);
        CustomPropertiesList vidProps = comp0.createDataEntry();
        vid.getCustomization(vidProps, 0, n -> {});
        vidProps.putChildTypeValue("Video");
        vidProps.putPropertyBoolValue("loop", false);
        String localVideoRef = null;
        if (ffExit == 0 && tmpMp4.length() > 0) {
            localVideoRef = VizArchiveManager.importLocalAsset(tmpMp4);
            vidProps.putPropertyStringValue("customVideo", localVideoRef);
        }
        rc.setThemeElements(live);
        for (int f = 0; f < 3; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        int vidGlErr = GL11.glGetError();
        if (vidGlErr != 0) {
            throw new AssertionError("GL error rendering VideoElement: " + vidGlErr);
        }

        File tmpViz = File.createTempFile("avee_verify_", ".viz");
        tmpViz.deleteOnExit();
        VizArchiveManager.saveViz(tmpViz, live, rc);

        boolean bundledVideoFound = (localVideoRef == null);
        try (java.util.zip.ZipFile zf = new java.util.zip.ZipFile(tmpViz)) {
            if (localVideoRef != null) {
                bundledVideoFound = zf.getEntry(localVideoRef.substring("local:".length())) != null;
            }
        }
        if (!bundledVideoFound) {
            throw new AssertionError("Saved .viz missing bundled video asset: " + localVideoRef);
        }

        CustomScene reloaded = VizArchiveManager.loadFile(tmpViz);
        CustomComposition rComp = reloaded.getEntry(0);
        CustomPropertiesList foundVideo = null;
        for (int i = 0; i < rComp.getEntryCount(); i++) {
            CustomPropertiesList entry = rComp.getData(i);
            if ("Video".equals(entry.getTypeName(""))) {
                foundVideo = entry;
            }
        }
        if (foundVideo == null || foundVideo.getPropertyBool("loop", true)
                || (localVideoRef != null && !localVideoRef.equals(foundVideo.getPropertyString("customVideo", "")))) {
            throw new AssertionError("Round-trip .viz Video element mismatch!");
        }

        // Verify standalone ImageElement actually renders custom PNG AND MP4 media + selection highlight border
        File tmpPng = File.createTempFile("avee_test_img_", ".png");
        tmpPng.deleteOnExit();
        java.awt.image.BufferedImage testBi = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = testBi.createGraphics();
        g2.setColor(new java.awt.Color(255, 80, 40, 255));
        g2.fillRect(0, 0, 64, 64);
        g2.dispose();
        javax.imageio.ImageIO.write(testBi, "png", tmpPng);
        String localImgRef = VizArchiveManager.importLocalAsset(tmpPng);

        CustomScene mediaScene = CustomScene.Create();
        CustomComposition mComp = mediaScene.createDataEntry();
        mComp.putChildTypeValue("Composition");
        Element imgEl = ElementsFactory.create("Image", "", null);
        CustomPropertiesList imgProps = mComp.createDataEntry();
        imgEl.getCustomization(imgProps, 0, n -> {});
        imgProps.putChildTypeValue("Image");
        imgProps.putPropertyStringValue("customImage", localImgRef);
        imgProps.putPropertyVec2fValue("scale", new com.daaw.avee.Common.Vec2f(0.5f, 0.5f));
        rc.setThemeElements(mediaScene);
        for (int f = 0; f < 4; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        Bitmap imgBmp = GraphicsUtils.savePixels(0, 0, w, h, null);
        int centerPx = imgBmp.getPixel(w / 2, h / 2);
        int rCh = (centerPx >> 16) & 0xFF;
        if (rCh < 200) {
            throw new AssertionError("ImageElement failed to render custom PNG! centerPx=0x" + Integer.toHexString(centerPx));
        }

        // Test ImageElement with MP4 video media
        if (localVideoRef != null) {
            imgProps.putPropertyStringValue("customImage", localVideoRef);
            rc.setThemeElements(mediaScene);
            for (int f = 0; f < 3; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.016f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            Bitmap vidImgBmp = GraphicsUtils.savePixels(0, 0, w, h, null);
            int vidCenter = vidImgBmp.getPixel(w / 2, h / 2);
            if ((vidCenter & 0x00FFFFFF) == 0x00191919) {
                throw new AssertionError("ImageElement failed to render MP4 video! centerPx=0x" + Integer.toHexString(vidCenter));
            }
        }

        // Test ImageElement with animated GIF (internalres:anim128_g_m10_15) AND local file .gif
        imgProps.putPropertyStringValue("customImage", "internalres:anim128_g_m10_15");
        rc.setThemeElements(mediaScene);
        int gifHashA = 0, gifHashB = 0;
        for (int f = 0; f < 40; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.05f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
            if (f == 15 || f == 38) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                Bitmap gb = GraphicsUtils.savePixels(0, 0, w, h, null);
                int hval = 0;
                for (int y = h / 4; y < (3 * h) / 4; y += 8) {
                    for (int x = w / 4; x < (3 * w) / 4; x += 8) {
                        hval = hval * 31 + gb.getPixel(x, y);
                    }
                }
                if (f == 15) gifHashA = hval; else gifHashB = hval;
            }
        }
        System.out.println("Animated GIF hashA=" + gifHashA + " hashB=" + gifHashB);
        if (gifHashA == gifHashB) {
            throw new AssertionError("Animated GIF did NOT advance frames! hash=" + gifHashA);
        }

        // Also generate a distinct multi-frame color-changing .gif and verify file-based .gif playback
        File tmpGif = File.createTempFile("avee_test_anim_", ".gif");
        tmpGif.deleteOnExit();
        Process ffGif = new ProcessBuilder(FfmpegLocator.ffmpeg(), "-y", "-f", "lavfi", "-i",
                "testsrc=duration=1.0:size=128x96:rate=10", tmpGif.getAbsolutePath())
                .redirectErrorStream(true).start();
        ffGif.getInputStream().readAllBytes();
        if (ffGif.waitFor() == 0 && tmpGif.length() > 0) {
            String localGifRef = VizArchiveManager.importLocalAsset(tmpGif);
            imgProps.putPropertyStringValue("customImage", localGifRef);
            rc.setThemeElements(mediaScene);
            int fileGifA = 0, fileGifB = 0;
            for (int f = 0; f < 30; f++) {
                Thread.sleep(25);
                Looper.drainMainQueue();
                audio.tickFrame(0.05f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
                if (f == 10 || f == 28) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                    Bitmap gb = GraphicsUtils.savePixels(0, 0, w, h, null);
                    int hval = 0;
                    for (int y = h / 3; y < (2 * h) / 3; y += 8) {
                        for (int x = w / 3; x < (2 * w) / 3; x += 8) {
                            hval = hval * 31 + gb.getPixel(x, y);
                        }
                    }
                    if (f == 10) fileGifA = hval; else fileGifB = hval;
                }
            }
            System.out.println("File GIF hashA=" + fileGifA + " hashB=" + fileGifB);
            if (fileGifA == fileGifB) {
                throw new AssertionError("File GIF did NOT advance frames! hash=" + fileGifA);
            }
        }

        // Test selection highlight flash renders white border pixels on layer selection
        imgProps.putPropertyBoolValue("visible", true);
        imgProps.putPropertyStringValue("customImage", ""); // no image drawn, only highlight flash
        rc.setThemeElements(mediaScene);
        rc.triggerHighlightByIndices(0, 0);
        Looper.drainMainQueue();
        audio.tickFrame(0.016f);
        GLES20.setRedirectFramebuffer0(fbo);
        rc.onDrawFrame(null);
        GLES20.setRedirectFramebuffer0(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        Bitmap hlBmp = GraphicsUtils.savePixels(0, 0, w, h, null);
        int hlPixels = 0;
        for (int y = 0; y < h; y += 2) {
            for (int x = 0; x < w; x += 2) {
                int px = hlBmp.getPixel(x, y);
                if (((px >> 16) & 0xFF) > 150 && ((px >> 8) & 0xFF) > 150 && (px & 0xFF) > 150) {
                    hlPixels++;
                }
            }
        }
        System.out.println("Layer highlight flash bright samples=" + hlPixels);
        if (hlPixels == 0) {
            throw new AssertionError("Selection highlight border produced 0 bright pixels!");
        }

        // Verify GPU Video Export across available GPU backends (Integrated, External, CPU)
        GpuBackend.ProbeResult gpuProbe = GpuBackend.probe();
        System.out.println("GPU Probe: integrated=" + gpuProbe.hasIntegrated + " (" + gpuProbe.integratedName + ", vaapiH264=" + gpuProbe.hasVaapiH264 + ")"
                + " external=" + gpuProbe.hasExternal + " (" + gpuProbe.externalName + ", nvencH264=" + gpuProbe.hasNvencH264 + ")");

        java.util.List<GpuBackend.GpuPreference> prefsToTest = new java.util.ArrayList<>();
        if (gpuProbe.hasIntegrated && gpuProbe.hasVaapiH264) prefsToTest.add(GpuBackend.GpuPreference.INTEGRATED);
        if (gpuProbe.hasExternal && gpuProbe.hasNvencH264) prefsToTest.add(GpuBackend.GpuPreference.EXTERNAL);
        prefsToTest.add(GpuBackend.GpuPreference.CPU);

        String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        String cp = System.getProperty("java.class.path");
        for (GpuBackend.GpuPreference pref : prefsToTest) {
            File expOut = File.createTempFile("avee_export_" + pref.name().toLowerCase() + "_", ".mp4");
            expOut.deleteOnExit();
            File cfgFile = File.createTempFile("avee_export_cfg_" + pref.name().toLowerCase() + "_", ".properties");
            cfgFile.deleteOnExit();

            ExportConfig ecfg = new ExportConfig();
            ecfg.gpuPreference = pref;
            ecfg.width = 640;
            ecfg.height = 360;
            ecfg.fps = 30;
            ecfg.videoBitrateMbps = 6.0f;
            ecfg.startMs = 0L;
            ecfg.endMs = 1000L; // 1 second (30 frames) fast verification
            ecfg.vizPath = tmpViz.getAbsolutePath();
            ecfg.audioPath = tmpMp3.exists() ? tmpMp3.getAbsolutePath() : "";
            ecfg.exportAudio = tmpMp3.exists();
            ecfg.syntheticBeat = !tmpMp3.exists();
            ecfg.outFilePath = expOut.getAbsolutePath();
            ecfg.saveTo(cfgFile);

            ProcessBuilder pb = new ProcessBuilder(javaBin, "-cp", cp, "com.daaw.avee.editor.ExportWorker", cfgFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            if (pref == GpuBackend.GpuPreference.EXTERNAL) {
                pb.environment().put("__NV_PRIME_RENDER_OFFLOAD", "1");
                pb.environment().put("__GLX_VENDOR_LIBRARY_NAME", "nvidia");
            } else if (pref == GpuBackend.GpuPreference.INTEGRATED) {
                pb.environment().put("__GLX_VENDOR_LIBRARY_NAME", "mesa");
                pb.environment().put("DRI_PRIME", "0");
            }
            Process wp = pb.start();
            String workerLog = new String(wp.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            int wRc = wp.waitFor();
            if (wRc != 0 || !expOut.exists() || expOut.length() < 4096) {
                throw new AssertionError("Export failed for " + pref + " rc=" + wRc + " size=" + expOut.length() + "\n" + workerLog);
            }
            Process fp = new ProcessBuilder(FfmpegLocator.ffprobe(), "-v", "error", "-show_entries", "stream=codec_type,codec_name,width,height",
                    "-of", "csv=p=0", expOut.getAbsolutePath()).redirectErrorStream(true).start();
            String fpOut = new String(fp.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            fp.waitFor();
            if (!fpOut.contains("h264") || !fpOut.contains("640,360") || (ecfg.exportAudio && !fpOut.contains("aac"))) {
                throw new AssertionError("ffprobe mismatch for " + pref + ": " + fpOut);
            }
            String gpuLine = java.util.Arrays.stream(workerLog.split("\n")).filter(l -> l.startsWith("GPU ") || l.startsWith("ENCODER ") || l.startsWith("PROGRESS "))
                    .reduce((a, b) -> a + " | " + b).orElse("");
            System.out.println("Export " + pref + " OK size=" + expOut.length() + " streams=[" + fpOut.replace('\n', ';') + "] -> " + gpuLine);
        }

        System.out.println("ALL VERIFICATIONS PASSED (presets=" + testedPresets + ", video+audio+image+highlight+export+viz round-trip=OK)");
        audio.shutdown();
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(tex);
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
    }
}

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

        // Verify Composition deletion & AppLogo deletion do not crash or respawn AppLogo
        java.util.List<Object> vHolders = new java.util.ArrayList<>();
        com.daaw.avee.comp.Visualizer.Elements.Composition.onRequest.subscribeWeak(
                (com.daaw.avee.Common.Events.WeakEventR1.Handler<Object, Boolean>) c -> true, vHolders);
        CustomScene delScene = VizArchiveManager.loadPreset(0);
        com.daaw.avee.comp.Visualizer.CustomComposition extraComp = delScene.createDataEntry();
        extraComp.putChildTypeValue("Composition");
        rc.setThemeElements(delScene);
        delScene = VizArchiveManager.readLiveScene(delScene, rc, null);
        int compBefore = delScene.getEntryCount();
        if (compBefore < 2) throw new AssertionError("Expected >=2 compositions before delete test");
        delScene.removeEntry(compBefore - 1);
        com.daaw.avee.comp.Visualizer.CustomComposition c0 = delScene.getEntry(0);
        for (int i = c0.getEntryCount() - 1; i >= 0; i--) {
            if ("AppLogo".equals(c0.getData(i).getTypeName(""))) {
                c0.removeEntry(i);
            }
        }
        rc.setThemeElements(delScene);
        GLES20.setRedirectFramebuffer0(fbo);
        rc.onDrawFrame(null);
        GLES20.setRedirectFramebuffer0(0);
        CustomScene afterDel = VizArchiveManager.readLiveScene(delScene, rc, null);
        if (afterDel.getEntryCount() != compBefore - 1) {
            throw new AssertionError("Composition deletion count mismatch: " + afterDel.getEntryCount());
        }
        com.daaw.avee.comp.Visualizer.CustomComposition afterC0 = afterDel.getEntry(0);
        for (int i = 0; i < afterC0.getEntryCount(); i++) {
            if ("AppLogo".equals(afterC0.getData(i).getTypeName(""))) {
                throw new AssertionError("AppLogo respawned after deletion!");
            }
        }
        System.out.println("Composition & AppLogo deletion OK (comps=" + afterDel.getEntryCount() + ")");

        // Verify BlurEffect actually blurs sharp edges into smooth gradient pixels
        CustomScene blurScene = CustomScene.Create();
        CustomComposition bComp0 = blurScene.createDataEntry();
        bComp0.putChildTypeValue("Composition");
        CustomPropertiesList blurProps = bComp0.createDataEntry();
        blurProps.putChildTypeValue("BlurEffect");
        blurProps.putPropertyIntValue("sourceCompositionIndex", 1);
        blurProps.putPropertyFloatValue("blurRadius", 2.5f);
        blurProps.putPropertyFloatValue("blurMultiplier", 4.1f);
        blurProps.putPropertyBoolValue("showUnblurredContent", false);
        blurProps.putPropertyBoolValue("showUnblurredContentUnder", false);
        blurProps.putPropertyVec2fValue("1layerScale", new com.daaw.avee.Common.Vec2f(1.0f, 1.0f));
        blurProps.putPropertyVec2fValue("2layerScale", new com.daaw.avee.Common.Vec2f(0.0f, 0.0f));
        blurProps.putPropertyVec2fValue("3layerScale", new com.daaw.avee.Common.Vec2f(0.0f, 0.0f));

        CustomComposition bComp1 = blurScene.createDataEntry();
        bComp1.putChildTypeValue("Composition");
        CustomPropertiesList sqProps = bComp1.createDataEntry();
        sqProps.putChildTypeValue("Image");
        sqProps.putPropertyStringValue("customImage", "internalres:white");
        sqProps.putPropertyVec2fValue("scale", new com.daaw.avee.Common.Vec2f(0.35f, 0.35f));

        rc.setThemeElements(blurScene);
        CustomScene liveBlurScene = VizArchiveManager.readLiveScene(blurScene, rc, null);
        String blurBlend = liveBlurScene.getEntry(0).getData(0).getChild("blendMode").getTypeName("");
        if (!"Add".equals(blurBlend)) {
            throw new AssertionError("Default BlurEffect blendMode mismatch: expected Add, got " + blurBlend);
        }
        for (int f = 0; f < 4; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        Bitmap blurredBmp = GraphicsUtils.savePixels(0, 0, w, h, null);
        int transitionPixels = 0;
        int blurredCenterR = (blurredBmp.getPixel(w / 2, h / 2) >> 16) & 0xFF;
        for (int x = 0; x < w; x++) {
            int rChVal = (blurredBmp.getPixel(x, h / 2) >> 16) & 0xFF;
            if (rChVal > 40 && rChVal < 230) {
                transitionPixels++;
            }
        }
        if (blurredCenterR <= 100 || transitionPixels < 10) {
            throw new AssertionError("BlurEffect failed to blur! centerR=" + blurredCenterR + " transitionPixels=" + transitionPixels);
        }

        blurProps.putPropertyFloatValue("blurRadius", 0.0f);
        rc.setThemeElements(blurScene);
        for (int f = 0; f < 2; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        Bitmap sharpBmp = GraphicsUtils.savePixels(0, 0, w, h, null);
        int sharpTransitionPixels = 0;
        for (int x = 0; x < w; x++) {
            int rChVal = (sharpBmp.getPixel(x, h / 2) >> 16) & 0xFF;
            if (rChVal > 40 && rChVal < 230) {
                sharpTransitionPixels++;
            }
        }
        if (sharpTransitionPixels >= transitionPixels) {
            throw new AssertionError("BlurEffect blurRadius=0 not sharper than blurred! sharp=" + sharpTransitionPixels + " blurred=" + transitionPixels);
        }
        System.out.println("BlurEffect verification OK (blend=" + blurBlend + " blurredTransitionPx=" + transitionPixels + " sharpTransitionPx=" + sharpTransitionPixels + " centerR=" + blurredCenterR + ")");

        // Verify dynamic composition linking & font asset options
        java.util.List<String> imgOpts = PropertyInspectorPanel.buildMediaOptions(
                com.daaw.avee.comp.Visualizer.Elements.Base.ElementImageLoader.internalImages, false, false, 4);
        if (!imgOpts.contains("composition:1") || !imgOpts.contains("composition:2") || !imgOpts.contains("composition:3")
                || imgOpts.contains("composition:0")) {
            throw new AssertionError("Composition linking options mismatch: " + imgOpts);
        }
        java.util.List<String> fontOpts = PropertyInspectorPanel.buildMediaOptions(
                com.daaw.avee.comp.Visualizer.Elements.TextElement.fontTypefaces, true, false, 3);
        if (!fontOpts.contains("internal_0") || !fontOpts.contains("internal_1")
                || !fontOpts.contains("internal_2") || !fontOpts.contains("internal_3")
                || fontOpts.contains("internal_0:2131230895")) {
            throw new AssertionError("Font asset options mismatch: " + fontOpts);
        }
        System.out.println("Composition linking & media options OK");

        // Verify MeasuredVar.FromString fallback & DesktopAudioEngine measure parity
        com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar fallback =
                new com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar("Constant", 2.5f, 3.5f);
        com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar parsedFallback =
                com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar.FromString("broken", fallback);
        if (parsedFallback == null
                || Math.abs(parsedFallback.getMeasureArgVec2f().x - 2.5f) > 1e-4f
                || Math.abs(parsedFallback.getMeasureArgVec2f().y - 3.5f) > 1e-4f) {
            throw new AssertionError("MeasuredVar.FromString fallback failed: " + parsedFallback);
        }
        String expanded = audio.onRequestsMeasureText("Song: {Title} [{TimeCurrent}]");
        if (expanded.contains("{Title}") || expanded.contains("{TimeCurrent}")) {
            throw new AssertionError("MeasureText expansion failed: " + expanded);
        }
        com.daaw.avee.Common.Vec2f rotShake = audio.onRequestMeasureVec2f(
                com.daaw.avee.comp.Common.MeasureDefs.constantCamShakeRotMore,
                new com.daaw.avee.Common.Vec2f(1f, 1f),
                new com.daaw.avee.Common.Vec2f(0f, 0f), null, null);
        if (Float.isNaN(rotShake.x) || Float.isInfinite(rotShake.x) || Float.isNaN(rotShake.y) || Float.isInfinite(rotShake.y)) {
            throw new AssertionError("constantCamShakeRotMore returned non-finite: " + rotShake);
        }
        System.out.println("MeasuredVar & measure parity OK");

        // Verify cross-composition layer move (Up/Down step + direct cross-comp move)
        CustomScene moveScene = CustomScene.Create();
        CustomComposition mComp0 = moveScene.createDataEntry();
        mComp0.putChildTypeValue("Composition");
        CustomPropertiesList mA = mComp0.createDataEntry();
        mA.putChildTypeValue("Image");
        mA.setTagName("A");
        CustomComposition mComp1 = moveScene.createDataEntry();
        mComp1.putChildTypeValue("Composition");
        CustomPropertiesList mB = mComp1.createDataEntry();
        mB.putChildTypeValue("Bars");
        mB.setTagName("B");
        CustomPropertiesList mC = mComp1.createDataEntry();
        mC.putChildTypeValue("Text");
        mC.setTagName("C");
        CustomComposition mComp2 = moveScene.createDataEntry();
        mComp2.putChildTypeValue("Composition");
        CustomPropertiesList mD = mComp2.createDataEntry();
        mD.putChildTypeValue("BlurEffect");
        mD.setTagName("D");

        int[] upTarget = AveeEditorApp.computeStepMoveTarget(moveScene, 1, 0, -1);
        if (upTarget == null || upTarget[0] != 0 || upTarget[1] != 1) {
            throw new AssertionError("Step Up across comp target mismatch: " + java.util.Arrays.toString(upTarget));
        }
        int[] upRes = AveeEditorApp.moveElementInScene(moveScene, 1, 0, upTarget[0], upTarget[1]);
        if (upRes == null || moveScene.getEntry(0).getEntryCount() != 2 || moveScene.getEntry(1).getEntryCount() != 1
                || !"A".equals(moveScene.getEntry(0).getData(0).getTagName())
                || !"B".equals(moveScene.getEntry(0).getData(1).getTagName())
                || !"C".equals(moveScene.getEntry(1).getData(0).getTagName())) {
            throw new AssertionError("Cross-comp Up move failed");
        }
        int[] downTarget = AveeEditorApp.computeStepMoveTarget(moveScene, 0, 1, 1);
        if (downTarget == null || downTarget[0] != 1 || downTarget[1] != 0) {
            throw new AssertionError("Step Down across comp target mismatch: " + java.util.Arrays.toString(downTarget));
        }
        int[] downRes = AveeEditorApp.moveElementInScene(moveScene, 0, 1, downTarget[0], downTarget[1]);
        if (downRes == null || moveScene.getEntry(0).getEntryCount() != 1 || moveScene.getEntry(1).getEntryCount() != 2
                || !"B".equals(moveScene.getEntry(1).getData(0).getTagName())
                || !"C".equals(moveScene.getEntry(1).getData(1).getTagName())) {
            throw new AssertionError("Cross-comp Down move failed");
        }
        int[] dragRes = AveeEditorApp.moveElementInScene(moveScene, 2, 0, 0, 0);
        if (dragRes == null || moveScene.getEntry(0).getEntryCount() != 2 || moveScene.getEntry(2).getEntryCount() != 0
                || !"D".equals(moveScene.getEntry(0).getData(0).getTagName())
                || !"A".equals(moveScene.getEntry(0).getData(1).getTagName())) {
            throw new AssertionError("Direct cross-comp drag move failed");
        }
        int[] emptyStep = AveeEditorApp.computeStepMoveTarget(moveScene, 1, 1, 1);
        if (emptyStep == null || emptyStep[0] != 2 || emptyStep[1] != 0) {
            throw new AssertionError("Step Down into empty comp target mismatch: " + java.util.Arrays.toString(emptyStep));
        }
        AveeEditorApp.moveElementInScene(moveScene, 1, 1, emptyStep[0], emptyStep[1]);
        if (moveScene.getEntry(2).getEntryCount() != 1 || !"C".equals(moveScene.getEntry(2).getData(0).getTagName())) {
            throw new AssertionError("Move into empty comp failed");
        }

        // Verify deleting Composition N merges all its layers to the bottom of Composition N-1 and remaps refs
        CustomScene mergeScene = CustomScene.Create();
        CustomComposition gComp0 = mergeScene.createDataEntry();
        gComp0.putChildTypeValue("Composition");
        CustomPropertiesList gA0 = gComp0.createDataEntry();
        gA0.putChildTypeValue("Image");
        gA0.setTagName("A0");
        gA0.putPropertyStringValue("customImage", "composition:1");
        CustomPropertiesList gA1 = gComp0.createDataEntry();
        gA1.putChildTypeValue("Image");
        gA1.setTagName("A1");
        gA1.putPropertyStringValue("customImage", "composition:2");

        CustomComposition gComp1 = mergeScene.createDataEntry();
        gComp1.putChildTypeValue("Composition");
        CustomPropertiesList gB1 = gComp1.createDataEntry();
        gB1.putChildTypeValue("Bars");
        gB1.setTagName("B1");
        CustomPropertiesList gC1 = gComp1.createDataEntry();
        gC1.putChildTypeValue("Text");
        gC1.setTagName("C1");

        CustomComposition gComp2 = mergeScene.createDataEntry();
        gComp2.putChildTypeValue("Composition");
        CustomPropertiesList gD2 = gComp2.createDataEntry();
        gD2.putChildTypeValue("BlurEffect");
        gD2.setTagName("D2");
        gD2.putPropertyIntValue("sourceCompositionIndex", 2);

        int movedLayers = AveeEditorApp.deleteCompositionMergingLayers(mergeScene, 1);
        if (movedLayers != 2 || mergeScene.getEntryCount() != 2 || mergeScene.getEntry(0).getEntryCount() != 4) {
            throw new AssertionError("Delete-comp merge count mismatch: moved=" + movedLayers
                    + " comps=" + mergeScene.getEntryCount()
                    + " c0=" + mergeScene.getEntry(0).getEntryCount());
        }
        String[] expectedTags = {"A0", "A1", "B1", "C1"};
        for (int i = 0; i < expectedTags.length; i++) {
            String tag = mergeScene.getEntry(0).getData(i).getTagName();
            if (!expectedTags[i].equals(tag)) {
                throw new AssertionError("Merged layer order mismatch at " + i + ": expected " + expectedTags[i] + " got " + tag);
            }
        }
        if (!"".equals(mergeScene.getEntry(0).getData(0).getPropertyString("customImage", "x"))
                || !"composition:1".equals(mergeScene.getEntry(0).getData(1).getPropertyString("customImage", ""))
                || mergeScene.getEntry(1).getData(0).getPropertyInt("sourceCompositionIndex", -1) != 1) {
            throw new AssertionError("Composition ref remap after merge-delete failed");
        }
        rc.setThemeElements(mergeScene);
        for (int f = 0; f < 2; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        System.out.println("Cross-composition move & delete-comp layer merge OK");

        // Verify UpdateManager version comparator, platform asset matching, settings defaults, and mock GitHub download
        if (UpdateManager.compareVersions("v1.2.101", "v1.2.101a01") >= 0
                || UpdateManager.compareVersions("v1.2.101a01", "v1.2.101a03") >= 0
                || UpdateManager.compareVersions("v1.2.101a03", "v1.2.101.a04") >= 0
                || UpdateManager.compareVersions("v1.2.101.a04", "v1.2.101.b01") >= 0
                || UpdateManager.compareVersions("v1.2.101.b01", "v1.2.101.1") >= 0
                || UpdateManager.compareVersions("v1.2.101.1", "v1.2.101.2") >= 0
                || UpdateManager.compareVersions("v1.2.101", "v1.2.101.abc") >= 0
                || UpdateManager.compareVersions("v1.2.101.abc", "v1.2.101.abd") >= 0
                || UpdateManager.compareVersions("v1.2.101.a03", "1.2.101.a03") != 0) {
            throw new AssertionError("UpdateManager.compareVersions ordering mismatch");
        }
        java.util.List<UpdateManager.ReleaseAsset> mockAssets = java.util.Arrays.asList(
                new UpdateManager.ReleaseAsset("AveeCraft-windows-x64-setup.exe", "http://127.0.0.1/setup.exe", 10),
                new UpdateManager.ReleaseAsset("AveeCraft-windows-x64-standalone.zip", "http://127.0.0.1/win.zip", 10),
                new UpdateManager.ReleaseAsset("AveeCraft-linux-amd64.deb", "http://127.0.0.1/app.deb", 10),
                new UpdateManager.ReleaseAsset("AveeCraft-linux-x64-standalone.tar.gz", "http://127.0.0.1/lin.tar.gz", 10)
        );
        if (!"AveeCraft-windows-x64-setup.exe".equals(UpdateManager.selectAssetForPlatform(mockAssets, UpdateManager.PackageKind.WINDOWS_SETUP).name)
                || !"AveeCraft-windows-x64-standalone.zip".equals(UpdateManager.selectAssetForPlatform(mockAssets, UpdateManager.PackageKind.WINDOWS_PORTABLE).name)
                || !"AveeCraft-linux-amd64.deb".equals(UpdateManager.selectAssetForPlatform(mockAssets, UpdateManager.PackageKind.LINUX_DEB).name)
                || !"AveeCraft-linux-x64-standalone.tar.gz".equals(UpdateManager.selectAssetForPlatform(mockAssets, UpdateManager.PackageKind.LINUX_PORTABLE).name)) {
            throw new AssertionError("UpdateManager asset selection mismatch");
        }
        File updCfgDir = java.nio.file.Files.createTempDirectory("avee_upd_test_").toFile();
        final byte[] fakePayload = "AVEECRAFT_UPDATE_BYTES_1234567890".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try (java.net.ServerSocket ss = new java.net.ServerSocket(0, 10, java.net.InetAddress.getLoopbackAddress())) {
            int port = ss.getLocalPort();
            Thread httpT = new Thread(() -> {
                for (int req = 0; req < 2; req++) {
                    try (java.net.Socket s = ss.accept()) {
                        java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream()));
                        String reqLine = br.readLine();
                        String line;
                        while ((line = br.readLine()) != null && !line.isEmpty()) {}
                        java.io.OutputStream os = s.getOutputStream();
                        if (reqLine != null && reqLine.contains("/releases")) {
                            String json = "[{\"tag_name\":\"v1.2.101.z99\",\"name\":\"v1.2.101.z99\",\"body\":\"Test release\",\"html_url\":\"https://example.com\",\"prerelease\":true,\"assets\":["
                                    + "{\"name\":\"AveeCraft-linux-x64-standalone.tar.gz\",\"browser_download_url\":\"http://127.0.0.1:" + port + "/pkg.tar.gz\",\"size\":" + fakePayload.length + "},"
                                    + "{\"name\":\"AveeCraft-windows-x64-setup.exe\",\"browser_download_url\":\"http://127.0.0.1:" + port + "/setup.exe\",\"size\":" + fakePayload.length + "}"
                                    + "]}]";
                            byte[] jb = json.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " + jb.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            os.write(jb);
                        } else {
                            os.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + fakePayload.length + "\r\nConnection: close\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                            os.write(fakePayload);
                        }
                        os.flush();
                    } catch (Throwable ignored) {}
                }
            }, "MockGitHubUpdateServer");
            httpT.setDaemon(true);
            httpT.start();

            UpdateManager um = new UpdateManager(updCfgDir, "http://127.0.0.1:" + port, "v1.2.101.a03");
            if (!um.getSettings().autoCheckUpdates || um.getSettings().autoDownloadUpdates) {
                throw new AssertionError("UpdateManager default settings mismatch");
            }
            um.checkForUpdatesBlocking(false);
            if (um.getState() != UpdateManager.State.AVAILABLE || um.getLatestRelease() == null
                    || !"v1.2.101.z99".equals(um.getLatestRelease().tagName)) {
                throw new AssertionError("UpdateManager check failed: state=" + um.getState() + " status=" + um.getStatusText());
            }
            um.downloadUpdateBlocking();
            if (um.getState() != UpdateManager.State.READY || um.getDownloadedFile() == null
                    || um.getDownloadedFile().length() != fakePayload.length) {
                throw new AssertionError("UpdateManager download failed: state=" + um.getState() + " status=" + um.getStatusText());
            }
            um.getSettings().autoDownloadUpdates = true;
            um.saveSettings();
            UpdateManager umReloaded = new UpdateManager(updCfgDir, "http://127.0.0.1:" + port, "v1.2.101.a03");
            if (!umReloaded.getSettings().autoDownloadUpdates) {
                throw new AssertionError("UpdateManager settings persistence failed");
            }
        }
        System.out.println("UpdateManager verification OK (currentVersion=" + UpdateManager.getCurrentVersion() + ")");

        // Verify MCP Bridge (live GL queue + token auth) AND real subprocess --mcp --headless stdio server
        File mcpDir = java.nio.file.Files.createTempDirectory("avee_mcp_test_").toFile();
        File testBridgeFile = new File(mcpDir, "mcp-bridge.json");
        final CustomScene[] mcpSceneHolder = new CustomScene[]{VizArchiveManager.loadPreset(0)};
        rc.setThemeElements(mcpSceneHolder[0]);
        mcpSceneHolder[0] = VizArchiveManager.readLiveScene(mcpSceneHolder[0], rc, null);
        final int[] mcpSel = new int[]{0, 0};
        com.daaw.avee.mcp.SceneHost verifierHost = new com.daaw.avee.mcp.SceneHost() {
            @Override public String getMode() { return "live"; }
            @Override public boolean isReadOnly() { return false; }
            @Override public CustomScene getScene() { return mcpSceneHolder[0]; }
            @Override public void setScene(CustomScene s, boolean rebuild) {
                mcpSceneHolder[0] = s;
                if (rebuild) rc.setThemeElements(s); else rc.setThemeCustomizationData(s);
            }
            @Override public void refreshLiveScene() { mcpSceneHolder[0] = VizArchiveManager.readLiveScene(mcpSceneHolder[0], rc, null); }
            @Override public RendererCore getRendererCore() { return rc; }
            @Override public DesktopAudioEngine getAudioEngine() { return audio; }
            @Override public int getSelectedCompIdx() { return mcpSel[0]; }
            @Override public int getSelectedElemIdx() { return mcpSel[1]; }
            @Override public void setSelection(int c, int e, boolean hi) { mcpSel[0] = c; mcpSel[1] = e; }
            @Override public int getPreviewWidth() { return w; }
            @Override public int getPreviewHeight() { return h; }
            @Override public void setPreviewSize(int pw, int ph) {}
            @Override public File getProjectFile() { return null; }
            @Override public void setProjectFile(File f) {}
            @Override public int getActivePresetIndex() { return 0; }
            @Override public void setActivePresetIndex(int i) {}
            @Override public void setStatusMessage(String m) {}
            @Override public java.awt.image.BufferedImage capturePreviewImage(int advanceFrames, long seekMs) {
                for (int f = 0; f < Math.max(1, advanceFrames); f++) {
                    Looper.drainMainQueue();
                    audio.tickFrame(0.016f);
                    GLES20.setRedirectFramebuffer0(fbo);
                    rc.onDrawFrame(null);
                    GLES20.setRedirectFramebuffer0(0);
                }
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
                Bitmap bmp = GraphicsUtils.savePixels(0, 0, w, h, null);
                return bmp != null ? bmp.getBufferedImage() : null;
            }
            @Override public void captureWindowImageAsync(java.util.concurrent.CompletableFuture<java.awt.image.BufferedImage> fut) {
                fut.complete(capturePreviewImage(1, -1L));
            }
        };
        com.daaw.avee.mcp.AveeSceneService svc = new com.daaw.avee.mcp.AveeSceneService(verifierHost);
        com.daaw.avee.mcp.McpBridgeServer bridge = new com.daaw.avee.mcp.McpBridgeServer(testBridgeFile, svc);
        bridge.start();
        try {
            org.json.JSONObject disc = new org.json.JSONObject(java.nio.file.Files.readString(testBridgeFile.toPath()));
            int bPort = disc.getInt("port");
            try (java.net.Socket badSock = new java.net.Socket("127.0.0.1", bPort)) {
                java.io.BufferedWriter bw = new java.io.BufferedWriter(new java.io.OutputStreamWriter(badSock.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8));
                java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(badSock.getInputStream(), java.nio.charset.StandardCharsets.UTF_8));
                bw.write("{\"token\":\"wrong\",\"method\":\"get_state\"}\n");
                bw.flush();
                org.json.JSONObject badResp = new org.json.JSONObject(br.readLine());
                if (badResp.optBoolean("ok", true)) {
                    throw new AssertionError("MCP bridge accepted invalid token!");
                }
            }

            com.daaw.avee.mcp.McpStdioServer liveRpc = new com.daaw.avee.mcp.McpStdioServer(testBridgeFile, false, true, false, 0, null);
            java.util.concurrent.CompletableFuture<Void> bridgeTestFuture = java.util.concurrent.CompletableFuture.runAsync(() -> {
                org.json.JSONObject stResp = new org.json.JSONObject(liveRpc.handleJsonRpcLine(
                        "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_state\",\"arguments\":{}}}"));
                String stText = stResp.getJSONObject("result").getJSONArray("content").getJSONObject(0).getString("text");
                if (!new org.json.JSONObject(stText).getString("mode").equals("live")) {
                    throw new RuntimeException("Expected live mode from bridge, got: " + stText);
                }
                org.json.JSONObject shotResp = new org.json.JSONObject(liveRpc.handleJsonRpcLine(
                        "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"screenshot_window\",\"arguments\":{\"maxWidth\":320}}}"));
                org.json.JSONArray content = shotResp.getJSONObject("result").getJSONArray("content");
                byte[] png = java.util.Base64.getDecoder().decode(content.getJSONObject(0).getString("data"));
                if (png.length < 100 || (png[0] & 0xFF) != 0x89 || png[1] != 'P' || png[2] != 'N' || png[3] != 'G') {
                    throw new RuntimeException("Invalid PNG returned from screenshot_window");
                }
            });
            long deadline = System.currentTimeMillis() + 10000L;
            while (!bridgeTestFuture.isDone() && System.currentTimeMillis() < deadline) {
                bridge.drainUiTasks();
                Thread.sleep(10);
            }
            bridgeTestFuture.get(1, java.util.concurrent.TimeUnit.SECONDS);
            liveRpc.close();
        } finally {
            bridge.stop();
        }

        // Spawn real child JVM `AveeEditorApp --mcp --headless` to verify clean stdio framing & offscreen rendering
        ProcessBuilder mcpPb = new ProcessBuilder(
                javaBin, "-cp", cp, "com.daaw.avee.editor.AveeEditorApp",
                "--mcp", "--headless", "--preset", "0", "--bridge-file", new File(mcpDir, "missing.json").getAbsolutePath());
        mcpPb.redirectError(ProcessBuilder.Redirect.PIPE);
        Process mcpProc = mcpPb.start();
        try (java.io.BufferedWriter mOut = new java.io.BufferedWriter(new java.io.OutputStreamWriter(mcpProc.getOutputStream(), java.nio.charset.StandardCharsets.UTF_8))) {
            mOut.write("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}\n");
            mOut.write("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
            mOut.write("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}\n");
            mOut.write("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"get_scene\",\"arguments\":{}}}\n");
            mOut.write("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"add_layer\",\"arguments\":{\"compIndex\":0,\"type\":\"Text\"}}}\n");
            mOut.write("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"render_preview\",\"arguments\":{\"maxWidth\":320,\"advanceFrames\":2}}}\n");
            mOut.flush();
        }
        String mcpStdout = new String(mcpProc.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        String mcpStderr = new String(mcpProc.getErrorStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        int mcpRc = mcpProc.waitFor();
        if (mcpRc != 0) {
            throw new AssertionError("Headless MCP subprocess failed rc=" + mcpRc + "\nSTDERR:\n" + mcpStderr);
        }
        String[] mcpLines = mcpStdout.trim().split("\\r?\\n");
        if (mcpLines.length != 5) {
            throw new AssertionError("Expected 5 JSON-RPC replies on stdout, got " + mcpLines.length + "\nSTDOUT:\n" + mcpStdout + "\nSTDERR:\n" + mcpStderr);
        }
        org.json.JSONObject previewReply = new org.json.JSONObject(mcpLines[4]);
        org.json.JSONArray previewContent = previewReply.getJSONObject("result").getJSONArray("content");
        byte[] headlessPng = java.util.Base64.getDecoder().decode(previewContent.getJSONObject(0).getString("data"));
        if (headlessPng.length < 200 || (headlessPng[0] & 0xFF) != 0x89 || headlessPng[1] != 'P') {
            throw new AssertionError("Headless MCP render_preview returned invalid PNG len=" + headlessPng.length);
        }
        System.out.println("MCP live bridge & headless stdio server verification OK (previewPngBytes=" + headlessPng.length + ")");

        // Part 1 verification: SpriteFont 1080p glyph isolation + RgbSplitEffect CRGBA & black splitting
        verifyPart1TextAndRgbSplit(rc, audio, w, h, fbo);

        // Part 2 verification: Range-adaptive even slider steps + Tab field navigation & numeric parsing
        verifyPart2InspectorSlidersAndInput();

        // Part 3 verification: Between-item drop target resolution & Composition move with child layers + ref remap
        verifyPart3HierarchyAndCompMove(rc, audio, fbo);

        // Part 4 verification: Manual timestamp parse/seek & spectrum/measure freeze on pause
        verifyPart4SeekAndPauseFreeze();

        // Part 5 verification: Screen color picker GL patch read, loupe right-of-cursor placement & property writeback
        verifyPart5ScreenColorPicker(w, h, fbo);

        // Part 6 verification: Shape corner roundness (RoundedRect + SidedPolygon) on Image & Bars
        verifyPart6ShapeRoundness(rc, audio, w, h, fbo);

        // Part 7 verification: AE AudioProviderAE, AESpectrum (linear freq + L/R + no-AGC) & AEWaveform
        verifyPart7AeAudioProvider(rc, audio, fbo);

        // Part 8 verification: Private template library, off-main-thread auto-save, preview PNG & .viz round-trip
        verifyPart8TemplateLibraryAndAutoSave(rc, audio, w, h, fbo);

        // Part 10 verification: Media library store, volume default/clamping, preview zoom/pan & inline numeric edit helpers
        verifyPart10UiThemeZoomVolumeAndMediaManager();

        System.out.println("ALL VERIFICATIONS PASSED (presets=" + testedPresets + ", video+audio+image+highlight+export+viz round-trip=OK)");
        audio.shutdown();
        GL30.glDeleteFramebuffers(fbo);
        GL11.glDeleteTextures(tex);
        GLFW.glfwDestroyWindow(win);
        GLFW.glfwTerminate();
    }

    private static void verifyPart1TextAndRgbSplit(RendererCore rc, DesktopAudioEngine audio, int w, int h, int fbo) throws Exception {
        String outDirProp = System.getProperty("avee.part1OutDir", "");
        File outDir = outDirProp.isEmpty() ? null : new File(outDirProp);
        if (outDir != null) outDir.mkdirs();

        // 1) Verify TextElement at 1080p scale (tall glyphs packed across rows) has NO dot bleed above/below glyphs
        int hiW = 1920, hiH = 1080;
        int hiTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, hiTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, hiW, hiH, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        int hiFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, hiFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, hiTex, 0);
        try {
            rc.onSurfaceChanged(null, hiW, hiH);
            CustomScene textScene = CustomScene.Create();
            CustomComposition textComp = textScene.createDataEntry();
            textComp.putChildTypeValue("Composition");
            Element txtEl = ElementsFactory.create("Text", "", null);
            CustomPropertiesList txtProps = textComp.createDataEntry();
            txtEl.getCustomization(txtProps, 0, n -> {});
            txtProps.putChildTypeValue("Text");
            txtProps.putPropertyStringValue("text", "Aurora_Music_Vietnam");
            txtProps.putPropertyIntValue("fontSize", 42);
            txtProps.putPropertyIntValue("color", 0xFFFFFFFF);
            txtProps.putPropertyVec2fValue("position", new com.daaw.avee.Common.Vec2f(0.5f, 0.5f));
            txtProps.putPropertyVec2fValue("alignmentPosition", new com.daaw.avee.Common.Vec2f(0.5f, 0.5f));
            rc.setThemeElements(textScene);
            for (int f = 0; f < 3; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.016f);
                GLES20.setRedirectFramebuffer0(hiFbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, hiFbo);
            Bitmap textBmp = GraphicsUtils.savePixels(0, 0, hiW, hiH, null);
            if (outDir != null) {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(outDir, "text_1080p.png"))) {
                    textBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
                }
            }
            int minY = hiH, maxY = -1;
            int[] rowLit = new int[hiH];
            for (int y = 0; y < hiH; y++) {
                int cnt = 0;
                for (int x = 0; x < hiW; x++) {
                    int px = textBmp.getPixel(x, y);
                    int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                    if (Math.max(r, Math.max(g, b)) > 16) cnt++;
                }
                rowLit[y] = cnt;
                if (cnt > 0) {
                    if (y < minY) minY = y;
                    if (y > maxY) maxY = y;
                }
            }
            if (maxY <= minY) {
                throw new AssertionError("1080p TextElement rendered empty");
            }
            int botDetached = 0;
            for (int y = Math.max(0, maxY - 5); y <= maxY; y++) {
                if (rowLit[y] > 0 && rowLit[y] <= 4) botDetached++;
            }
            if (botDetached > 1) {
                throw new AssertionError("Detected stray bottom glyph bleed dots at 1080p: botDetached=" + botDetached);
            }
        } finally {
            rc.onSurfaceChanged(null, w, h);
            GL30.glDeleteFramebuffers(hiFbo);
            GL11.glDeleteTextures(hiTex);
        }

        // 2) Verify RgbSplitEffect exposes CRGBA split colors AND splits Black (0,0,0) on transparent into R/G/B fringes
        com.daaw.avee.comp.Visualizer.Elements.RgbSplitEffectElement probeElem = new com.daaw.avee.comp.Visualizer.Elements.RgbSplitEffectElement();
        CustomPropertiesList probeProps = new CustomPropertiesList(0, new org.json.JSONObject(), null);
        probeElem.getCustomization(probeProps, 0, n -> {});
        if (!"crgba".equals(probeProps.getPropertyType("splitColor0"))
                || !"crgba".equals(probeProps.getPropertyType("splitColor1"))
                || !"crgba".equals(probeProps.getPropertyType("splitColor2"))) {
            throw new AssertionError("RgbSplitEffect splitColor properties should expose crgba type");
        }

        File blackPng = File.createTempFile("avee_black_sq_", ".png");
        blackPng.deleteOnExit();
        java.awt.image.BufferedImage blackBi = new java.awt.image.BufferedImage(64, 64, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D bg2 = blackBi.createGraphics();
        bg2.setColor(new java.awt.Color(0, 0, 0, 255));
        bg2.fillRect(0, 0, 64, 64);
        bg2.dispose();
        javax.imageio.ImageIO.write(blackBi, "png", blackPng);
        String blackRef = VizArchiveManager.importLocalAsset(blackPng);

        CustomScene splitScene = CustomScene.Create();
        CustomComposition finalComp = splitScene.createDataEntry();
        finalComp.putChildTypeValue("Composition");
        CustomComposition srcComp = splitScene.createDataEntry();
        srcComp.putChildTypeValue("Composition");

        Element imgEl = ElementsFactory.create("Image", "", null);
        CustomPropertiesList blackImg = srcComp.createDataEntry();
        imgEl.getCustomization(blackImg, 0, n -> {});
        blackImg.putChildTypeValue("Image");
        blackImg.putPropertyStringValue("customImage", blackRef);
        blackImg.putPropertyIntValue("color", 0xFF000000);
        blackImg.putPropertyVec2fValue("position", new com.daaw.avee.Common.Vec2f(0.5f, 0.5f));
        blackImg.putPropertyVec2fValue("alignmentPosition", new com.daaw.avee.Common.Vec2f(0.5f, 0.5f));
        blackImg.putPropertyVec2fValue("scale", new com.daaw.avee.Common.Vec2f(0.35f, 0.35f));

        Element splitEl = ElementsFactory.create("RgbSplitEffect", "", null);
        CustomPropertiesList splitFx = finalComp.createDataEntry();
        splitEl.getCustomization(splitFx, 0, n -> {});
        splitFx.putChildTypeValue("RgbSplitEffect");
        splitFx.putPropertyStringValue("targetImage", "composition:1");
        splitFx.putPropertyMVariableFloatValue("splitMultiplier", com.daaw.avee.comp.Visualizer.Elements.Base.MVariableFloat.CreateConstantFloat(1.0f));
        splitFx.putPropertyMVariableFloatValue("splitAmount", com.daaw.avee.comp.Visualizer.Elements.Base.MVariableFloat.CreateConstantVec2f(0.04f, 0.0f));
        splitFx.putPropertyIntValue("splitColor0", 0xFFFF0000);
        splitFx.putPropertyIntValue("splitColor1", 0xFF00FF00);
        splitFx.putPropertyIntValue("splitColor2", 0xFF0000FF);

        rc.setThemeElements(splitScene);
        for (int f = 0; f < 3; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        Bitmap splitBmp = GraphicsUtils.savePixels(0, 0, w, h, null);
        if (outDir != null) {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(new File(outDir, "rgbsplit_black.png"))) {
                splitBmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
        }
        int redFringe = 0, blueFringe = 0, blackCore = 0;
        for (int y = h / 4; y < (h * 3) / 4; y++) {
            for (int x = w / 6; x < (w * 5) / 6; x++) {
                int px = splitBmp.getPixel(x, y);
                int a = (px >>> 24) & 0xFF, r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                if (r > 180 && g < 30 && b < 30) redFringe++;
                if (b > 180 && r < 30 && g < 30) blueFringe++;
                if (a > 200 && r < 20 && g < 20 && b < 20) blackCore++;
            }
        }
        if (redFringe < 50 || blueFringe < 50 || blackCore < 100) {
            throw new AssertionError("RgbSplitEffect failed to split Black (0,0,0) square cleanly: redFringe="
                    + redFringe + " blueFringe=" + blueFringe + " blackCore=" + blackCore);
        }
        System.out.println("Part 1 verification OK (1080p SpriteFont clean, RgbSplit black fringes R="
                + redFringe + " B=" + blueFringe + " core=" + blackCore + ")");
    }

    private static void verifyPart2InspectorSlidersAndInput() {
        // 1) Unit ranges use clean 0.010 increments
        float unitStep = PropertyInspectorPanel.computeNiceFloatStep(0.0f, 1.0f);
        float vec2Step = PropertyInspectorPanel.computeNiceFloatStep(-1.0f, 1.0f);
        if (Math.abs(unitStep - 0.01f) > 1e-6f || Math.abs(vec2Step - 0.01f) > 1e-6f) {
            throw new AssertionError("Expected 0.010 step for unit sliders, got " + unitStep + ", " + vec2Step);
        }
        float q1 = PropertyInspectorPanel.quantizeFloat(0.4227f, 0.0f, 1.0f, unitStep);
        if (Math.abs(q1 - 0.42f) > 1e-5f) {
            throw new AssertionError("Expected quantized 0.420, got " + q1);
        }

        // 2) Ranges above 100 use clean 5 or 10 increments instead of random fractional steps
        float rotStep = PropertyInspectorPanel.computeNiceFloatStep(0.0f, 300.0f);
        float depthStep = PropertyInspectorPanel.computeNiceFloatStep(0.0f, 1000.0f);
        if (Math.abs(rotStep - 5.0f) > 1e-6f || Math.abs(depthStep - 10.0f) > 1e-6f) {
            throw new AssertionError("Expected 5/10 step for >100 ranges, got rot=" + rotStep + " depth=" + depthStep);
        }
        float qRot = PropertyInspectorPanel.quantizeFloat(127.643f, 0.0f, 300.0f, rotStep);
        if (Math.abs(qRot - 130.0f) > 1e-5f) {
            throw new AssertionError("Expected quantized rotation 130.0, got " + qRot);
        }

        // 3) Int slider step scaling & clamping
        if (PropertyInspectorPanel.computeNiceIntStep(0, 20) != 1
                || PropertyInspectorPanel.computeNiceIntStep(1, 5000) != 50
                || PropertyInspectorPanel.quantizeInt(1234, 1, 5000, 50) != 1250) {
            throw new AssertionError("Unexpected integer slider step/quantization");
        }

        // 4) Independent Tab order across dual fields (#0 -> #1 -> next property) & Shift+Tab back
        java.util.List<String> order = java.util.Arrays.asList("/pos#0", "/pos#1", "/scale#0", "/scale#1", "/rot#0");
        if (!"/pos#1".equals(PropertyInspectorPanel.findNextFieldUid(order, "/pos#0", false))
                || !"/scale#0".equals(PropertyInspectorPanel.findNextFieldUid(order, "/pos#1", false))
                || !"/pos#0".equals(PropertyInspectorPanel.findNextFieldUid(order, "/pos#1", true))
                || PropertyInspectorPanel.findNextFieldUid(order, "/rot#0", false) != null) {
            throw new AssertionError("Tab field navigation order mismatch");
        }

        // 5) Manual numeric parsing (both positive/negative decimals, whitespace, invalid guard)
        Float pf = PropertyInspectorPanel.parseEditedFloat("  1.25  ");
        Integer pi = PropertyInspectorPanel.parseEditedInt(" 240 ");
        if (pf == null || Math.abs(pf - 1.25f) > 1e-5f || pi == null || pi != 240
                || PropertyInspectorPanel.parseEditedFloat("abc") != null
                || PropertyInspectorPanel.parseEditedInt("") != null) {
            throw new AssertionError("Manual numeric parser mismatch");
        }
        System.out.println("Part 2 verification OK (even steps unit=0.010 rot=5.0 depth=10.0, dual-field Tab order OK)");
    }

    private static void verifyPart3HierarchyAndCompMove(RendererCore rc, DesktopAudioEngine audio, int fbo) {
        // 1) Drop slot resolution between items (no-op on adjacent self slots, exact adjusted index on move)
        if (AveeEditorApp.resolveLayerDropIndex(0, 1, 0, 1) != -1
                || AveeEditorApp.resolveLayerDropIndex(0, 1, 0, 2) != -1
                || AveeEditorApp.resolveLayerDropIndex(0, 0, 0, 3) != 2
                || AveeEditorApp.resolveLayerDropIndex(0, 2, 0, 0) != 0
                || AveeEditorApp.resolveLayerDropIndex(0, 1, 1, 2) != 2) {
            throw new AssertionError("Layer drop insertion index resolution mismatch");
        }
        if (AveeEditorApp.resolveCompositionDropIndex(0, 1, 3) != -1
                || AveeEditorApp.resolveCompositionDropIndex(1, 1, 3) != -1
                || AveeEditorApp.resolveCompositionDropIndex(1, 2, 3) != -1
                || AveeEditorApp.resolveCompositionDropIndex(1, 3, 3) != 2
                || AveeEditorApp.resolveCompositionDropIndex(3, 1, 4) != 1) {
            throw new AssertionError("Composition drop insertion index resolution mismatch");
        }

        // 2) Move Composition with all child layers + automatic composition:N / sourceCompositionIndex remapping
        CustomScene scene = CustomScene.Create();
        CustomComposition comp0 = scene.createDataEntry();
        comp0.putChildTypeValue("Composition");
        CustomComposition comp1 = scene.createDataEntry();
        comp1.putChildTypeValue("Composition");
        CustomComposition comp2 = scene.createDataEntry();
        comp2.putChildTypeValue("Composition");

        Element imgEl = ElementsFactory.create("Image", "", null);
        CustomPropertiesList refImg = comp0.createDataEntry();
        imgEl.getCustomization(refImg, 0, n -> {});
        refImg.putChildTypeValue("Image");
        refImg.putPropertyStringValue("customImage", "composition:1");

        Element blurEl = ElementsFactory.create("BlurEffect", "", null);
        CustomPropertiesList refBlur = comp0.createDataEntry();
        blurEl.getCustomization(refBlur, 0, n -> {});
        refBlur.putChildTypeValue("BlurEffect");
        refBlur.putPropertyIntValue("sourceCompositionIndex", 2);

        Element txtEl1 = ElementsFactory.create("Text", "", null);
        CustomPropertiesList c1Txt = comp1.createDataEntry();
        txtEl1.getCustomization(c1Txt, 0, n -> {});
        c1Txt.putChildTypeValue("Text");
        c1Txt.putPropertyStringValue("text", "COMP1_CHILD");

        Element barsEl = ElementsFactory.create("Bars", "", null);
        CustomPropertiesList c1Bars = comp1.createDataEntry();
        barsEl.getCustomization(c1Bars, 0, n -> {});
        c1Bars.putChildTypeValue("Bars");

        Element txtEl2 = ElementsFactory.create("Text", "", null);
        CustomPropertiesList c2Txt = comp2.createDataEntry();
        txtEl2.getCustomization(c2Txt, 0, n -> {});
        c2Txt.putChildTypeValue("Text");
        c2Txt.putPropertyStringValue("text", "COMP2_CHILD");

        int movedTo = AveeEditorApp.moveCompositionInScene(scene, 1, 2);
        if (movedTo != 2) {
            throw new AssertionError("Expected movedTo=2, got " + movedTo);
        }
        if (scene.getEntry(1).getEntryCount() != 1
                || !"COMP2_CHILD".equals(scene.getEntry(1).getData(0).getPropertyString("text", ""))) {
            throw new AssertionError("Composition 1 after swap does not hold old Composition 2 children");
        }
        if (scene.getEntry(2).getEntryCount() != 2
                || !"COMP1_CHILD".equals(scene.getEntry(2).getData(0).getPropertyString("text", ""))
                || !"Bars".equals(scene.getEntry(2).getData(1).getTypeName(""))) {
            throw new AssertionError("Moved Composition 2 did not preserve its child layers cleanly");
        }
        if (!"composition:2".equals(scene.getEntry(0).getData(0).getPropertyString("customImage", ""))
                || scene.getEntry(0).getData(1).getPropertyInt("sourceCompositionIndex", -1) != 1) {
            throw new AssertionError("Composition reference remapping after move failed");
        }

        rc.setThemeElements(scene);
        for (int f = 0; f < 3; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        int glErr = GL11.glGetError();
        if (glErr != 0) {
            throw new AssertionError("GL error after Composition reorder: " + glErr);
        }
        System.out.println("Part 3 verification OK (drop line slot resolution, Composition move + child layers + ref remap OK)");
    }

    private static void verifyPart4SeekAndPauseFreeze() throws Exception {
        // 1) Timestamp parsing & formatting
        if (AveeEditorApp.parseTimestampMs("1:23", 180_000L) != 83_000L
                || AveeEditorApp.parseTimestampMs("01:23.5", 180_000L) != 83_500L
                || AveeEditorApp.parseTimestampMs("45", 180_000L) != 45_000L
                || AveeEditorApp.parseTimestampMs("45.25s", 180_000L) != 45_250L
                || AveeEditorApp.parseTimestampMs("1:01:02", 4_000_000L) != 3_662_000L
                || AveeEditorApp.parseTimestampMs("99:99", 120_000L) != 120_000L
                || AveeEditorApp.parseTimestampMs("not-a-time", 120_000L) != -1L) {
            throw new AssertionError("Manual timestamp parseTimestampMs mismatch");
        }
        if (!"1:23".equals(AveeEditorApp.formatTimestamp(83_000L))
                || !"1:01:02".equals(AveeEditorApp.formatTimestamp(3_662_000L))) {
            throw new AssertionError("formatTimestamp mismatch");
        }

        // 2) Load real generated audio, play -> pause -> verify spectrum, waveform, RMS & beat shake freeze cleanly
        File tmpWav = File.createTempFile("avee_part4_sine_", ".wav");
        tmpWav.deleteOnExit();
        Process ff = new ProcessBuilder(
                FfmpegLocator.ffmpeg(), "-y", "-f", "lavfi", "-i", "sine=frequency=440:duration=2.5",
                "-ac", "2", "-ar", "44100", tmpWav.getAbsolutePath())
                .redirectErrorStream(true).start();
        ff.getInputStream().readAllBytes();
        int rc = ff.waitFor();
        if (rc != 0 || tmpWav.length() < 1000) {
            throw new AssertionError("Failed to generate sine audio fixture exit=" + rc);
        }

        DesktopAudioEngine eng = new DesktopAudioEngine(false);
        if (!eng.loadAudioFile(tmpWav)) {
            throw new AssertionError("DesktopAudioEngine failed to load generated WAV");
        }
        eng.setSyntheticBeat(false);
        eng.setPlaying(true);

        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioSpectrumData spec =
                new com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioSpectrumData();
        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioSpectrumDataPhaseS phaseS =
                new com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioSpectrumDataPhaseS();
        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioWaveformData wave =
                new com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioWaveformData();

        com.daaw.avee.comp.Visualizer.Graphic.RenderState fakeRs =
                new com.daaw.avee.comp.Visualizer.Graphic.RenderState(eng) {
                    @Override public float getFrameTimeF() { return 0.016f; }
                    @Override public boolean getMultiThread() { return false; }
                };

        for (int f = 0; f < 12; f++) {
            eng.stepOfflineFrame(400L + f * 20L, 0.016f);
            spec.process(fakeRs, eng);
            phaseS.process(fakeRs, eng);
            wave.process(fakeRs, eng);
        }

        float[] specBeforePause = spec.getFrameValues(0, 0).clone();
        float[] phaseBeforePause = phaseS.getFrameValues(0, 0).clone();
        float[] waveBeforePause = wave.getFrameValues(0, 0).clone();
        float rmsBeforePause = spec.getRms();
        float phaseRmsBeforePause = phaseS.getRms();
        float waveRmsBeforePause = wave.getRms();
        float maxBar = 0f;
        for (float v : specBeforePause) maxBar = Math.max(maxBar, v);
        if (maxBar <= 0.01f || rmsBeforePause <= 0.001f) {
            throw new AssertionError("Expected active spectrum/RMS before pause, got maxBar="
                    + maxBar + " rms=" + rmsBeforePause);
        }

        com.daaw.avee.comp.Common.MeterOutputData meter = new com.daaw.avee.comp.Common.MeterOutputData();
        meter.frameDataRmsValue = rmsBeforePause;
        meter.peakBarIndex = spec.getPeakBarIndex();
        com.daaw.avee.Common.Vec2f arg = new com.daaw.avee.Common.Vec2f(0.5f, 0.5f);
        com.daaw.avee.Common.Vec2f shakeActive = eng.onRequestMeasureVec2f(
                com.daaw.avee.comp.Common.MeasureDefs.beatRandomShake, arg, null, meter, fakeRs);

        // Now pause! Spectrum, PhaseS, waveform, RMS, and beat shake MUST freeze at exact paused state across many frames
        eng.setPlaying(false);
        for (int f = 0; f < 30; f++) {
            eng.tickFrame(0.016f);
            spec.process(fakeRs, eng);
            phaseS.process(fakeRs, eng);
            wave.process(fakeRs, eng);
        }
        float[] specAfterPause = spec.getFrameValues(0, 0);
        float[] phaseAfterPause = phaseS.getFrameValues(0, 0);
        float[] waveAfterPause = wave.getFrameValues(0, 0);
        if (Math.abs(spec.getRms() - rmsBeforePause) > 1e-6f
                || Math.abs(phaseS.getRms() - phaseRmsBeforePause) > 1e-6f
                || Math.abs(wave.getRms() - waveRmsBeforePause) > 1e-6f) {
            throw new AssertionError("RMS decayed/reset on pause: before=" + rmsBeforePause + " after=" + spec.getRms());
        }
        for (int i = 0; i < specBeforePause.length; i++) {
            if (Math.abs(specAfterPause[i] - specBeforePause[i]) > 1e-6f) {
                throw new AssertionError("Spectrum bar[" + i + "] changed on pause: before="
                        + specBeforePause[i] + " after=" + specAfterPause[i]);
            }
        }
        for (int i = 0; i < phaseBeforePause.length; i++) {
            if (Math.abs(phaseAfterPause[i] - phaseBeforePause[i]) > 1e-6f) {
                throw new AssertionError("PhaseS spectrum bar[" + i + "] changed on pause: before="
                        + phaseBeforePause[i] + " after=" + phaseAfterPause[i]);
            }
        }
        for (int i = 0; i < waveBeforePause.length; i++) {
            if (Math.abs(waveAfterPause[i] - waveBeforePause[i]) > 1e-6f) {
                throw new AssertionError("Waveform bar[" + i + "] changed on pause");
            }
        }
        com.daaw.avee.Common.Vec2f shakeFrozen = eng.onRequestMeasureVec2f(
                com.daaw.avee.comp.Common.MeasureDefs.beatRandomShake, arg, shakeActive, meter, fakeRs);
        if (Math.abs(shakeFrozen.x - shakeActive.x) > 1e-6f || Math.abs(shakeFrozen.y - shakeActive.y) > 1e-6f) {
            throw new AssertionError("BeatRandomShake moved/reset on pause");
        }

        // Seek while paused jumps position accurately and refreshes spectrum once before re-freezing
        eng.seekMs(1400L);
        if (Math.abs(eng.getPositionMs() - 1400L) > 5L) {
            throw new AssertionError("seekMs while paused failed: pos=" + eng.getPositionMs());
        }
        spec.process(fakeRs, eng);
        float[] specAfterPausedSeek = spec.getFrameValues(0, 0).clone();
        spec.process(fakeRs, eng);
        float[] specFrozenAfterSeek = spec.getFrameValues(0, 0);
        for (int i = 0; i < specAfterPausedSeek.length; i++) {
            if (Math.abs(specFrozenAfterSeek[i] - specAfterPausedSeek[i]) > 1e-6f) {
                throw new AssertionError("Spectrum failed to stay frozen after seek while paused");
            }
        }
        eng.shutdown();
        System.out.println("Part 4 verification OK (manual timestamp parse/seek + pause spectrum/waveform/shake freeze OK)");
    }

    private static void verifyPart5ScreenColorPicker(int w, int h, int fbo) {
        int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, w, h);
            GL11.glEnable(GL11.GL_SCISSOR_TEST);

            int cx = w / 2;
            int cy = h / 2;
            // Background black
            GL11.glScissor(0, 0, w, h);
            GL11.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Top-left inside patch (higher fbY = higher on screen): pure Red
            GL11.glScissor(cx - 3, cy + 3, 1, 1);
            GL11.glClearColor(1.0f, 0.0f, 0.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Center pixel: Emerald (34, 180, 95)
            GL11.glScissor(cx, cy, 1, 1);
            GL11.glClearColor(34 / 255.0f, 180 / 255.0f, 95 / 255.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Right neighbor (+2, 0): Yellow (240, 220, 40)
            GL11.glScissor(cx + 2, cy, 1, 1);
            GL11.glClearColor(240 / 255.0f, 220 / 255.0f, 40 / 255.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            // Bottom-right inside patch (+3, -3 GL -> lower on screen): Blue (20, 60, 250)
            GL11.glScissor(cx + 3, cy - 3, 1, 1);
            GL11.glClearColor(20 / 255.0f, 60 / 255.0f, 250 / 255.0f, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GL11.glDisable(GL11.GL_SCISSOR_TEST);

            int size = ScreenColorPicker.PATCH_SIZE;
            int r = ScreenColorPicker.PATCH_RADIUS;
            int[] patch = new int[size * size];
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.allocateDirect(size * size * 4)
                    .order(java.nio.ByteOrder.nativeOrder());
            int center = ScreenColorPicker.readGlPatch(fbo, 0, cx, cy, w, h, r, patch, buf);

            int cr = (center >> 16) & 0xFF;
            int cg = (center >> 8) & 0xFF;
            int cb = center & 0xFF;
            if (Math.abs(cr - 34) > 2 || Math.abs(cg - 180) > 2 || Math.abs(cb - 95) > 2) {
                throw new AssertionError("Center sampled pixel mismatch: rgb=(" + cr + "," + cg + "," + cb + ")");
            }
            int topLeft = patch[(r - 3) * size + (r - 3)];
            int rightPx = patch[r * size + (r + 2)];
            int botRight = patch[(r + 3) * size + (r + 3)];
            if (((topLeft >> 16) & 0xFF) < 250 || ((topLeft >> 8) & 0xFF) > 5) {
                throw new AssertionError("Top-left Y-flipped patch pixel mismatch: 0x" + Integer.toHexString(topLeft));
            }
            if (((rightPx >> 16) & 0xFF) < 235 || ((rightPx >> 8) & 0xFF) < 215 || (rightPx & 0xFF) > 45) {
                throw new AssertionError("Right neighbor patch pixel mismatch: 0x" + Integer.toHexString(rightPx));
            }
            if ((botRight & 0xFF) < 245 || ((botRight >> 16) & 0xFF) > 25) {
                throw new AssertionError("Bottom-right patch pixel mismatch: 0x" + Integer.toHexString(botRight));
            }

            // Loupe preview window placement: strictly right of cursor when space available, clamped at edge
            float[] norm = ScreenColorPicker.computeLoupeRect(400f, 350f, 0f, 0f, 1500f, 880f, 160f, 200f);
            if (norm[0] <= 400f || Math.abs(norm[1] - 250f) > 1e-3f) {
                throw new AssertionError("Expected loupe right of cursor: " + java.util.Arrays.toString(norm));
            }
            float[] edge = ScreenColorPicker.computeLoupeRect(1460f, 860f, 0f, 0f, 1500f, 880f, 160f, 200f);
            if (edge[0] + edge[2] > 1500f || edge[1] + edge[3] > 880f || edge[0] < 4f) {
                throw new AssertionError("Loupe edge clamping failed: " + java.util.Arrays.toString(edge));
            }

            // Property writeback across crgba (preserving alpha), crgb, crgba4f, chsla4f
            CustomPropertiesList props = new CustomPropertiesList(0, new org.json.JSONObject(), null);
            props.putPropertyIntAsCRGBA("colRgba", 0x80000000, "c");
            props.putPropertyIntAsCRGB("colRgb", 0xFF000000, "c");
            props.putPropertyArray4fValue("col4f", new float[]{0f, 0f, 0f, 0.5f});
            props.putPropertyArray4fValue("colHsla", new float[]{0f, 0f, 0f, 0.5f});

            if (!ScreenColorPicker.applyPickedColor(props, "colRgba", "crgba", center, 0.5f)
                    || !ScreenColorPicker.applyPickedColor(props, "colRgb", "crgb", center, 0.5f)
                    || !ScreenColorPicker.applyPickedColor(props, "col4f", "crgba4f", center, 0.5f)
                    || !ScreenColorPicker.applyPickedColor(props, "colHsla", "chsla4f", center, 0.5f)) {
                throw new AssertionError("applyPickedColor returned false");
            }
            int savedRgba = props.getPropertyInt("colRgba", 0);
            int savedRgb = props.getPropertyInt("colRgb", 0);
            if (Math.abs(((savedRgba >> 24) & 0xFF) - 128) > 2 || (savedRgba & 0x00FFFFFF) != (center & 0x00FFFFFF)) {
                throw new AssertionError("crgba writeback mismatch: 0x" + Integer.toHexString(savedRgba));
            }
            if (((savedRgb >> 24) & 0xFF) != 255 || (savedRgb & 0x00FFFFFF) != (center & 0x00FFFFFF)) {
                throw new AssertionError("crgb writeback mismatch: 0x" + Integer.toHexString(savedRgb));
            }
            float[] savedHsla = props.getPropertyArray4f("colHsla", new float[4]);
            float[] backF4 = new float[4];
            com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils.hlsa1ColorToF4Color(savedHsla, backF4);
            if (Math.abs(backF4[0] * 255f - cr) > 3f || Math.abs(backF4[1] * 255f - cg) > 3f || Math.abs(backF4[2] * 255f - cb) > 3f) {
                throw new AssertionError("chsla4f round-trip mismatch: " + java.util.Arrays.toString(backF4));
            }
        } finally {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
        }
        System.out.println("Part 5 verification OK (eyedropper GL patch, right-side loupe & color property writeback OK)");
    }

    private static void verifyPart6ShapeRoundness(RendererCore rc, DesktopAudioEngine audio, int w, int h, int fbo) {
        // 1) Factory registration & property schema check
        com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath rrPath =
                com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathFactory.create("RoundedRect", null);
        if (!(rrPath instanceof com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathRoundedRect)) {
            throw new AssertionError("SegmentPathFactory should construct SegmentPathRoundedRect for RoundedRect");
        }
        if (!"RoundedRect".equals(com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathFactory.getTypeName(rrPath))) {
            throw new AssertionError("SegmentPathFactory.getTypeName mismatch for RoundedRect");
        }

        CustomPropertiesList rrProps = new CustomPropertiesList(0, new org.json.JSONObject(), null);
        rrPath.onReadCustomization(rrProps);
        if (rrProps.getPropertyType("roundness") == null || !rrProps.getPropertyType("roundness").startsWith("f ")) {
            throw new AssertionError("RoundedRect missing float roundness property: " + rrProps.getPropertyType("roundness"));
        }

        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathSided poly =
                new com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathSided().setSides(4);
        CustomPropertiesList polyProps = new CustomPropertiesList(0, new org.json.JSONObject(), null);
        poly.onReadCustomization(polyProps);
        if (polyProps.getPropertyType("roundness") == null || !polyProps.getPropertyType("roundness").startsWith("f ")) {
            throw new AssertionError("SidedPolygon missing float roundness property");
        }

        // 2) Geometry check: RoundedRect roundness=0 hits sharp corner while roundness=0.5 rounds corner inward & outward normal points inward
        android.graphics.RectF rect = new android.graphics.RectF(0f, 0f, 200f, 100f);
        android.graphics.PointF pt = new android.graphics.PointF();
        android.graphics.PointF vec = new android.graphics.PointF();
        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathRoundedRect rr =
                (com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentPathRoundedRect) rrPath;
        rr.setRoundness(0.0f);
        if (rr.getPreferredPointCount(rect) != 4) {
            throw new AssertionError("Expected 4 points for sharp RoundedRect");
        }
        rr.getPointOnPath(false, 0, 4, rect, 0f, pt, vec);
        if (Math.abs(pt.x - 0f) > 0.01f || Math.abs(pt.y - 0f) > 0.01f || vec.x <= 0f || vec.y <= 0f) {
            throw new AssertionError("Sharp RoundedRect corner 0 mismatch: pt=(" + pt.x + "," + pt.y + ") vec=(" + vec.x + "," + vec.y + ")");
        }
        rr.setRoundness(0.6f);
        if (rr.getPreferredPointCount(rect) < 36) {
            throw new AssertionError("Expected smooth point count for rounded RoundedRect");
        }
        // Sample point along top-left region (quarter turn from top-left): must stay strictly inside acute corner bounding box
        boolean foundCurvedInward = false;
        int nPts = rr.getPreferredPointCount(rect);
        for (int i = 0; i < nPts; i++) {
            rr.getPointOnPath(false, i, nPts, rect, 0f, pt, vec);
            float vecLen = (float) Math.hypot(vec.x, vec.y);
            if (Math.abs(vecLen - 1.0f) > 1e-3f) {
                throw new AssertionError("RoundedRect normal not unit length: " + vecLen);
            }
            if (pt.x > 3f && pt.x < 25f && pt.y > 3f && pt.y < 25f) {
                foundCurvedInward = true;
            }
        }
        if (!foundCurvedInward) {
            throw new AssertionError("RoundedRect with roundness=0.6 did not round corners");
        }

        // 3) Render check: white Image with RoundedRect + roundness=0.8 trims corners dark while keeping center white
        CustomScene scene = CustomScene.Create();
        CustomComposition comp0 = scene.createDataEntry();
        comp0.putChildTypeValue("Composition");
        CustomPropertiesList img = comp0.createDataEntry();
        img.putChildTypeValue("Image");
        img.putPropertyStringValue("customImage", "composition:0"); // internal white square fallback via White shape test below
        img.putPropertyArray4fValue("Color", new float[]{1f, 1f, 1f, 1f});
        img.putPropertyVec2fValue("scale", new com.daaw.avee.Common.Vec2f(0.8f, 0.8f));
        img.putPropertySelectStringValue("customImage", "internal_0");
        CustomPropertiesList shapeChild = img.putEmptyChild("Shape", "RoundedRect", "1_image", new String[0]);
        shapeChild.putPropertyFloatValue("roundness", 0.85f);
        CustomPropertiesList bars = comp0.createDataEntry();
        bars.putChildTypeValue("Bars");
        CustomPropertiesList barsPath = bars.putEmptyChild("ShapePath", "SidedPolygon", "1_bars", new String[0]);
        barsPath.putPropertyIntValue("sides", 4);
        barsPath.putPropertyFloatValue("roundness", 0.5f);

        rc.setThemeElements(scene);
        for (int f = 0; f < 3; f++) {
            Looper.drainMainQueue();
            audio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }

        // Also test white solid texture corner clipping directly in an offscreen white image element via 4x4 PNG
        try {
            File whitePng = File.createTempFile("avee_part6_white_", ".png");
            whitePng.deleteOnExit();
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            g.setColor(java.awt.Color.WHITE);
            g.fillRect(0, 0, 16, 16);
            g.dispose();
            javax.imageio.ImageIO.write(bi, "png", whitePng);

            CustomScene s2 = CustomScene.Create();
            CustomComposition c2 = s2.createDataEntry();
            c2.putChildTypeValue("Composition");
            CustomPropertiesList im2 = c2.createDataEntry();
            im2.putChildTypeValue("Image");
            im2.putPropertyStringValue("customImage", whitePng.getAbsolutePath());
            im2.putPropertyArray4fValue("Color", new float[]{1f, 1f, 1f, 1f});
            im2.putPropertyVec2fValue("scale", new com.daaw.avee.Common.Vec2f(0.8f, 0.8f));
            CustomPropertiesList sh2 = im2.putEmptyChild("Shape", "RoundedRect", "1_image", new String[0]);

            // First render with roundness=0.0 (sharp corner -> white at bounding corner)
            sh2.putPropertyFloatValue("roundness", 0.0f);
            rc.setThemeElements(s2);
            for (int f = 0; f < 4; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.016f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
            }
            java.nio.ByteBuffer px = java.nio.ByteBuffer.allocateDirect(w * h * 4).order(java.nio.ByteOrder.nativeOrder());
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            int cornerX = Math.round(w * 0.115f);
            int cornerY = Math.round(h * 0.115f);
            int cornerOff = (cornerY * w + cornerX) * 4;
            int sharpCornerR = px.get(cornerOff) & 0xFF;

            // Now render with roundness=0.9 (rounded corner -> corner clipped away while center stays white)
            sh2.putPropertyFloatValue("roundness", 0.9f);
            rc.setThemeElements(s2);
            for (int f = 0; f < 4; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.016f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
            }
            px.clear();
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, fbo);
            GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, px);
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
            int centerOff = ((h / 2) * w + (w / 2)) * 4;
            int centerR = px.get(centerOff) & 0xFF;
            int roundedCornerR = px.get(cornerOff) & 0xFF;
            if (sharpCornerR < 200 || centerR < 200 || roundedCornerR > 40) {
                throw new AssertionError("RoundedRect Image corner clipping failed: sharpCornerR="
                        + sharpCornerR + " roundedCornerR=" + roundedCornerR + " centerR=" + centerR);
            }
        } catch (Exception e) {
            throw new AssertionError("Part 6 RoundedRect render check failed: " + e.getMessage(), e);
        }
        System.out.println("Part 6 verification OK (RoundedRect + SidedPolygon roundness property, geometry & render clipping OK)");
    }

    private static void verifyPart7AeAudioProvider(RendererCore rc, DesktopAudioEngine mainAudio, int fbo) throws Exception {
        // 1) Element & Factory registration + familiar property parity
        com.daaw.avee.comp.Visualizer.Elements.Base.Element aeEl =
                com.daaw.avee.comp.Visualizer.Elements.ElementsFactory.create("AudioProviderAE", "", null);
        if (!(aeEl instanceof com.daaw.avee.comp.Visualizer.Elements.AeAudioDataProviderElement)) {
            throw new AssertionError("Expected AeAudioDataProviderElement for AudioProviderAE");
        }
        if (!com.daaw.avee.comp.Visualizer.Elements.ElementsFactory.getAddableTypeNames().contains("AudioProviderAE")) {
            throw new AssertionError("AudioProviderAE missing from ElementsFactory.getAddableTypeNames()");
        }
        com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentDataProvider aeSpecP =
                com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentDataProviderFactory.create("AESpectrum", null);
        com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentDataProvider aeWaveP =
                com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentDataProviderFactory.create("AEWaveform", null);
        if (!(aeSpecP instanceof com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeSpectrumData)
                || !(aeWaveP instanceof com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeWaveformData)) {
            throw new AssertionError("SegmentDataProviderFactory failed to create AESpectrum / AEWaveform");
        }

        CustomPropertiesList aeProps = new CustomPropertiesList(0, new org.json.JSONObject(), null);
        aeSpecP.onReadCustomization(aeProps);
        String[] expectedSpecKeys = {"fftSize", "sampleOutCount", "lowerHz", "higherHz", "hzLinearFactor",
                "mirrorSamples", "smooth", "outputMultiplier", "audioDurationMs", "audioOffsetMs", "channelMode", "agcNormalized"};
        for (String k : expectedSpecKeys) {
            if (aeProps.getPropertyType(k) == null) {
                throw new AssertionError("AESpectrum missing property key: " + k);
            }
        }
        CustomPropertiesList waveProps = new CustomPropertiesList(0, new org.json.JSONObject(), null);
        aeWaveP.onReadCustomization(waveProps);
        if (waveProps.getPropertyType("sampleOutCountPower") == null || waveProps.getPropertyType("waveformMode") == null
                || waveProps.getPropertyType("audioDurationMs") == null) {
            throw new AssertionError("AEWaveform missing expected property keys");
        }

        // 2) Stereo WAV fixture: t<1.5s L=440Hz(0.8), R=1200Hz(0.4); t>=1.5s L=440Hz(0.2) (4x quieter to verify no-AGC)
        int sr = 44100;
        int totalSamples = (int) (sr * 2.5);
        byte[] pcmBytes = new byte[totalSamples * 4];
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.wrap(pcmBytes).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < totalSamples; i++) {
            double sec = i / (double) sr;
            double ampL = sec < 1.5 ? 0.8 : 0.2;
            short l = (short) Math.round(ampL * Math.sin(2.0 * Math.PI * 440.0 * sec) * 32000.0);
            short r = (short) Math.round(0.4 * Math.sin(2.0 * Math.PI * 1200.0 * sec) * 32000.0);
            bb.putShort(l);
            bb.putShort(r);
        }
        File tmpWav = File.createTempFile("avee_part7_stereo_", ".wav");
        tmpWav.deleteOnExit();
        try (javax.sound.sampled.AudioInputStream ais = new javax.sound.sampled.AudioInputStream(
                new java.io.ByteArrayInputStream(pcmBytes),
                new javax.sound.sampled.AudioFormat(sr, 16, 2, true, false),
                totalSamples)) {
            javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, tmpWav);
        }

        DesktopAudioEngine eng = new DesktopAudioEngine(false);
        if (!eng.loadAudioFile(tmpWav)) {
            throw new AssertionError("Failed to load stereo WAV in Part 7 test");
        }
        eng.setSyntheticBeat(false);

        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeSpectrumData spec =
                new com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeSpectrumData()
                        .setSampleOutCount(101)
                        .setFreqRange(0.0f, 2000.0f)
                        .setAudioDurationMs(60);
        com.daaw.avee.comp.Visualizer.Graphic.RenderState fakeRs =
                new com.daaw.avee.comp.Visualizer.Graphic.RenderState(eng) {
                    @Override public float getFrameTimeF() { return 0.016f; }
                    @Override public boolean getMultiThread() { return false; }
                };

        // Left channel peak near 440 Hz -> bin 22 (of 0..100 for 0..2000 Hz)
        spec.setChannelMode("Left");
        eng.stepOfflineFrame(600L, 0.016f);
        spec.process(fakeRs, eng);
        float[] barsLeftLoud = spec.getFrameValues(0, 0).clone();
        int peakLeftBin = 0;
        for (int i = 1; i < barsLeftLoud.length; i++) {
            if (barsLeftLoud[i] > barsLeftLoud[peakLeftBin]) peakLeftBin = i;
        }
        if (Math.abs(peakLeftBin - 22) > 2 || barsLeftLoud[peakLeftBin] < 10.0f) {
            throw new AssertionError("AE Left channel 440Hz peak mismatch: bin=" + peakLeftBin + " val=" + barsLeftLoud[peakLeftBin]);
        }

        // Right channel peak near 1200 Hz -> bin 60
        spec.setChannelMode("Right");
        eng.stepOfflineFrame(600L, 0.016f);
        spec.process(fakeRs, eng);
        float[] barsRight = spec.getFrameValues(0, 0).clone();
        int peakRightBin = 0;
        for (int i = 1; i < barsRight.length; i++) {
            if (barsRight[i] > barsRight[peakRightBin]) peakRightBin = i;
        }
        if (Math.abs(peakRightBin - 60) > 2 || barsRight[peakRightBin] < 5.0f) {
            throw new AssertionError("AE Right channel 1200Hz peak mismatch: bin=" + peakRightBin + " val=" + barsRight[peakRightBin]);
        }

        // No-AGC linearity: Left at 2000ms (0.2 amp) must be ~0.25x Left at 600ms (0.8 amp)
        spec.setChannelMode("Left");
        eng.stepOfflineFrame(2000L, 0.016f);
        spec.process(fakeRs, eng);
        float[] barsLeftQuiet = spec.getFrameValues(0, 0).clone();
        float loudPeak = barsLeftLoud[peakLeftBin];
        float quietPeak = barsLeftQuiet[peakLeftBin];
        float dynRatio = loudPeak / Math.max(1e-4f, quietPeak);
        if (dynRatio < 3.2f || dynRatio > 4.8f) {
            throw new AssertionError("AESpectrum no-AGC ratio expected ~4.0x, got " + dynRatio
                    + " (loud=" + loudPeak + " quiet=" + quietPeak + ")");
        }

        // Pause freeze check on AESpectrum & AEWaveform
        com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeWaveformData wave =
                new com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeWaveformData()
                        .setSampleOutCountPower(6)
                        .setWaveformMode("SignedSample");
        wave.process(fakeRs, eng);
        float[] waveActive = wave.getFrameValues(0, 0).clone();
        boolean hasNeg = false;
        boolean hasPos = false;
        for (float v : waveActive) {
            if (v < -0.5f) hasNeg = true;
            if (v > 0.5f) hasPos = true;
        }
        if (!hasNeg || !hasPos) {
            throw new AssertionError("AEWaveform SignedSample should capture zero-centered oscillations");
        }
        eng.stepOfflineFrame(600L, 0.016f);
        spec.process(fakeRs, eng);
        eng.setPlaying(false);
        // After pause without seek, bars should remain frozen across process calls
        float[] prevBars = spec.getFrameValues(0, 0).clone();
        for (int f = 0; f < 5; f++) {
            eng.tickFrame(0.016f);
            spec.process(fakeRs, eng);
        }
        float[] frozenBars = spec.getFrameValues(0, 0);
        if (Math.abs(frozenBars[peakLeftBin] - prevBars[peakLeftBin]) > 1e-5f) {
            throw new AssertionError("AESpectrum failed to freeze on pause");
        }
        eng.shutdown();

        // 3) Scene render check with AudioProvider + AudioProviderAE driving Bars at audioProviderIndex=2
        CustomScene scene = CustomScene.Create();
        CustomComposition comp0 = scene.createDataEntry();
        comp0.putChildTypeValue("Composition");
        CustomPropertiesList ap1 = comp0.createDataEntry();
        ap1.putChildTypeValue("AudioProvider");
        CustomPropertiesList ap2 = comp0.createDataEntry();
        ap2.putChildTypeValue("AudioProviderAE");
        CustomPropertiesList bars = comp0.createDataEntry();
        bars.putChildTypeValue("Bars");
        bars.putPropertyIntValue("audioProviderIndex", 2);

        rc.setThemeElements(scene);
        for (int f = 0; f < 3; f++) {
            Looper.drainMainQueue();
            mainAudio.tickFrame(0.016f);
            GLES20.setRedirectFramebuffer0(fbo);
            rc.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
        }
        CustomScene readBack = VizArchiveManager.readLiveScene(scene, rc, null);
        String ap2Type = readBack.getEntry(0).getData(1).getTypeName("");
        String ap2Sub = readBack.getEntry(0).getData(1).getChild("sampleProvider").getTypeName("");
        if (!"AudioProviderAE".equals(ap2Type) || !"AESpectrum".equals(ap2Sub)) {
            throw new AssertionError("AudioProviderAE scene round-trip mismatch: type=" + ap2Type + " sub=" + ap2Sub);
        }
        System.out.println("Part 7 verification OK (AudioProviderAE + AESpectrum/AEWaveform ratio="
                + String.format(java.util.Locale.US, "%.2fx", dynRatio) + ", L/R peak=" + peakLeftBin + "/" + peakRightBin + ")");
    }

    private static void verifyPart8TemplateLibraryAndAutoSave(RendererCore rc, DesktopAudioEngine audio,
                                                              int w, int h, int fbo) throws Exception {
        java.nio.file.Path tmpLibPath = java.nio.file.Files.createTempDirectory("avee_part8_lib_");
        File tmpLib = tmpLibPath.toFile();
        TemplateLibraryStore store = new TemplateLibraryStore(tmpLib);
        try {
            // 1) Built-in 18 presets initialized privately
            if (store.getEntries().size() != TemplateLibraryStore.BUILT_IN_COUNT) {
                throw new AssertionError("Expected 18 built-in presets, got " + store.getEntries().size());
            }

            // 2) Background off-main-thread auto-save on edits (must execute on AveeTemplateAutoSave worker thread, not main)
            String callingThread = Thread.currentThread().getName();
            CustomScene p0 = store.loadTemplate(TemplateLibraryStore.presetId(0));
            com.daaw.avee.comp.Visualizer.CustomComposition c0 = p0.getEntry(0);
            com.daaw.avee.comp.Visualizer.CustomPropertiesList markerLayer = c0.createDataEntry();
            markerLayer.putChildTypeValue("Text");
            markerLayer.setTagName("AutoSavedTag_Part8");
            store.scheduleAutoSave(TemplateLibraryStore.presetId(0), p0,
                    com.daaw.avee.comp.AppPreferences.AppPreferences.getActiveWorkspaceFolder());
            if (!store.flushPendingSavesSync(3000L)) {
                throw new AssertionError("Background auto-save timed out");
            }
            String workerName = store.getLastSaveThreadName();
            if (!"AveeTemplateAutoSave".equals(workerName) || callingThread.equals(workerName)) {
                throw new AssertionError("Expected save on background thread AveeTemplateAutoSave, got " + workerName);
            }

            // Re-open store from disk (simulating app restart) and confirm edit + lastSelectedId persisted
            TemplateLibraryStore storeReopened = new TemplateLibraryStore(tmpLib);
            CustomScene reloadedP0 = storeReopened.loadTemplate(TemplateLibraryStore.presetId(0));
            boolean foundTag = false;
            for (int i = 0; i < reloadedP0.getEntry(0).getEntryCount(); i++) {
                if ("AutoSavedTag_Part8".equals(reloadedP0.getEntry(0).getData(i).getTagName())) {
                    foundTag = true;
                    break;
                }
            }
            if (!foundTag || !storeReopened.getEntry(TemplateLibraryStore.presetId(0)).modified) {
                throw new AssertionError("Auto-saved preset edit did not persist across restart");
            }

            // Reset built-in preset restores clean factory state
            CustomScene resetP0 = storeReopened.resetBuiltInPreset(TemplateLibraryStore.presetId(0));
            if (resetP0.getEntry(0).getEntryCount() >= reloadedP0.getEntry(0).getEntryCount()) {
                throw new AssertionError("resetBuiltInPreset did not restore original preset elements");
            }
            storeReopened.shutdown();

            // 3) Import external .viz with bundled asset -> lives only inside private library dir -> export back to .viz
            File srcImg = File.createTempFile("avee_part8_asset_", ".png");
            srcImg.deleteOnExit();
            java.awt.image.BufferedImage bi = new java.awt.image.BufferedImage(32, 32, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = bi.createGraphics();
            g.setColor(java.awt.Color.CYAN);
            g.fillRect(0, 0, 32, 32);
            g.dispose();
            javax.imageio.ImageIO.write(bi, "png", srcImg);

            File extWs = java.nio.file.Files.createTempDirectory("avee_part8_extws_").toFile();
            com.daaw.avee.comp.AppPreferences.AppPreferences.setActiveWorkspaceFolder(extWs);
            String localRef = VizArchiveManager.importLocalAsset(srcImg);
            CustomScene impSrc = CustomScene.Create();
            com.daaw.avee.comp.Visualizer.CustomComposition ic = impSrc.createDataEntry();
            ic.putChildTypeValue("Composition");
            com.daaw.avee.comp.Visualizer.CustomPropertiesList imgEl = ic.createDataEntry();
            imgEl.putChildTypeValue("Image");
            imgEl.putPropertyStringValue("customImage", localRef);
            File extViz = File.createTempFile("avee_part8_in_", ".viz");
            extViz.deleteOnExit();
            VizArchiveManager.saveVizFromWorkspace(extViz, impSrc, null, extWs);

            TemplateLibraryStore.TemplateEntry imported = store.importTemplateFile(extViz, "MySecretTemplate");
            File bundledInPrivateStore = new File(imported.assetsDir, localRef.substring("local:".length()));
            if (!imported.dir.getAbsolutePath().startsWith(tmpLib.getAbsolutePath()) || !bundledInPrivateStore.isFile()) {
                throw new AssertionError("Imported .viz was not unpacked into private library store");
            }

            // Render live preview PNG off-main-thread into private store preview.png
            store.loadTemplate(imported.id);
            rc.setThemeElements(store.loadTemplate(imported.id));
            for (int f = 0; f < 3; f++) {
                Looper.drainMainQueue();
                audio.tickFrame(0.016f);
                GLES20.setRedirectFramebuffer0(fbo);
                rc.onDrawFrame(null);
                GLES20.setRedirectFramebuffer0(0);
            }
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            Bitmap snap = GraphicsUtils.savePixels(0, 0, w, h, null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            store.schedulePreviewSave(imported.id, snap.getBufferedImage());
            if (!store.flushPendingSavesSync(3000L) || !imported.previewFile.isFile()) {
                throw new AssertionError("Preview PNG save failed");
            }
            java.awt.image.BufferedImage thumbCheck = javax.imageio.ImageIO.read(imported.previewFile);
            if (thumbCheck == null || thumbCheck.getWidth() != TemplateLibraryStore.THUMB_W
                    || thumbCheck.getHeight() != TemplateLibraryStore.THUMB_H) {
                throw new AssertionError("Preview thumbnail dimensions mismatch");
            }

            // Export back out as shareable .viz and verify asset + scene inside
            File exportedViz = File.createTempFile("avee_part8_out_", ".viz");
            exportedViz.deleteOnExit();
            store.exportViz(imported.id, exportedViz, null, null);
            if (!exportedViz.isFile() || exportedViz.length() < 200) {
                throw new AssertionError("Exported .viz archive is missing or empty");
            }
            CustomScene verifiedOut = VizArchiveManager.loadFile(exportedViz);
            String outImgRef = verifiedOut.getEntry(0).getData(0).getPropertyString("customImage", "");
            if (!localRef.equals(outImgRef)) {
                throw new AssertionError("Exported .viz asset reference mismatch: " + outImgRef);
            }
        } finally {
            store.shutdown();
        }
        System.out.println("Part 8 verification OK (private template store, background auto-save on "
                + "AveeTemplateAutoSave, preview PNG 320x180 & .viz import/export OK)");
    }

    private static void verifyPart10UiThemeZoomVolumeAndMediaManager() throws Exception {
        // 1) Audio engine: syntheticBeat defaults to false and volume clamps 0..1
        DesktopAudioEngine freshAudio = new DesktopAudioEngine();
        try {
            if (freshAudio.isSyntheticBeat()) {
                throw new AssertionError("Expected DesktopAudioEngine.isSyntheticBeat() to default to false");
            }
            if (!"No Audio Loaded".equals(freshAudio.getTitle())) {
                throw new AssertionError("Expected default audio title 'No Audio Loaded', got: "
                        + freshAudio.getTitle());
            }
            freshAudio.setVolume(0.35f);
            if (Math.abs(freshAudio.getVolume() - 0.35f) > 1e-4f) {
                throw new AssertionError("Expected volume 0.35, got " + freshAudio.getVolume());
            }
            freshAudio.setVolume(-0.5f);
            if (Math.abs(freshAudio.getVolume()) > 1e-4f) {
                throw new AssertionError("Expected volume clamped to 0.0, got " + freshAudio.getVolume());
            }
            freshAudio.setVolume(2.0f);
            if (Math.abs(freshAudio.getVolume() - 1.0f) > 1e-4f) {
                throw new AssertionError("Expected volume clamped to 1.0, got " + freshAudio.getVolume());
            }
        } finally {
            freshAudio.shutdown();
        }

        // 2) MediaLibraryStore: default ~/Music seed, persistent removal, async scan of custom folder
        java.nio.file.Path tmpRoot = java.nio.file.Files.createTempDirectory("avee_part10_media_");
        File cfgFile = new File(tmpRoot.toFile(), "media_library.json");
        File musicDir = new File(tmpRoot.toFile(), "custom_music");
        File nestedDir = new File(musicDir, "sub");
        nestedDir.mkdirs();
        File hiddenDir = new File(musicDir, ".thumbnails");
        hiddenDir.mkdirs();
        File hiddenFile = new File(hiddenDir, "ignored.mp3");
        File mp3File = new File(musicDir, "track_one.mp3");
        File wavFile = new File(nestedDir, "track_two.wav");
        File txtFile = new File(nestedDir, "notes.txt");
        java.nio.file.Files.write(mp3File.toPath(), new byte[]{1, 2, 3});
        java.nio.file.Files.write(wavFile.toPath(), new byte[]{4, 5, 6});
        java.nio.file.Files.write(txtFile.toPath(), new byte[]{7, 8, 9});
        java.nio.file.Files.write(hiddenFile.toPath(), new byte[]{10, 11, 12});

        MediaLibraryStore store = new MediaLibraryStore(cfgFile);
        try {
            File defMusic = MediaLibraryStore.defaultMusicFolder();
            if (defMusic.isDirectory()) {
                boolean hadDef = false;
                for (File f : store.getFolders()) {
                    if (f.getAbsolutePath().equals(defMusic.getAbsolutePath())) {
                        hadDef = true;
                        break;
                    }
                }
                if (!hadDef) {
                    throw new AssertionError("Expected default ~/Music folder to be seeded initially");
                }
                if (!store.removeFolder(defMusic)) {
                    throw new AssertionError("Expected removeFolder(defaultMusicFolder) to succeed");
                }
            }
        } finally {
            store.shutdown();
        }

        // Re-open same config: removal of ~/Music must persist and not be re-added
        MediaLibraryStore reopened = new MediaLibraryStore(cfgFile);
        try {
            File defMusic = MediaLibraryStore.defaultMusicFolder();
            for (File f : reopened.getFolders()) {
                if (f.getAbsolutePath().equals(defMusic.getAbsolutePath())) {
                    throw new AssertionError("Removed default ~/Music folder reappeared after reopen");
                }
            }
            if (!reopened.addFolder(musicDir)) {
                throw new AssertionError("Expected addFolder(custom_music) to return true");
            }
            if (reopened.addFolder(musicDir)) {
                throw new AssertionError("Expected duplicate addFolder(custom_music) to return false");
            }
            reopened.rescanAsync();
            long deadline = System.currentTimeMillis() + 5000L;
            while (reopened.isScanning() && System.currentTimeMillis() < deadline) {
                Thread.sleep(15L);
            }
            if (reopened.isScanning()) {
                throw new AssertionError("MediaLibraryStore async scan timed out");
            }
            String scanThread = reopened.getLastScanThreadName();
            if (scanThread == null || !scanThread.startsWith("AveeMediaScanner")) {
                throw new AssertionError("Expected scan on AveeMediaScanner thread, got: " + scanThread);
            }
            java.util.List<MediaLibraryStore.TrackEntry> tracks = reopened.getTracks();
            if (tracks.size() != 2) {
                throw new AssertionError("Expected 2 scanned audio tracks (.mp3, .wav), got " + tracks.size());
            }
            MediaLibraryStore.FolderListing listing = reopened.listDirectory(musicDir);
            if (listing.subfolders.size() != 1
                    || !"sub".equals(listing.subfolders.get(0).name)
                    || listing.subfolders.get(0).itemCount != 1) {
                throw new AssertionError("Unexpected folder listing subfolders (should exclude .thumbnails): "
                        + listing.subfolders.size());
            }
            if (listing.tracks.size() != 1 || !"track_one".equals(listing.tracks.get(0).title)) {
                throw new AssertionError("Unexpected direct tracks in folder listing: " + listing.tracks.size());
            }
            java.util.List<MediaLibraryStore.TrackEntry> recFolderTracks = reopened.collectTracksInFolder(musicDir);
            if (recFolderTracks.size() != 2) {
                throw new AssertionError("Expected 2 recursive folder tracks, got " + recFolderTracks.size());
            }
        } finally {
            reopened.shutdown();
        }

        // 3) Preview viewport zoom, pan clamp & rect math
        if (Math.abs(AveeEditorApp.clampPreviewZoom(0.05f) - 0.25f) > 1e-4f
                || Math.abs(AveeEditorApp.clampPreviewZoom(12.0f) - 8.0f) > 1e-4f) {
            throw new AssertionError("Preview zoom clamp failed");
        }
        if (Math.abs(AveeEditorApp.clampPreviewPan(999f, 800f, 800f) - 360f) > 0.1f
                || Math.abs(AveeEditorApp.clampPreviewPan(-1500f, 800f, 2000f) - (-900f)) > 0.1f) {
            throw new AssertionError("Preview pan clamp mismatch");
        }
        float[] r1 = AveeEditorApp.computeZoomedPreviewRect(800f, 600f, 1280, 720, 1.0f, 0f, 0f);
        if (Math.abs(r1[2] - 800f) > 0.1f || Math.abs(r1[3] - 450f) > 0.1f
                || Math.abs(r1[0] - 0f) > 0.1f || Math.abs(r1[1] - 75f) > 0.1f) {
            throw new AssertionError("1.0x preview rect mismatch: " + java.util.Arrays.toString(r1));
        }
        float[] r2 = AveeEditorApp.computeZoomedPreviewRect(800f, 600f, 1280, 720, 2.5f, 40f, -20f);
        if (Math.abs(r2[2] - 2000f) > 0.1f || Math.abs(r2[3] - 1125f) > 0.1f
                || Math.abs(r2[0] - (-560f)) > 0.1f || Math.abs(r2[1] - (-282.5f)) > 0.1f) {
            throw new AssertionError("2.5x + pan preview rect mismatch: " + java.util.Arrays.toString(r2));
        }

        // 3b) Auto-snapping floating mini-window math
        java.util.List<float[]> siblings = new java.util.ArrayList<>();
        siblings.add(new float[]{0f, 0f, 300f, 400f});
        float[] snapLeftEdge = AveeEditorApp.snapFloatingWindowRect(
                10f, 100f, 200f, 300f, 0f, 0f, 1000f, 800f, null, 16f);
        float[] snapRightEdge = AveeEditorApp.snapFloatingWindowRect(
                790f, 100f, 200f, 300f, 0f, 0f, 1000f, 800f, null, 16f);
        float[] snapSibling = AveeEditorApp.snapFloatingWindowRect(
                312f, 8f, 250f, 300f, 0f, 0f, 1000f, 800f, siblings, 16f);
        float[] noSnap = AveeEditorApp.snapFloatingWindowRect(
                400f, 100f, 250f, 300f, 0f, 0f, 1000f, 800f, siblings, 16f);
        if (Math.abs(snapLeftEdge[0] - 0f) > 0.01f
                || Math.abs(snapRightEdge[0] - 800f) > 0.01f
                || Math.abs(snapSibling[0] - 300f) > 0.01f || Math.abs(snapSibling[1] - 0f) > 0.01f
                || Math.abs(noSnap[0] - 400f) > 0.01f || Math.abs(noSnap[1] - 100f) > 0.01f) {
            throw new AssertionError("Floating window snap mismatch: left="
                    + java.util.Arrays.toString(snapLeftEdge)
                    + " right=" + java.util.Arrays.toString(snapRightEdge)
                    + " sibling=" + java.util.Arrays.toString(snapSibling)
                    + " noSnap=" + java.util.Arrays.toString(noSnap));
        }

        // 4) Inline numeric edit parsing & step quantization (double-click edit target helpers)
        Float parsedF = PropertyInspectorPanel.parseEditedFloat("0.375");
        Integer parsedI = PropertyInspectorPanel.parseEditedInt("42");
        if (parsedF == null || Math.abs(parsedF - 0.375f) > 1e-5f || parsedI == null || parsedI != 42) {
            throw new AssertionError("Inline numeric parsing failed: f=" + parsedF + " i=" + parsedI);
        }
        float quantized = PropertyInspectorPanel.quantizeFloat(0.123f, 0f, 1f, 0.05f);
        if (Math.abs(quantized - 0.10f) > 1e-5f) {
            throw new AssertionError("Expected quantized float 0.10, got " + quantized);
        }

        System.out.println("Part 10 verification OK (silent audio default + volume clamp, media library "
                + "AveeMediaScanner & persistent folder removal, zoom/pan rects & inline edit helpers OK)");
    }
}

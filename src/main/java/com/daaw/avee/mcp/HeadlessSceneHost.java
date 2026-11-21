package com.daaw.avee.mcp;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Looper;
import com.daaw.avee.Common.Events.WeakEventR1;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.Visualizer.CustomComposition;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Composition;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import com.daaw.avee.editor.DesktopAudioEngine;
import com.daaw.avee.editor.VizArchiveManager;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;

public class HeadlessSceneHost implements SceneHost, AutoCloseable {
    private final boolean readOnly;
    private final ExecutorService glExecutor;
    private final List<Object> listenerHolders = new ArrayList<>();

    private long window = 0L;
    private int fbo = 0;
    private int tex = 0;
    private int previewW = 1280;
    private int previewH = 720;

    private DesktopAudioEngine audioEngine;
    private RendererCore rendererCore;
    private CustomScene currentScene;
    private int selectedCompIdx = 0;
    private int selectedElemIdx = -1;
    private File projectFile = null;
    private int activePresetIndex = 0;
    private boolean initialized = false;

    public HeadlessSceneHost(boolean readOnly) {
        this.readOnly = readOnly;
        this.glExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AveeMcpHeadlessGL");
            t.setDaemon(true);
            return t;
        });
    }

    public void init(int initialPreset, File initialProject) throws Exception {
        runOnGlThread(() -> {
            if (initialized) return null;
            if (!GLFW.glfwInit()) {
                throw new IllegalStateException("GLFW init failed in headless MCP host");
            }
            GLFW.glfwDefaultWindowHints();
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE);
            window = GLFW.glfwCreateWindow(640, 360, "AveeCraft-MCP-Headless", 0L, 0L);
            if (window == 0L) {
                throw new IllegalStateException("Failed to create offscreen GLFW window");
            }
            GLFW.glfwMakeContextCurrent(window);
            GL.createCapabilities();

            Composition.onRequest.subscribeWeak((WeakEventR1.Handler<Object, Boolean>) c -> true, listenerHolders);
            Context ctx = PlayerCore.s().getAppContext();
            audioEngine = new DesktopAudioEngine(false);
            audioEngine.setSyntheticBeat(true);
            rendererCore = new RendererCore(ctx, ctx.getResources(), audioEngine);
            rendererCore.onSurfaceCreated(null, null);
            ensureFbo(previewW, previewH);
            rendererCore.onSurfaceChanged(null, previewW, previewH);

            if (initialProject != null && initialProject.isFile()) {
                currentScene = VizArchiveManager.loadFile(initialProject);
                projectFile = initialProject;
                activePresetIndex = -1;
            } else {
                int preset = Math.max(0, Math.min(17, initialPreset));
                currentScene = VizArchiveManager.loadPreset(preset);
                activePresetIndex = preset;
            }
            rendererCore.setThemeElements(currentScene);
            refreshLiveScene();
            if (currentScene != null && currentScene.getEntryCount() > 0 && currentScene.getEntry(0).getEntryCount() > 0) {
                selectedCompIdx = 0;
                selectedElemIdx = 0;
            }
            capturePreviewImage(2, -1L);
            initialized = true;
            return null;
        });
    }

    public <T> T runOnGlThread(Callable<T> task) throws Exception {
        try {
            return glExecutor.submit(task).get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new RuntimeException(c);
        }
    }

    private void ensureFbo(int w, int h) {
        if (fbo != 0 && previewW == w && previewH == h) return;
        if (fbo != 0) {
            GL30.glDeleteFramebuffers(fbo);
            fbo = 0;
        }
        if (tex != 0) {
            GL11.glDeleteTextures(tex);
            tex = 0;
        }
        previewW = w;
        previewH = h;
        tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        fbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, tex, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        if (rendererCore != null) {
            rendererCore.onSurfaceChanged(null, w, h);
        }
    }

    @Override
    public String getMode() {
        return "headless";
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public CustomScene getScene() {
        return currentScene;
    }

    @Override
    public void setScene(CustomScene scene, boolean rebuildElements) {
        this.currentScene = scene;
        if (rendererCore != null && scene != null) {
            if (rebuildElements) {
                rendererCore.setThemeElements(scene);
            } else {
                rendererCore.setThemeCustomizationData(scene);
            }
        }
    }

    @Override
    public void refreshLiveScene() {
        if (rendererCore != null) {
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
            clampSelection();
        }
    }

    private void clampSelection() {
        if (currentScene == null || currentScene.getEntryCount() <= 0) {
            selectedCompIdx = 0;
            selectedElemIdx = -1;
            return;
        }
        selectedCompIdx = Math.max(0, Math.min(selectedCompIdx, currentScene.getEntryCount() - 1));
        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        int n = comp != null ? comp.getEntryCount() : 0;
        if (selectedElemIdx >= n) {
            selectedElemIdx = n - 1;
        }
    }

    @Override
    public RendererCore getRendererCore() {
        return rendererCore;
    }

    @Override
    public DesktopAudioEngine getAudioEngine() {
        return audioEngine;
    }

    @Override
    public int getSelectedCompIdx() {
        return selectedCompIdx;
    }

    @Override
    public int getSelectedElemIdx() {
        return selectedElemIdx;
    }

    @Override
    public void setSelection(int compIdx, int elemIdx, boolean triggerHighlight) {
        this.selectedCompIdx = compIdx;
        this.selectedElemIdx = elemIdx;
        clampSelection();
        if (triggerHighlight && rendererCore != null) {
            rendererCore.triggerHighlightByIndices(selectedCompIdx, selectedElemIdx);
        }
    }

    @Override
    public int getPreviewWidth() {
        return previewW;
    }

    @Override
    public int getPreviewHeight() {
        return previewH;
    }

    @Override
    public void setPreviewSize(int w, int h) {
        int cw = Math.max(128, Math.min(3840, w));
        int ch = Math.max(128, Math.min(2160, h));
        ensureFbo(cw, ch);
    }

    @Override
    public File getProjectFile() {
        return projectFile;
    }

    @Override
    public void setProjectFile(File file) {
        this.projectFile = file;
    }

    @Override
    public int getActivePresetIndex() {
        return activePresetIndex;
    }

    @Override
    public void setActivePresetIndex(int idx) {
        this.activePresetIndex = idx;
    }

    @Override
    public void setStatusMessage(String msg) {
    }

    @Override
    public BufferedImage capturePreviewImage(int advanceFrames, long seekMs) {
        ensureFbo(previewW, previewH);
        if (seekMs >= 0L) {
            audioEngine.stepOfflineFrame(seekMs, 0.016f);
        }
        int steps = Math.max(1, Math.min(120, advanceFrames));
        for (int i = 0; i < steps; i++) {
            Looper.drainMainQueue();
            audioEngine.tickFrame(0.016f);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GLES20.setRedirectFramebuffer0(fbo);
            rendererCore.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        Bitmap bmp = GraphicsUtils.savePixels(0, 0, previewW, previewH, null);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        audioEngine.setLatestStats(rendererCore.getFrameStats());
        return bmp != null ? bmp.getBufferedImage() : null;
    }

    @Override
    public void captureWindowImageAsync(CompletableFuture<BufferedImage> future) {
        future.completeExceptionally(new UnsupportedOperationException(
                "screenshot_window is only available when connected to the live GUI window; use render_preview in headless mode."));
    }

    @Override
    public void close() {
        if (!initialized) {
            glExecutor.shutdownNow();
            return;
        }
        try {
            runOnGlThread(() -> {
                if (audioEngine != null) audioEngine.shutdown();
                if (fbo != 0) {
                    GL30.glDeleteFramebuffers(fbo);
                    fbo = 0;
                }
                if (tex != 0) {
                    GL11.glDeleteTextures(tex);
                    tex = 0;
                }
                if (window != 0L) {
                    GLFW.glfwDestroyWindow(window);
                    window = 0L;
                }
                initialized = false;
                return null;
            });
        } catch (Throwable ignored) {
        } finally {
            glExecutor.shutdown();
        }
    }
}

package com.daaw.avee.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Looper;
import com.daaw.avee.Common.Events.WeakEventR1;
import com.daaw.avee.Common.tlog;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.Visualizer.CustomComposition;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Base.Element;
import com.daaw.avee.comp.Visualizer.Elements.Composition;
import com.daaw.avee.comp.Visualizer.Elements.ElementsFactory;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import com.daaw.avee.mcp.AveeSceneService;
import com.daaw.avee.mcp.McpBridgeServer;
import com.daaw.avee.mcp.McpStdioServer;
import com.daaw.avee.mcp.SceneHost;
import imgui.ImDrawList;
import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiConfigFlags;
import imgui.flag.ImGuiDir;
import imgui.flag.ImGuiDragDropFlags;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public class AveeEditorApp extends Application implements SceneHost {
    private static final int[][] RESOLUTIONS = {
            {1280, 720},
            {1920, 1080},
            {1080, 1920},
            {854, 480}
    };
    private static final String[] RESOLUTION_LABELS = {
            "1280x720 (HD)",
            "1920x1080 (FHD)",
            "1080x1920 (Vertical)",
            "854x480 (SD)"
    };
    private static final String[] RESOLUTION_LABELS_COMPACT = {
            "720p",
            "1080p",
            "1080x1920",
            "480p"
    };

    public static class PreviewBottomLayout {
        public final boolean twoRows;
        public final boolean compactResLabels;
        public final float resComboW;
        public final float totalBarHeight;

        PreviewBottomLayout(boolean twoRows, boolean compactResLabels, float resComboW, float totalBarHeight) {
            this.twoRows = twoRows;
            this.compactResLabels = compactResLabels;
            this.resComboW = resComboW;
            this.totalBarHeight = totalBarHeight;
        }
    }

    public static PreviewBottomLayout computePreviewBottomBarLayout(float availW) {
        if (availW >= 340.0f) {
            float comboW = Math.max(110.0f, Math.min(185.0f, availW - 208.0f));
            return new PreviewBottomLayout(false, false, comboW, 30.0f);
        }
        if (availW >= 265.0f) {
            float comboW = Math.max(76.0f, availW - 196.0f);
            return new PreviewBottomLayout(false, true, comboW, 30.0f);
        }
        float comboW = Math.max(72.0f, availW - 4.0f);
        return new PreviewBottomLayout(true, true, comboW, 58.0f);
    }

    public static boolean shouldRenderLivePreview(boolean isVisualizerPage, boolean isExporting, boolean hasPendingScreenshot) {
        return hasPendingScreenshot || (isVisualizerPage && !isExporting);
    }

    private DesktopAudioEngine audioEngine;
    private RendererCore rendererCore;
    private CustomScene currentScene;
    private final PropertyInspectorPanel inspector = new PropertyInspectorPanel();
    private final ExportDialogPanel exportDialog = new ExportDialogPanel();
    private final TemplatePickerPanel templatePicker = new TemplatePickerPanel();
    private final MediaLibraryPanel mediaPanel = new MediaLibraryPanel();
    private TemplateLibraryStore templateLibrary;
    private MediaLibraryStore mediaLibrary;
    private String activeTemplateId = TemplateLibraryStore.presetId(0);
    private RendererCore thumbRenderer;
    private int thumbFbo = 0;
    private int thumbTex = 0;
    private final java.util.Set<String> warmedPreviewIds = new java.util.HashSet<>();
    private boolean previewRefreshPending = false;
    private long previewDirtyAtNs = 0L;

    private enum AppPage {
        VISUALIZER,
        LIBRARY,
        EXPORT
    }

    private AppPage activePage = AppPage.VISUALIZER;
    private final ImBoolean showSceneHierarchy = new ImBoolean(true);
    private final ImBoolean showPropertyInspector = new ImBoolean(true);
    private boolean dockLayoutInitialized = false;
    private boolean requestResetDockLayout = false;
    private final java.util.Map<String, float[]> floatingWindowRects = new java.util.LinkedHashMap<>();
    private final java.util.Set<String> movedFloatingWindows = new java.util.HashSet<>();
    private boolean prevMouseDownLeft = false;

    private final List<File> playbackQueue = new ArrayList<>();
    private int playbackQueueIndex = -1;
    private long lastObservedCompletedCount = 0L;
    private boolean loopSingleTrack = false;

    private float previewZoom = 1.0f;
    private float previewPanX = 0.0f;
    private float previewPanY = 0.0f;
    private final float[] volumeTmp = {100.0f};

    private int selectedCompIdx = 0;
    private int selectedElemIdx = -1; // -1 selects the composition itself
    private int[] activeDragPayload = null;
    private int activeCompDragPayload = -1;

    private int previewW = 1280;
    private int previewH = 720;
    private final ImInt resCombo = new ImInt(0);
    private int previewFbo = 0;
    private int previewTex = 0;
    private int fboW = 0;
    private int fboH = 0;

    private String statusMessage = "Ready";
    private File currentProjectFile = null;
    private long lastFrameNs = System.nanoTime();
    private final ImBoolean hqBlurChk = new ImBoolean(true);
    private final float[] seekTmp = new float[1];
    private final ImString timestampEditBuf = new ImString(32);
    private boolean timestampEditing = false;
    private final int smokeFrames = Integer.getInteger("avee.smokeFrames", 0);
    private int frameCounter = 0;
    private final List<Object> listenerHolders = new ArrayList<>();
    private boolean aboutOpen = false;
    private boolean updateDialogOpen = false;
    private final UpdateManager updateManager = new UpdateManager();
    private final ImBoolean autoCheckChk = new ImBoolean(true);
    private final ImBoolean autoDownloadChk = new ImBoolean(false);
    private int activePresetIdx = 0;
    private volatile boolean mcpReadOnly = false;
    private final AveeSceneService mcpSceneService = new AveeSceneService(this);
    private final McpBridgeServer mcpBridge = new McpBridgeServer(mcpSceneService);
    private final ImBoolean mcpEnabledChk = new ImBoolean(false);
    private final ImBoolean mcpAllowEditsChk = new ImBoolean(true);
    private java.util.concurrent.CompletableFuture<BufferedImage> pendingWindowScreenshot = null;
    private int aboutIconTex = 0;
    private static final String REPO_URL = "https://github.com/thanhcong2k7/AveeCraft";

    @Override
    protected void configure(Configuration config) {
        config.setTitle("AveeCraft — Desktop Visualizer Editor");
        config.setWidth(1500);
        config.setHeight(880);
    }

    @Override
    protected void initImGui(Configuration config) {
        super.initImGui(config);
        String fontPath = null;
        String[] candidates = {
                "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
                "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
                "C:/Windows/Fonts/segoeui.ttf",
                "C:/Windows/Fonts/arial.ttf"
        };
        for (String c : candidates) {
            if (new File(c).isFile()) {
                fontPath = c;
                break;
            }
        }
        if (fontPath != null) {
            ImFontAtlas fonts = ImGui.getIO().getFonts();
            fonts.clear();
            ImFontConfig cfg = new ImFontConfig();
            ImFont loaded = fonts.addFontFromFileTTF(fontPath, 15.0f, cfg, fonts.getGlyphRangesVietnamese());
            boolean built = (loaded != null) && fonts.build();
            cfg.destroy();
            if (!built) {
                fonts.clear();
                fonts.addFontDefault();
                fonts.build();
            }
        }
        ImGui.getIO().addConfigFlags(ImGuiConfigFlags.DockingEnable);
        ImGui.getIO().setConfigWindowsMoveFromTitleBarOnly(true);
        applyModernUiTheme();
    }

    private static void applyModernUiTheme() {
        imgui.ImGuiStyle st = ImGui.getStyle();
        st.setWindowPadding(8.0f, 8.0f);
        st.setFramePadding(7.0f, 4.0f);
        st.setItemSpacing(7.0f, 5.0f);
        st.setItemInnerSpacing(5.0f, 4.0f);
        st.setIndentSpacing(16.0f);
        st.setScrollbarSize(12.0f);
        st.setGrabMinSize(10.0f);
        st.setWindowBorderSize(1.0f);
        st.setChildBorderSize(1.0f);
        st.setFrameBorderSize(1.0f);
        st.setWindowRounding(6.0f);
        st.setChildRounding(5.0f);
        st.setFrameRounding(5.0f);
        st.setPopupRounding(5.0f);
        st.setScrollbarRounding(6.0f);
        st.setGrabRounding(4.0f);
        st.setTabRounding(5.0f);

        st.setColor(ImGuiCol.Text, 0.93f, 0.95f, 0.98f, 1.0f);
        st.setColor(ImGuiCol.TextDisabled, 0.54f, 0.59f, 0.67f, 1.0f);
        st.setColor(ImGuiCol.WindowBg, 0.08f, 0.09f, 0.12f, 0.98f);
        st.setColor(ImGuiCol.ChildBg, 0.10f, 0.11f, 0.15f, 0.96f);
        st.setColor(ImGuiCol.PopupBg, 0.10f, 0.11f, 0.15f, 0.98f);
        st.setColor(ImGuiCol.Border, 0.21f, 0.25f, 0.33f, 0.85f);
        st.setColor(ImGuiCol.FrameBg, 0.14f, 0.16f, 0.21f, 1.0f);
        st.setColor(ImGuiCol.FrameBgHovered, 0.19f, 0.22f, 0.29f, 1.0f);
        st.setColor(ImGuiCol.FrameBgActive, 0.22f, 0.26f, 0.35f, 1.0f);
        st.setColor(ImGuiCol.TitleBg, 0.09f, 0.11f, 0.15f, 1.0f);
        st.setColor(ImGuiCol.TitleBgActive, 0.13f, 0.16f, 0.22f, 1.0f);
        st.setColor(ImGuiCol.TitleBgCollapsed, 0.08f, 0.09f, 0.12f, 0.85f);
        st.setColor(ImGuiCol.MenuBarBg, 0.09f, 0.10f, 0.14f, 1.0f);
        st.setColor(ImGuiCol.ScrollbarBg, 0.07f, 0.08f, 0.11f, 0.8f);
        st.setColor(ImGuiCol.ScrollbarGrab, 0.24f, 0.28f, 0.36f, 1.0f);
        st.setColor(ImGuiCol.ScrollbarGrabHovered, 0.32f, 0.38f, 0.49f, 1.0f);
        st.setColor(ImGuiCol.ScrollbarGrabActive, 0.22f, 0.65f, 0.90f, 1.0f);
        st.setColor(ImGuiCol.CheckMark, 0.24f, 0.78f, 0.98f, 1.0f);
        st.setColor(ImGuiCol.SliderGrab, 0.22f, 0.68f, 0.92f, 1.0f);
        st.setColor(ImGuiCol.SliderGrabActive, 0.34f, 0.82f, 1.00f, 1.0f);
        st.setColor(ImGuiCol.Button, 0.17f, 0.21f, 0.28f, 1.0f);
        st.setColor(ImGuiCol.ButtonHovered, 0.21f, 0.44f, 0.68f, 1.0f);
        st.setColor(ImGuiCol.ButtonActive, 0.17f, 0.37f, 0.58f, 1.0f);
        st.setColor(ImGuiCol.Header, 0.18f, 0.22f, 0.30f, 0.90f);
        st.setColor(ImGuiCol.HeaderHovered, 0.20f, 0.44f, 0.68f, 0.85f);
        st.setColor(ImGuiCol.HeaderActive, 0.19f, 0.38f, 0.60f, 1.0f);
        st.setColor(ImGuiCol.Separator, 0.22f, 0.26f, 0.34f, 0.80f);
        st.setColor(ImGuiCol.ResizeGrip, 0.24f, 0.72f, 0.95f, 0.30f);
        st.setColor(ImGuiCol.ResizeGripHovered, 0.24f, 0.72f, 0.95f, 0.70f);
        st.setColor(ImGuiCol.ResizeGripActive, 0.34f, 0.82f, 1.00f, 0.95f);
        st.setColor(ImGuiCol.Tab, 0.13f, 0.16f, 0.21f, 0.95f);
        st.setColor(ImGuiCol.TabHovered, 0.20f, 0.45f, 0.70f, 1.0f);
        st.setColor(ImGuiCol.TabActive, 0.16f, 0.36f, 0.56f, 1.0f);
        st.setColor(ImGuiCol.TabUnfocused, 0.10f, 0.12f, 0.16f, 0.95f);
        st.setColor(ImGuiCol.TabUnfocusedActive, 0.14f, 0.23f, 0.34f, 1.0f);
        st.setColor(ImGuiCol.DockingPreview, 0.14f, 0.68f, 0.94f, 0.55f);
        st.setColor(ImGuiCol.DockingEmptyBg, 0.06f, 0.07f, 0.10f, 1.0f);
    }

    @Override
    protected void preRun() {
        super.preRun();
        applyWindowIcon();
        Composition.onRequest.subscribeWeak((WeakEventR1.Handler<Object, Boolean>) c -> true, listenerHolders);
        Context ctx = PlayerCore.s().getAppContext();
        audioEngine = new DesktopAudioEngine();
        rendererCore = new RendererCore(ctx, ctx.getResources(), audioEngine);
        rendererCore.onSurfaceCreated(null, null);
        ensurePreviewFbo(previewW, previewH);
        rendererCore.onSurfaceChanged(null, previewW, previewH);

        templateLibrary = new TemplateLibraryStore();
        mediaLibrary = new MediaLibraryStore();
        mediaLibrary.rescanAsync();
        thumbRenderer = new RendererCore(ctx, ctx.getResources(), audioEngine);
        thumbRenderer.onSurfaceCreated(null, null);
        ensureThumbFbo(TemplateLibraryStore.THUMB_W, TemplateLibraryStore.THUMB_H);
        thumbRenderer.onSurfaceChanged(null, TemplateLibraryStore.THUMB_W, TemplateLibraryStore.THUMB_H);

        loadTemplateFromLibrary(templateLibrary.getLastSelectedId());
        if (updateManager.getSettings().autoCheckUpdates) {
            updateManager.checkForUpdatesAsync(false);
        }
    }

    private void applyWindowIcon() {
        long win = GLFW.glfwGetCurrentContext();
        if (win == 0L) return;
        try (InputStream is = AveeEditorApp.class.getResourceAsStream("/drawable/aveecraft.png")) {
            if (is == null) return;
            BufferedImage img = ImageIO.read(is);
            if (img == null) return;
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer pixels = MemoryUtil.memAlloc(w * h * 4);
            for (int px : argb) {
                pixels.put((byte) ((px >> 16) & 0xFF));
                pixels.put((byte) ((px >> 8) & 0xFF));
                pixels.put((byte) (px & 0xFF));
                pixels.put((byte) ((px >> 24) & 0xFF));
            }
            pixels.flip();
            try (GLFWImage.Buffer icons = GLFWImage.malloc(1)) {
                icons.position(0).width(w).height(h).pixels(pixels);
                icons.position(0);
                GLFW.glfwSetWindowIcon(win, icons);
            } finally {
                pixels.rewind();
                aboutIconTex = GL11.glGenTextures();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, aboutIconTex);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
                MemoryUtil.memFree(pixels);
            }
        } catch (Throwable t) {
            tlog.w("Failed to set window icon: " + t.getMessage());
        }
    }

    @Override
    public void process() {
        long nowNs = System.nanoTime();
        float dtSec = (float) Math.max(0.001, Math.min(0.1, (nowNs - lastFrameNs) / 1_000_000_000.0));
        lastFrameNs = nowNs;

        // Capture completed previous frame from front buffer if requested by screenshot_window
        if (pendingWindowScreenshot != null) {
            java.util.concurrent.CompletableFuture<BufferedImage> target = pendingWindowScreenshot;
            pendingWindowScreenshot = null;
            try {
                long ctxWin = GLFW.glfwGetCurrentContext();
                int[] fbW = new int[1];
                int[] fbH = new int[1];
                GLFW.glfwGetFramebufferSize(ctxWin, fbW, fbH);
                int cw = Math.max(1, fbW[0]);
                int ch = Math.max(1, fbH[0]);
                GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, 0);
                GL11.glReadBuffer(GL11.GL_FRONT);
                Bitmap winBmp = GraphicsUtils.savePixels(0, 0, cw, ch, null);
                GL11.glReadBuffer(GL11.GL_BACK);
                target.complete(winBmp != null ? winBmp.getBufferedImage() : null);
            } catch (Throwable t) {
                target.completeExceptionally(t);
            }
        }

        // Sample hovered screen/window pixel patch for eyedropper while front buffer holds clean frame
        if (inspector.getColorPicker().isActive()) {
            inspector.getColorPicker().captureFrontBufferPatch(GLFW.glfwGetCurrentContext());
        }

        // Run main-thread callbacks (e.g. image decode results), drain MCP requests, and tick clock
        Looper.drainMainQueue();
        mcpBridge.drainUiTasks();
        audioEngine.tickFrame(dtSec);

        // Render Avee scene into offscreen preview FBO only when visible and not exporting
        boolean renderPreview = shouldRenderLivePreview(
                activePage == AppPage.VISUALIZER,
                exportDialog.isExporting(),
                pendingWindowScreenshot != null);
        if (renderPreview) {
            ensurePreviewFbo(previewW, previewH);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
            GLES20.setRedirectFramebuffer0(previewFbo);
            rendererCore.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            audioEngine.setLatestStats(rendererCore.getFrameStats());
            updateTemplatePreviewsIncremental(nowNs);
        } else if (activePage == AppPage.EXPORT || exportDialog.isExporting()) {
            deletePreviewFbo();
            deleteThumbFbo();
        }

        renderEditorUi();
        frameCounter++;
        if (smokeFrames > 0 && frameCounter >= smokeFrames) {
            long ctxWin = org.lwjgl.glfw.GLFW.glfwGetCurrentContext();
            if (ctxWin != 0L) {
                org.lwjgl.glfw.GLFW.glfwSetWindowShouldClose(ctxWin, true);
            }
        }
    }

    @Override
    protected void postRun() {
        mcpBridge.stop();
        updateManager.shutdown();
        if (exportDialog.isExporting()) {
            exportDialog.cancelExport();
        }
        templatePicker.dispose();
        if (templateLibrary != null) {
            templateLibrary.shutdown();
        }
        if (mediaLibrary != null) {
            mediaLibrary.shutdown();
        }
        inspector.dispose();
        if (audioEngine != null) {
            audioEngine.shutdown();
        }
        if (aboutIconTex != 0) {
            GL11.glDeleteTextures(aboutIconTex);
            aboutIconTex = 0;
        }
        deleteThumbFbo();
        deletePreviewFbo();
        super.postRun();
    }

    private void ensurePreviewFbo(int w, int h) {
        if (previewFbo != 0 && fboW == w && fboH == h) return;
        deletePreviewFbo();

        previewTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, previewTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        previewFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, previewTex, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);

        fboW = w;
        fboH = h;
        if (rendererCore != null) {
            rendererCore.onSurfaceChanged(null, w, h);
        }
    }

    private void deletePreviewFbo() {
        if (previewFbo != 0) {
            GL30.glDeleteFramebuffers(previewFbo);
            previewFbo = 0;
        }
        if (previewTex != 0) {
            GL11.glDeleteTextures(previewTex);
            previewTex = 0;
        }
        fboW = fboH = 0;
    }

    private void ensureThumbFbo(int w, int h) {
        if (thumbFbo != 0) return;
        thumbTex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, thumbTex);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, (ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);

        thumbFbo = GL30.glGenFramebuffers();
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, thumbFbo);
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, thumbTex, 0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
    }

    private void deleteThumbFbo() {
        if (thumbFbo != 0) {
            GL30.glDeleteFramebuffers(thumbFbo);
            thumbFbo = 0;
        }
        if (thumbTex != 0) {
            GL11.glDeleteTextures(thumbTex);
            thumbTex = 0;
        }
    }

    private void updateTemplatePreviewsIncremental(long nowNs) {
        if (templateLibrary == null || activeTemplateId == null) return;
        if (previewRefreshPending && (nowNs - previewDirtyAtNs) > 700_000_000L && previewFbo != 0) {
            previewRefreshPending = false;
            try {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
                Bitmap bmp = GraphicsUtils.savePixels(0, 0, previewW, previewH, null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                if (bmp != null && bmp.getBufferedImage() != null) {
                    templateLibrary.schedulePreviewSave(activeTemplateId, bmp.getBufferedImage());
                }
            } catch (Throwable ignored) {
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            }
            return;
        }
        if (thumbRenderer == null || thumbFbo == 0 || (frameCounter % 2) != 0) return;
        TemplateLibraryStore.TemplateEntry missing = null;
        for (TemplateLibraryStore.TemplateEntry e : templateLibrary.getEntries()) {
            if (!e.previewFile.isFile() && !e.id.equals(activeTemplateId) && !warmedPreviewIds.contains(e.id)) {
                missing = e;
                break;
            }
        }
        if (missing == null) {
            TemplateLibraryStore.TemplateEntry cur = templateLibrary.getEntry(activeTemplateId);
            if (cur != null && !cur.previewFile.isFile() && previewFbo != 0 && warmedPreviewIds.add(cur.id)) {
                try {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
                    Bitmap bmp = GraphicsUtils.savePixels(0, 0, previewW, previewH, null);
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                    if (bmp != null && bmp.getBufferedImage() != null) {
                        templateLibrary.schedulePreviewSave(cur.id, bmp.getBufferedImage());
                    }
                } catch (Throwable ignored) {
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                }
            }
            return;
        }
        warmedPreviewIds.add(missing.id);
        File prevWs = com.daaw.avee.comp.AppPreferences.AppPreferences.getActiveWorkspaceFolder();
        try {
            com.daaw.avee.comp.AppPreferences.AppPreferences.setActiveWorkspaceFolder(missing.assetsDir);
            CustomScene s = missing.sceneFile.isFile()
                    ? CustomScene.deserialize(java.nio.file.Files.readString(missing.sceneFile.toPath(), java.nio.charset.StandardCharsets.UTF_8))
                    : null;
            if (s != null) {
                thumbRenderer.setThemeElements(s);
                for (int i = 0; i < 2; i++) {
                    Looper.drainMainQueue();
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, thumbFbo);
                    GLES20.setRedirectFramebuffer0(thumbFbo);
                    thumbRenderer.onDrawFrame(null);
                    GLES20.setRedirectFramebuffer0(0);
                    GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                }
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, thumbFbo);
                Bitmap bmp = GraphicsUtils.savePixels(0, 0, TemplateLibraryStore.THUMB_W, TemplateLibraryStore.THUMB_H, null);
                GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
                if (bmp != null && bmp.getBufferedImage() != null) {
                    templateLibrary.schedulePreviewSave(missing.id, bmp.getBufferedImage());
                }
            }
        } catch (Throwable ignored) {
            GLES20.setRedirectFramebuffer0(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        } finally {
            if (prevWs != null) {
                com.daaw.avee.comp.AppPreferences.AppPreferences.setActiveWorkspaceFolder(prevWs);
            }
        }
    }

    private void renderEditorUi() {
        renderMainMenuBar();
        syncPlaybackQueueAdvance();

        boolean mouseDownLeft = ImGui.isMouseDown(0);
        boolean mouseReleasedLeft = prevMouseDownLeft && !mouseDownLeft;
        prevMouseDownLeft = mouseDownLeft;

        ImGuiViewport vp = ImGui.getMainViewport();
        float vpX = vp.getWorkPosX();
        float vpY = vp.getWorkPosY();
        float totalW = Math.max(760f, vp.getWorkSizeX());
        float totalH = Math.max(460f, vp.getWorkSizeY());

        float navW = 148f;
        float bottomBarH = 68f;
        float contentX = vpX + navW;
        float contentY = vpY;
        float contentW = Math.max(400f, totalW - navW);
        float contentH = Math.max(240f, totalH - bottomBarH);

        // 1. Left Persistent Navigation Sidebar (Library / Visualizer)
        renderNavigationSidebar(vpX, vpY, navW, contentH);

        // 2. Main Workspace Content (Library Page OR Dockable Auto-Snapping Visualizer Mini-Windows)
        if (activePage == AppPage.LIBRARY) {
            ImGui.setNextWindowPos(contentX, contentY, imgui.flag.ImGuiCond.Always);
            ImGui.setNextWindowSize(contentW, contentH, imgui.flag.ImGuiCond.Always);
            int libFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoSavedSettings;
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
            ImGui.begin("##LibraryPageHost", libFlags);
            mediaPanel.renderLibraryPage(mediaLibrary, new MediaLibraryPanel.HostCallbacks() {
                @Override
                public void onPlayTrack(File audioFile) {
                    if (audioFile != null) {
                        playQueue(java.util.Collections.singletonList(audioFile), 0);
                    }
                }

                @Override
                public void onPlayQueue(List<File> tracks, int startIndex) {
                    playQueue(tracks, startIndex);
                }

                @Override
                public void onEnqueueTracks(List<File> tracks) {
                    enqueueTracks(tracks);
                }

                @Override
                public File getCurrentAudioFile() {
                    return audioEngine != null ? audioEngine.getAudioFile() : null;
                }

                @Override
                public List<File> getPlaybackQueue() {
                    return new ArrayList<>(playbackQueue);
                }

                @Override
                public int getPlaybackQueueIndex() {
                    return playbackQueueIndex;
                }

                @Override
                public void onOpenVisualizer() {
                    activePage = AppPage.VISUALIZER;
                }
            });
            ImGui.end();
            ImGui.popStyleVar(3);
        } else if (activePage == AppPage.EXPORT) {
            ImGui.setNextWindowPos(contentX, contentY, imgui.flag.ImGuiCond.Always);
            ImGui.setNextWindowSize(contentW, contentH, imgui.flag.ImGuiCond.Always);
            int expFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoSavedSettings;
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 14f, 12f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
            ImGui.begin("##ExportPageHost", expFlags);
            exportDialog.renderEmbeddedPage(currentScene, rendererCore, audioEngine, previewW, previewH,
                    () -> activePage = AppPage.VISUALIZER);
            ImGui.end();
            ImGui.popStyleVar(3);
        } else {
            ImGui.setNextWindowPos(contentX, contentY, imgui.flag.ImGuiCond.Always);
            ImGui.setNextWindowSize(contentW, contentH, imgui.flag.ImGuiCond.Always);
            int hostFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                    | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking
                    | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus
                    | ImGuiWindowFlags.NoSavedSettings;
            ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0f);
            ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
            ImGui.begin("##VisualizerDockHost", hostFlags);
            int dockId = ImGui.getID("AveeVisualizerDockSpace");
            if (!dockLayoutInitialized || requestResetDockLayout) {
                dockLayoutInitialized = true;
                requestResetDockLayout = false;
                floatingWindowRects.clear();
                movedFloatingWindows.clear();
                imgui.internal.ImGui.dockBuilderRemoveNode(dockId);
                imgui.internal.ImGui.dockBuilderAddNode(dockId, imgui.internal.flag.ImGuiDockNodeFlags.DockSpace);
                imgui.internal.ImGui.dockBuilderSetNodePos(dockId, contentX, contentY);
                imgui.internal.ImGui.dockBuilderSetNodeSize(dockId, contentW, contentH);
                ImInt dockMainId = new ImInt(dockId);
                ImInt leftId = new ImInt();
                ImInt rightId = new ImInt();
                imgui.internal.ImGui.dockBuilderSplitNode(dockMainId.get(), ImGuiDir.Left, 0.21f, leftId, dockMainId);
                imgui.internal.ImGui.dockBuilderSplitNode(dockMainId.get(), ImGuiDir.Right, 0.34f, rightId, dockMainId);
                imgui.internal.ImGui.dockBuilderDockWindow("Scene Hierarchy###HierarchyMini", leftId.get());
                imgui.internal.ImGui.dockBuilderDockWindow("Property Inspector###InspectorMini", rightId.get());
                imgui.internal.ImGui.dockBuilderDockWindow("Preview###PreviewMini", dockMainId.get());
                imgui.internal.ImGui.dockBuilderFinish(dockId);
            }
            ImGui.dockSpace(dockId, 0f, 0f, imgui.internal.flag.ImGuiDockNodeFlags.None);
            ImGui.end();
            ImGui.popStyleVar(3);

            int miniFlags = ImGuiWindowFlags.NoCollapse;
            ImGui.setNextWindowSize(Math.max(420f, contentW * 0.48f), Math.max(320f, contentH * 0.85f),
                    imgui.flag.ImGuiCond.FirstUseEver);
            ImGui.begin("Preview###PreviewMini", miniFlags);
            renderPreviewViewportPanel();
            trackAndSnapFloatingWindow("Preview###PreviewMini", contentX, contentY, contentW, contentH, mouseReleasedLeft);
            ImGui.end();

            if (showSceneHierarchy.get()) {
                ImGui.setNextWindowSize(280f, Math.max(300f, contentH * 0.85f), imgui.flag.ImGuiCond.FirstUseEver);
                ImGui.begin("Scene Hierarchy###HierarchyMini", showSceneHierarchy, miniFlags);
                renderSceneTreePanel();
                trackAndSnapFloatingWindow("Scene Hierarchy###HierarchyMini", contentX, contentY, contentW, contentH, mouseReleasedLeft);
                ImGui.end();
            } else {
                floatingWindowRects.remove("Scene Hierarchy###HierarchyMini");
                movedFloatingWindows.remove("Scene Hierarchy###HierarchyMini");
            }

            if (showPropertyInspector.get()) {
                ImGui.setNextWindowSize(360f, Math.max(320f, contentH * 0.85f), imgui.flag.ImGuiCond.FirstUseEver);
                ImGui.begin("Property Inspector###InspectorMini", showPropertyInspector, miniFlags);
                renderInspectorPanel();
                trackAndSnapFloatingWindow("Property Inspector###InspectorMini", contentX, contentY, contentW, contentH, mouseReleasedLeft);
                ImGui.end();
            } else {
                floatingWindowRects.remove("Property Inspector###InspectorMini");
                movedFloatingWindows.remove("Property Inspector###InspectorMini");
            }
        }

        // 3. Persistent Bottom Playback Bar (always visible across Library, Visualizer, and Export)
        renderBottomPlaybackBar(vpX, vpY + totalH - bottomBarH, totalW, bottomBarH);

        renderTemplatePickerModal();
        renderAboutDialog();
        renderUpdateDialog();
        inspector.getColorPicker().renderOverlay(() -> {
            applySceneChanges(true);
            statusMessage = "Picked color from screen";
        });
    }

    private void openExportPage() {
        exportDialog.prepareForPage(audioEngine);
        activePage = AppPage.EXPORT;
    }

    private String getActiveTemplateDisplayName() {
        if (templateLibrary != null && activeTemplateId != null) {
            TemplateLibraryStore.TemplateEntry entry = templateLibrary.getEntry(activeTemplateId);
            if (entry != null && entry.title != null && !entry.title.trim().isEmpty()) {
                return entry.title.trim();
            }
        }
        return activePresetIdx >= 0 ? ("Visualizer Preset " + activePresetIdx) : "Custom Scene";
    }

    private void renderNavigationSidebar(float x, float y, float w, float h) {
        ImGui.setNextWindowPos(x, y, imgui.flag.ImGuiCond.Always);
        ImGui.setNextWindowSize(w, h, imgui.flag.ImGuiCond.Always);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoSavedSettings;
        ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.065f, 0.075f, 0.10f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.begin("##AppNavSidebar", flags);

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addLine(x + w - 1f, y, x + w - 1f, y + h, ImGui.getColorU32(0.18f, 0.22f, 0.29f, 0.9f), 1.0f);
        dl.addText(x + 16f, y + 14f, ImGui.getColorU32(0.95f, 0.97f, 1.0f, 1.0f), "AveeCraft");
        dl.addLine(x + 12f, y + 40f, x + w - 12f, y + 40f, ImGui.getColorU32(0.20f, 0.24f, 0.32f, 0.7f), 1.0f);

        if (renderSidebarNavItem(dl, "Library", "##NavLibrary", x, y + 50f, w, 42f,
                activePage == AppPage.LIBRARY, 0)) {
            activePage = AppPage.LIBRARY;
        }
        if (renderSidebarNavItem(dl, "Visualizer", "##NavVisualizer", x, y + 96f, w, 42f,
                activePage == AppPage.VISUALIZER, 1)) {
            activePage = AppPage.VISUALIZER;
        }
        String exportLabel = exportDialog.isExporting()
                ? ("Export " + Math.round(exportDialog.getProgressFraction() * 100.0f) + "%")
                : "Export";
        if (renderSidebarNavItem(dl, exportLabel, "##NavExport", x, y + 142f, w, 42f,
                activePage == AppPage.EXPORT, 2)) {
            openExportPage();
        }

        // Bottom sidebar summary (template chooser button moved to Preview top bar)
        float footerY = y + h - 54f;
        dl.addLine(x + 12f, footerY - 8f, x + w - 12f, footerY - 8f, ImGui.getColorU32(0.20f, 0.24f, 0.32f, 0.7f), 1.0f);
        int trackCnt = mediaLibrary != null ? mediaLibrary.getTracks().size() : 0;
        dl.addText(x + 14f, footerY, ImGui.getColorU32(0.54f, 0.59f, 0.67f, 1.0f), trackCnt + " audio tracks");

        ImGui.setCursorScreenPos(x + 14f, footerY + 22f);
        dl.pushClipRect(x + 10f, footerY + 18f, x + w - 10f, y + h - 4f, true);
        ImGui.pushStyleColor(ImGuiCol.Text, 0.52f, 0.57f, 0.65f, 1.0f);
        ImGui.textUnformatted(getActiveTemplateDisplayName());
        ImGui.popStyleColor();
        dl.popClipRect();

        ImGui.end();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    private boolean renderSidebarNavItem(ImDrawList dl, String label, String uid,
                                         float x, float y, float w, float h,
                                         boolean active, int iconKind) {
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton(uid, w, h);
        boolean hovered = ImGui.isItemHovered();
        if (active) {
            dl.addRectFilled(x, y, x + w - 1f, y + h, ImGui.getColorU32(0.11f, 0.20f, 0.28f, 0.95f));
            dl.addRectFilled(x, y, x + 4f, y + h, ImGui.getColorU32(0.0f, 0.72f, 0.96f, 1.0f));
        } else if (hovered) {
            dl.addRectFilled(x, y, x + w - 1f, y + h, ImGui.getColorU32(0.14f, 0.17f, 0.23f, 0.85f));
        }
        int iconCol = active ? ImGui.getColorU32(0.24f, 0.82f, 1.0f, 1.0f) : ImGui.getColorU32(0.66f, 0.71f, 0.78f, 1.0f);
        int textCol = active ? ImGui.getColorU32(0.96f, 0.98f, 1.0f, 1.0f) : ImGui.getColorU32(0.76f, 0.80f, 0.86f, 1.0f);
        float icx = x + 24f;
        float icy = y + h * 0.5f;
        if (iconKind == 0) {
            dl.addRectFilled(icx - 7f, icy - 6f, icx - 1f, icy - 3f, iconCol, 1.5f);
            dl.addRectFilled(icx - 7f, icy - 4f, icx + 7f, icy + 6f, iconCol, 1.5f);
        } else if (iconKind == 1) {
            dl.addRectFilled(icx - 7f, icy - 1f, icx - 4f, icy + 6f, iconCol, 1f);
            dl.addRectFilled(icx - 1.5f, icy - 7f, icx + 1.5f, icy + 6f, iconCol, 1f);
            dl.addRectFilled(icx + 4f, icy - 4f, icx + 7f, icy + 6f, iconCol, 1f);
        } else {
            dl.addLine(icx, icy + 2f, icx, icy - 6f, iconCol, 2.0f);
            dl.addTriangleFilled(icx - 4f, icy - 3f, icx + 4f, icy - 3f, icx, icy - 8f, iconCol);
            dl.addLine(icx - 7f, icy + 3f, icx - 7f, icy + 6.5f, iconCol, 1.8f);
            dl.addLine(icx - 7f, icy + 6.5f, icx + 7f, icy + 6.5f, iconCol, 1.8f);
            dl.addLine(icx + 7f, icy + 3f, icx + 7f, icy + 6.5f, iconCol, 1.8f);
        }
        dl.addText(x + 42f, y + (h - ImGui.getFontSize()) * 0.5f, textCol, label);
        return clicked;
    }

    private void renderBottomPlaybackBar(float x, float y, float w, float h) {
        ImGui.setNextWindowPos(x, y, imgui.flag.ImGuiCond.Always);
        ImGui.setNextWindowSize(w, h, imgui.flag.ImGuiCond.Always);
        int flags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoDocking | ImGuiWindowFlags.NoScrollbar
                | ImGuiWindowFlags.NoSavedSettings;
        ImGui.pushStyleColor(ImGuiCol.WindowBg, 0.085f, 0.095f, 0.115f, 1.0f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 10f, 6f);
        ImGui.pushStyleVar(ImGuiStyleVar.WindowRounding, 0f);
        ImGui.begin("##BottomPlaybackBar", flags);

        ImDrawList dl = ImGui.getWindowDrawList();
        dl.addLine(x, y, x + w, y, ImGui.getColorU32(0.20f, 0.24f, 0.30f, 0.9f), 1.0f);

        float cy = y + h * 0.5f;
        int colIcon = ImGui.getColorU32(0.92f, 0.94f, 0.98f, 1.0f);
        int colMutedIcon = ImGui.getColorU32(0.95f, 0.42f, 0.42f, 1.0f);

        // Left: Prev | Play/Pause | Next | ... transport cluster
        float leftX = x + 10f;
        float leftGroupW = 168f;
        if (renderTransportVectorBtn(dl, "##BarPrev", leftX, cy - 17f, 36f, 34f, "Previous Track",
                (bx, by) -> {
                    dl.addRectFilled(bx - 7f, by - 6f, bx - 5f, by + 6f, colIcon);
                    dl.addTriangleFilled(bx - 4f, by, bx + 6f, by - 6f, bx + 6f, by + 6f, colIcon);
                })) {
            playPrevInQueue();
        }
        if (renderTransportVectorBtn(dl, "##BarPlayPause", leftX + 42f, cy - 17f, 38f, 34f,
                audioEngine.isAudioPlaying() ? "Pause" : "Play",
                (bx, by) -> {
                    if (audioEngine.isAudioPlaying()) {
                        dl.addRectFilled(bx - 6f, by - 7f, bx - 1.5f, by + 7f, colIcon, 1f);
                        dl.addRectFilled(bx + 1.5f, by - 7f, bx + 6f, by + 7f, colIcon, 1f);
                    } else {
                        dl.addTriangleFilled(bx - 5f, by - 8f, bx - 5f, by + 8f, bx + 8f, by, colIcon);
                    }
                })) {
            if (audioEngine.hasAudioLoaded()) {
                audioEngine.setPlaying(!audioEngine.isAudioPlaying());
            } else if (!playbackQueue.isEmpty()) {
                playQueue(playbackQueue, Math.max(0, playbackQueueIndex));
            } else {
                pickAndLoadAudio();
            }
        }
        if (renderTransportVectorBtn(dl, "##BarNext", leftX + 86f, cy - 17f, 36f, 34f, "Next Track",
                (bx, by) -> {
                    dl.addTriangleFilled(bx - 6f, by - 6f, bx - 6f, by + 6f, bx + 4f, by, colIcon);
                    dl.addRectFilled(bx + 5f, by - 6f, bx + 7f, by + 6f, colIcon);
                })) {
            playNextInQueue();
        }
        if (renderTransportVectorBtn(dl, "##BarMore", leftX + 128f, cy - 17f, 36f, 34f, "Playback Options",
                (bx, by) -> {
                    dl.addCircleFilled(bx - 6f, by, 2.0f, colIcon, 8);
                    dl.addCircleFilled(bx, by, 2.0f, colIcon, 8);
                    dl.addCircleFilled(bx + 6f, by, 2.0f, colIcon, 8);
                })) {
            ImGui.openPopup("##BarOptionsPopup");
        }
        if (ImGui.beginPopup("##BarOptionsPopup")) {
            if (ImGui.menuItem("Load Song File...")) {
                pickAndLoadAudio();
            }
            if (ImGui.menuItem("Loop Current Track", null, loopSingleTrack)) {
                loopSingleTrack = !loopSingleTrack;
            }
            ImGui.separator();
            if (ImGui.menuItem("Open Media Library")) {
                activePage = AppPage.LIBRARY;
            }
            if (ImGui.menuItem("Open Visualizer")) {
                activePage = AppPage.VISUALIZER;
            }
            ImGui.endPopup();
        }

        // Right: Speaker Mute button + Volume slider
        float volGroupW = 122f;
        float rightX = x + w - volGroupW - 12f;
        ImGui.setCursorScreenPos(rightX, cy - 16f);
        if (ImGui.invisibleButton("##BarMuteBtn", 30f, 32f)) {
            audioEngine.setMuted(!audioEngine.isMuted());
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip(audioEngine.isMuted() ? "Unmute" : "Mute");
        }
        float spkX = ImGui.getItemRectMinX() + 14f;
        float spkY = ImGui.getItemRectMinY() + 16f;
        int spkCol = audioEngine.isMuted() ? colMutedIcon : colIcon;
        dl.addRectFilled(spkX - 7f, spkY - 3f, spkX - 2f, spkY + 3f, spkCol);
        dl.addTriangleFilled(spkX - 3f, spkY - 3f, spkX + 4f, spkY - 7f, spkX + 4f, spkY + 7f, spkCol);
        if (audioEngine.isMuted()) {
            dl.addLine(spkX + 6f, spkY - 4f, spkX + 12f, spkY + 4f, spkCol, 2.0f);
            dl.addLine(spkX + 12f, spkY - 4f, spkX + 6f, spkY + 4f, spkCol, 2.0f);
        }

        ImGui.setCursorScreenPos(rightX + 34f, cy - 11f);
        volumeTmp[0] = audioEngine.getVolume() * 100.0f;
        ImGui.setNextItemWidth(84f);
        if (ImGui.sliderFloat("##BarVolume", volumeTmp, 0f, 100f, "%.0f%%")) {
            audioEngine.setVolume(volumeTmp[0] / 100.0f);
        }

        // Center: Progress Slider + Duration + Timestamp Jump + Track Title
        float centerX = leftX + leftGroupW + 14f;
        float centerRight = rightX - 16f;
        float centerW = Math.max(180f, centerRight - centerX);
        long durMs = audioEngine.getDurationMs();
        long posMs = audioEngine.getPositionMs();

        String posStr = formatTimestamp(posMs);
        String durStr = formatTimestamp(durMs);
        float row1Y = y + 9f;
        dl.addText(centerX, row1Y + 2f, ImGui.getColorU32(0.58f, 0.62f, 0.68f, 1.0f), posStr);

        float durTextW = ImGui.calcTextSize(durStr).x;
        float jumpBoxW = 66f;
        float seekX = centerX + ImGui.calcTextSize(posStr).x + 10f;
        float seekEnd = centerX + centerW - durTextW - jumpBoxW - 18f;
        float seekW = Math.max(60f, seekEnd - seekX);

        ImGui.setCursorScreenPos(seekX, row1Y);
        seekTmp[0] = posMs / 1000f;
        float maxSec = Math.max(1f, durMs / 1000f);
        ImGui.setNextItemWidth(seekW);
        if (ImGui.sliderFloat("##BarSeek", seekTmp, 0f, maxSec, "")) {
            audioEngine.seekMs((long) (seekTmp[0] * 1000f));
        }

        dl.addText(seekX + seekW + 8f, row1Y + 2f, ImGui.getColorU32(0.58f, 0.62f, 0.68f, 1.0f), durStr);

        if (!timestampEditing) {
            timestampEditBuf.set(posStr);
        }
        ImGui.setCursorScreenPos(centerX + centerW - jumpBoxW, row1Y);
        ImGui.setNextItemWidth(jumpBoxW);
        int tsFlags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll;
        boolean tsEntered = ImGui.inputText("##jumpTs", timestampEditBuf, tsFlags);
        if (ImGui.isItemActivated()) {
            timestampEditing = true;
        }
        if (ImGui.isItemHovered() && !ImGui.isItemActive()) {
            ImGui.setTooltip("Type timestamp (m:ss, h:mm:ss, or seconds) and press Enter/Tab");
        }
        if (timestampEditing) {
            boolean esc = ImGui.isKeyPressed(ImGuiKey.Escape, false);
            boolean tab = (ImGui.isItemActive() || ImGui.isItemFocused() || ImGui.isItemDeactivated())
                    && ImGui.isKeyPressed(ImGuiKey.Tab, false);
            boolean deactivated = ImGui.isItemDeactivated();
            if (esc) {
                timestampEditing = false;
                timestampEditBuf.set(posStr);
            } else if (tsEntered || tab || deactivated) {
                long targetMs = parseTimestampMs(timestampEditBuf.get(), durMs);
                if (targetMs >= 0L && audioEngine.hasAudioLoaded()) {
                    audioEngine.seekMs(targetMs);
                    statusMessage = "Seeked to " + formatTimestamp(audioEngine.getPositionMs());
                }
                timestampEditing = false;
            }
        }

        // Row 2 under slider: track title + loading / queue info
        float row2Y = y + 38f;
        String title = audioEngine.isLoading()
                ? ("Loading " + audioEngine.getTitle() + "...")
                : (audioEngine.hasAudioLoaded() ? audioEngine.getTitle() : "unknown");
        String queueNote = (!playbackQueue.isEmpty() && playbackQueueIndex >= 0)
                ? (title + "   •   " + (playbackQueueIndex + 1) + "/" + playbackQueue.size())
                : title;
        dl.pushClipRect(centerX, row2Y, centerRight, y + h - 4f, true);
        dl.addText(centerX, row2Y, ImGui.getColorU32(0.76f, 0.80f, 0.85f, 1.0f), queueNote);
        dl.popClipRect();

        ImGui.end();
        ImGui.popStyleVar(2);
        ImGui.popStyleColor();
    }

    private interface VectorGlyph {
        void draw(float cx, float cy);
    }

    private boolean renderTransportVectorBtn(ImDrawList dl, String uid, float x, float y, float w, float h,
                                             String tooltip, VectorGlyph glyph) {
        ImGui.setCursorScreenPos(x, y);
        boolean clicked = ImGui.invisibleButton(uid, w, h);
        if (ImGui.isItemHovered()) {
            dl.addRectFilled(x, y, x + w, y + h, ImGui.getColorU32(0.22f, 0.27f, 0.34f, 0.75f), 6f);
            ImGui.setTooltip(tooltip);
        }
        glyph.draw(x + w * 0.5f, y + h * 0.5f);
        return clicked;
    }

    private void trackAndSnapFloatingWindow(String windowId, float boundX, float boundY, float boundW, float boundH,
                                            boolean mouseReleased) {
        if (ImGui.isWindowDocked()) {
            floatingWindowRects.remove(windowId);
            movedFloatingWindows.remove(windowId);
            return;
        }
        float wx = ImGui.getWindowPosX();
        float wy = ImGui.getWindowPosY();
        float ww = ImGui.getWindowSizeX();
        float wh = ImGui.getWindowSizeY();
        float[] prev = floatingWindowRects.get(windowId);
        if (ImGui.isMouseDown(0) && prev != null
                && (Math.abs(wx - prev[0]) > 0.5f || Math.abs(wy - prev[1]) > 0.5f)) {
            movedFloatingWindows.add(windowId);
        }
        if (mouseReleased && movedFloatingWindows.remove(windowId)) {
            List<float[]> others = new ArrayList<>();
            for (java.util.Map.Entry<String, float[]> entry : floatingWindowRects.entrySet()) {
                if (!entry.getKey().equals(windowId)) {
                    others.add(entry.getValue());
                }
            }
            float[] snapped = snapFloatingWindowRect(wx, wy, ww, wh, boundX, boundY, boundW, boundH, others, 16.0f);
            if (Math.abs(snapped[0] - wx) > 0.5f || Math.abs(snapped[1] - wy) > 0.5f) {
                ImGui.setWindowPos(snapped[0], snapped[1]);
                wx = snapped[0];
                wy = snapped[1];
            }
        }
        floatingWindowRects.put(windowId, new float[]{wx, wy, ww, wh});
    }

    public static float[] snapFloatingWindowRect(float x, float y, float w, float h,
                                                 float boundX, float boundY, float boundW, float boundH,
                                                 List<float[]> otherRects, float thresholdPx) {
        float bestX = x;
        float bestDxAbs = thresholdPx + 1.0f;
        float[] edgeXs = {boundX, boundX + boundW - w};
        for (float targetX : edgeXs) {
            float d = Math.abs(x - targetX);
            if (d <= thresholdPx && d < bestDxAbs) {
                bestDxAbs = d;
                bestX = targetX;
            }
        }

        float bestY = y;
        float bestDyAbs = thresholdPx + 1.0f;
        float[] edgeYs = {boundY, boundY + boundH - h};
        for (float targetY : edgeYs) {
            float d = Math.abs(y - targetY);
            if (d <= thresholdPx && d < bestDyAbs) {
                bestDyAbs = d;
                bestY = targetY;
            }
        }

        if (otherRects != null) {
            for (float[] o : otherRects) {
                if (o == null || o.length < 4) continue;
                float ox = o[0], oy = o[1], ow = o[2], oh = o[3];
                if (y < oy + oh + thresholdPx && y + h > oy - thresholdPx) {
                    float[] candidatesX = {ox + ow, ox - w, ox, ox + ow - w};
                    for (float targetX : candidatesX) {
                        float d = Math.abs(x - targetX);
                        if (d <= thresholdPx && d < bestDxAbs) {
                            bestDxAbs = d;
                            bestX = targetX;
                        }
                    }
                }
                if (x < ox + ow + thresholdPx && x + w > ox - thresholdPx) {
                    float[] candidatesY = {oy + oh, oy - h, oy, oy + oh - h};
                    for (float targetY : candidatesY) {
                        float d = Math.abs(y - targetY);
                        if (d <= thresholdPx && d < bestDyAbs) {
                            bestDyAbs = d;
                            bestY = targetY;
                        }
                    }
                }
            }
        }

        float maxX = Math.max(boundX, boundX + boundW - Math.min(w, 80f));
        float maxY = Math.max(boundY, boundY + boundH - 32f);
        float finalX = Math.max(boundX, Math.min(maxX, bestX));
        float finalY = Math.max(boundY, Math.min(maxY, bestY));
        return new float[]{finalX, finalY};
    }

    private void syncPlaybackQueueAdvance() {
        if (audioEngine == null) return;
        long completed = audioEngine.getCompletedCount();
        if (completed != lastObservedCompletedCount) {
            lastObservedCompletedCount = completed;
            if (!loopSingleTrack && playbackQueue.size() > 1 && playbackQueueIndex >= 0) {
                playNextInQueue();
            }
        }
    }

    private void playQueue(List<File> tracks, int startIndex) {
        if (tracks == null || tracks.isEmpty()) return;
        playbackQueue.clear();
        for (File f : tracks) {
            if (f != null) playbackQueue.add(f);
        }
        if (playbackQueue.isEmpty()) {
            playbackQueueIndex = -1;
            return;
        }
        int idx = Math.max(0, Math.min(playbackQueue.size() - 1, startIndex));
        loadQueueTrackAt(idx);
    }

    private void enqueueTracks(List<File> tracks) {
        if (tracks == null || tracks.isEmpty()) return;
        int added = 0;
        for (File f : tracks) {
            if (f != null) {
                playbackQueue.add(f);
                added++;
            }
        }
        if (added > 0) {
            statusMessage = "Enqueued " + added + (added == 1 ? " track" : " tracks");
            if (!audioEngine.hasAudioLoaded()) {
                loadQueueTrackAt(0);
            }
        }
    }

    private void playNextInQueue() {
        if (playbackQueue.isEmpty()) {
            if (audioEngine.hasAudioLoaded()) {
                audioEngine.seekMs(0L);
            }
            return;
        }
        int next = (playbackQueueIndex + 1) % playbackQueue.size();
        loadQueueTrackAt(next);
    }

    private void playPrevInQueue() {
        if (playbackQueue.isEmpty() || audioEngine.getPositionMs() > 3000L) {
            if (audioEngine.hasAudioLoaded()) {
                audioEngine.seekMs(0L);
            }
            return;
        }
        int prev = (playbackQueueIndex - 1 + playbackQueue.size()) % playbackQueue.size();
        loadQueueTrackAt(prev);
    }

    private void loadQueueTrackAt(int idx) {
        if (idx < 0 || idx >= playbackQueue.size()) return;
        File f = playbackQueue.get(idx);
        playbackQueueIndex = idx;
        statusMessage = "Loading " + f.getName() + "...";
        audioEngine.loadAudioFileAsync(f, ok -> {
            if (Boolean.TRUE.equals(ok)) {
                lastObservedCompletedCount = audioEngine.getCompletedCount();
                statusMessage = "Playing: " + audioEngine.getTitle();
            } else {
                statusMessage = "Failed to load: " + f.getName();
            }
        });
    }

    private void renderTemplatePickerModal() {
        if (templateLibrary == null) return;
        templatePicker.render(templateLibrary, activeTemplateId, new TemplatePickerPanel.HostCallbacks() {
            @Override
            public void onSelectTemplate(String id) {
                loadTemplateFromLibrary(id);
            }

            @Override
            public void onImportTemplate() {
                File f = openDialog("Import Avee Template (.viz / .json)",
                        new String[]{"*.viz", "*.json", "*.txt"}, "Avee Templates (*.viz, *.json)");
                if (f != null) importIntoLibraryAndSelect(f);
            }

            @Override
            public void onExportTemplate(String id) {
                TemplateLibraryStore.TemplateEntry e = templateLibrary.getEntry(id);
                String defName = (e != null ? e.title.replaceAll("[^A-Za-z0-9._-]", "_") : "template") + ".viz";
                File out = saveDialog("Export Template as .viz", defName,
                        new String[]{"*.viz"}, "Avee Template Archive (*.viz)");
                if (out != null) {
                    try {
                        boolean isActive = id != null && id.equals(activeTemplateId);
                        templateLibrary.exportViz(id, ensureExt(out, ".viz"),
                                isActive ? currentScene : null, isActive ? rendererCore : null);
                        statusMessage = "Exported " + out.getName();
                    } catch (Exception ex) {
                        statusMessage = "Export .viz failed: " + ex.getMessage();
                    }
                }
            }

            @Override
            public void onDuplicateTemplate(String id) {
                try {
                    TemplateLibraryStore.TemplateEntry dup = templateLibrary.duplicateTemplate(id);
                    loadTemplateFromLibrary(dup.id);
                    statusMessage = "Duplicated as " + dup.title;
                } catch (Exception ex) {
                    statusMessage = "Duplicate failed: " + ex.getMessage();
                }
            }

            @Override
            public void onResetBuiltInTemplate(String id) {
                try {
                    inspector.getColorPicker().cancel();
                    currentScene = templateLibrary.resetBuiltInPreset(id);
                    activeTemplateId = id;
                    TemplateLibraryStore.TemplateEntry e = templateLibrary.getEntry(id);
                    activePresetIdx = e != null ? e.builtInIndex : -1;
                    rendererCore.setThemeElements(currentScene);
                    currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
                    selectedCompIdx = 0;
                    selectedElemIdx = (currentScene.getEntryCount() > 0 && currentScene.getEntry(0).getEntryCount() > 0) ? 0 : -1;
                    warmedPreviewIds.remove(id);
                    previewRefreshPending = true;
                    previewDirtyAtNs = System.nanoTime() - 1_000_000_000L;
                    statusMessage = "Reset Preset " + activePresetIdx;
                } catch (Exception ex) {
                    statusMessage = "Reset failed: " + ex.getMessage();
                }
            }

            @Override
            public void onDeleteTemplate(String id) {
                if (templateLibrary.deleteTemplate(id)) {
                    loadTemplateFromLibrary(templateLibrary.getLastSelectedId());
                    statusMessage = "Removed imported template";
                }
            }
        });
    }

    private void renderMainMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                if (ImGui.menuItem("Templates Gallery...")) {
                    templatePicker.open();
                }
                if (ImGui.menuItem("Import .viz / .json into Library...")) {
                    File f = openDialog("Import Avee Template", new String[]{"*.viz", "*.json", "*.txt"}, "Avee Templates (*.viz, *.json)");
                    if (f != null) importIntoLibraryAndSelect(f);
                }
                if (ImGui.beginMenu("Built-in Presets")) {
                    for (int i = 0; i <= 17; i++) {
                        if (ImGui.menuItem("Visualizer Preset " + i)) {
                            loadBuiltInPreset(i);
                        }
                    }
                    ImGui.endMenu();
                }
                ImGui.separator();
                if (ImGui.menuItem("Export Active Template (.viz)...")) {
                    TemplateLibraryStore.TemplateEntry curE = templateLibrary != null ? templateLibrary.getEntry(activeTemplateId) : null;
                    String defName = (curE != null ? curE.title.replaceAll("[^A-Za-z0-9._-]", "_") : "template") + ".viz";
                    File f = saveDialog("Save .viz Archive", defName, new String[]{"*.viz"}, "Avee Template Archive (*.viz)");
                    if (f != null) saveVizFile(ensureExt(f, ".viz"));
                }
                if (ImGui.menuItem("Save .viz As...", currentProjectFile != null || activeTemplateId != null)) {
                    File f = saveDialog("Save .viz Archive", "template.viz", new String[]{"*.viz"}, "Avee Template Archive (*.viz)");
                    if (f != null) saveVizFile(ensureExt(f, ".viz"));
                }
                if (ImGui.menuItem("Save scene.json As...")) {
                    File f = saveDialog("Save Scene JSON", "scene.json", new String[]{"*.json"}, "JSON (*.json)");
                    if (f != null) saveJsonFile(ensureExt(f, ".json"));
                }
                ImGui.separator();
                if (ImGui.menuItem("Export Video...")) {
                    openExportPage();
                }
                if (ImGui.menuItem("Export Current Frame PNG...")) {
                    File f = saveDialog("Save Preview Frame", "preview.png", new String[]{"*.png"}, "PNG Image (*.png)");
                    if (f != null) exportPreviewPng(ensureExt(f, ".png"));
                }
                ImGui.separator();
                if (ImGui.menuItem("Exit")) {
                    System.exit(0);
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("Audio")) {
                if (ImGui.menuItem("Load Audio File...")) {
                    pickAndLoadAudio();
                }
                if (ImGui.menuItem(audioEngine.isAudioPlaying() ? "Pause Audio" : "Play Audio", null, false, audioEngine.hasAudioLoaded())) {
                    audioEngine.setPlaying(!audioEngine.isAudioPlaying());
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("View")) {
                if (ImGui.menuItem("Library Page", null, activePage == AppPage.LIBRARY)) {
                    activePage = AppPage.LIBRARY;
                }
                if (ImGui.menuItem("Visualizer Page", null, activePage == AppPage.VISUALIZER)) {
                    activePage = AppPage.VISUALIZER;
                }
                if (ImGui.menuItem("Export Page", null, activePage == AppPage.EXPORT)) {
                    openExportPage();
                }
                ImGui.separator();
                ImGui.menuItem("Scene Hierarchy", null, showSceneHierarchy);
                ImGui.menuItem("Property Inspector", null, showPropertyInspector);
                if (ImGui.menuItem("Templates Gallery...")) {
                    templatePicker.open();
                }
                ImGui.separator();
                if (ImGui.menuItem("Reset Preview Zoom")) {
                    previewZoom = 1.0f;
                    previewPanX = 0.0f;
                    previewPanY = 0.0f;
                }
                if (ImGui.menuItem("Reset Panel Layout")) {
                    activePage = AppPage.VISUALIZER;
                    showSceneHierarchy.set(true);
                    showPropertyInspector.set(true);
                    requestResetDockLayout = true;
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("Export")) {
                if (ImGui.menuItem("Export Video (GPU Accelerated)...")) {
                    openExportPage();
                }
                if (ImGui.menuItem("Export Current Frame PNG...")) {
                    File f = saveDialog("Save Preview Frame", "preview.png", new String[]{"*.png"}, "PNG Image (*.png)");
                    if (f != null) exportPreviewPng(ensureExt(f, ".png"));
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("Tools")) {
                ImGui.text("MCP Server (LLM Integration)");
                mcpEnabledChk.set(mcpBridge.isRunning());
                if (ImGui.checkbox("Enable MCP Bridge", mcpEnabledChk)) {
                    toggleMcpBridge(mcpEnabledChk.get());
                }
                mcpAllowEditsChk.set(!mcpReadOnly);
                if (ImGui.checkbox("Allow MCP Edits (Read+Edit)", mcpAllowEditsChk)) {
                    mcpReadOnly = !mcpAllowEditsChk.get();
                }
                if (mcpBridge.isRunning()) {
                    ImGui.textDisabled("Bridge listening: 127.0.0.1:" + mcpBridge.getPort());
                } else {
                    ImGui.textDisabled("Bridge stopped (manual toggle)");
                }
                ImGui.separator();
                if (ImGui.menuItem("Copy MCP Client Config JSON")) {
                    ImGui.setClipboardText(buildMcpClientConfigJson());
                    statusMessage = "Copied MCP client config JSON";
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("Help")) {
                if (ImGui.beginMenu("Updates")) {
                    if (ImGui.menuItem("Check for Updates...")) {
                        updateDialogOpen = true;
                        updateManager.checkForUpdatesAsync(true);
                    }
                    ImGui.separator();
                    UpdateManager.Settings us = updateManager.getSettings();
                    autoCheckChk.set(us.autoCheckUpdates);
                    if (ImGui.checkbox("Auto-check on startup", autoCheckChk)) {
                        us.autoCheckUpdates = autoCheckChk.get();
                        updateManager.saveSettings();
                    }
                    autoDownloadChk.set(us.autoDownloadUpdates);
                    if (ImGui.checkbox("Auto-download updates", autoDownloadChk)) {
                        us.autoDownloadUpdates = autoDownloadChk.get();
                        updateManager.saveSettings();
                    }
                    ImGui.separator();
                    if (ImGui.menuItem("Open Updates Folder")) {
                        UpdateManager.openFolder(updateManager.getUpdatesDir());
                    }
                    ImGui.endMenu();
                }
                if (ImGui.menuItem("About AveeCraft...")) {
                    aboutOpen = true;
                }
                ImGui.endMenu();
            }

            UpdateManager.State uState = updateManager.getState();
            if ((uState == UpdateManager.State.AVAILABLE || uState == UpdateManager.State.READY)
                    && updateManager.getLatestRelease() != null) {
                ImGui.sameLine();
                String badge = (uState == UpdateManager.State.READY)
                        ? ("Update Ready: " + updateManager.getLatestRelease().tagName)
                        : ("Update Available: " + updateManager.getLatestRelease().tagName);
                ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.45f, 0.24f, 1.0f);
                if (ImGui.smallButton(badge)) {
                    updateDialogOpen = true;
                }
                ImGui.popStyleColor();
            }

            ImGui.sameLine(ImGui.getWindowWidth() - 420f);
            ImGui.textDisabled(statusMessage);
            ImGui.endMainMenuBar();
        }
    }

    private void renderSceneTreePanel() {
        ImGui.text("Scene Hierarchy");
        ImGui.separator();

        if (ImGui.button("+ Element")) {
            ImGui.openPopup("AddElementPopup");
        }
        ImGui.sameLine();
        if (ImGui.button("+ Comp")) {
            addComposition();
        }
        ImGui.sameLine();
        if (ImGui.button("Reset GL")) {
            rendererCore.setThemeElements(currentScene);
            statusMessage = "Reloaded GL resources";
        }

        if (ImGui.beginPopup("AddElementPopup")) {
            for (String type : ElementsFactory.getAddableTypeNames()) {
                if ("Composition".equals(type)) continue;
                if (ImGui.menuItem(type)) {
                    addElementToSelectedComp(type);
                }
            }
            ImGui.endPopup();
        }

        // Action row for selected element or offscreen composition
        boolean canEditElem = currentScene != null && selectedElemIdx >= 0;
        boolean canMoveComp = currentScene != null && selectedElemIdx == -1 && selectedCompIdx > 0;
        if (ImGui.button("Up") && (canEditElem || canMoveComp)) {
            if (canEditElem) {
                moveSelectedElement(-1);
            } else {
                moveSelectedComposition(-1);
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Down") && (canEditElem || canMoveComp)) {
            if (canEditElem) {
                moveSelectedElement(1);
            } else {
                moveSelectedComposition(1);
            }
        }
        ImGui.sameLine();
        if (ImGui.button("Duplicate") && canEditElem) {
            duplicateSelectedElement();
        }
        ImGui.sameLine();
        if (ImGui.button("Delete") && currentScene != null) {
            deleteSelected();
        }

        ImGui.separator();
        if (currentScene == null) {
            ImGui.textDisabled("No template loaded");
            return;
        }

        ImGui.beginChild("##TreeScroll", 0f, 0f, false);
        int[] pendingDragMove = null;
        int[] pendingCompMove = null;
        float rowMinX = ImGui.getWindowPosX() + 10.0f;
        float rowMaxX = ImGui.getWindowPosX() + ImGui.getWindowWidth() - 10.0f;
        int compCount = currentScene.getEntryCount();
        for (int cIdx = 0; cIdx < compCount; cIdx++) {
            CustomComposition comp = currentScene.getEntry(cIdx);
            if (comp == null) continue;
            boolean compSelected = (selectedCompIdx == cIdx && selectedElemIdx == -1);
            int flags = ImGuiTreeNodeFlags.DefaultOpen | ImGuiTreeNodeFlags.OpenOnArrow
                    | ImGuiTreeNodeFlags.SpanAvailWidth
                    | (compSelected ? ImGuiTreeNodeFlags.Selected : 0);

            String compLabel = "Composition " + cIdx + (cIdx == 0 ? " (Final)" : "");
            boolean open = ImGui.treeNodeEx(compLabel + "##comp_" + cIdx, flags);
            float compMinY = ImGui.getItemRectMinY();
            float compMaxY = ImGui.getItemRectMaxY();
            if (ImGui.isItemClicked()) {
                selectedCompIdx = cIdx;
                selectedElemIdx = -1;
                if (rendererCore != null) {
                    rendererCore.triggerHighlightByIndices(cIdx, -1);
                }
            }
            if (cIdx > 0 && ImGui.beginDragDropSource()) {
                activeCompDragPayload = cIdx;
                activeDragPayload = null;
                ImGui.setDragDropPayload("AVEE_COMP", Integer.valueOf(cIdx));
                ImGui.text("Move Composition " + cIdx + " (" + comp.getEntryCount() + " layers)");
                ImGui.endDragDropSource();
            }
            if (ImGui.beginDragDropTarget()) {
                if (activeCompDragPayload > 0) {
                    boolean topHalf = cIdx > 0 && ImGui.getMousePosY() <= (compMinY + compMaxY) * 0.5f;
                    int insertSlot = (cIdx == 0) ? 1 : (topHalf ? cIdx : (cIdx + 1));
                    int resolvedComp = resolveCompositionDropIndex(activeCompDragPayload, insertSlot, compCount);
                    Integer srcComp = ImGui.acceptDragDropPayload("AVEE_COMP", ImGuiDragDropFlags.AcceptNoDrawDefaultRect, Integer.class);
                    if (resolvedComp > 0) {
                        float lineY = (cIdx > 0 && topHalf) ? (compMinY - 1.5f) : (compMaxY + 1.5f);
                        drawDropInsertionLine(rowMinX, rowMaxX, lineY);
                        if (srcComp != null) {
                            pendingCompMove = new int[]{srcComp.intValue(), resolvedComp};
                        }
                    }
                }
                if (activeDragPayload != null) {
                    int[] src = ImGui.acceptDragDropPayload("AVEE_LAYER", ImGuiDragDropFlags.AcceptNoDrawDefaultRect, int[].class);
                    int resolvedEnd = resolveLayerDropIndex(activeDragPayload[0], activeDragPayload[1], cIdx, comp.getEntryCount());
                    if (resolvedEnd >= 0) {
                        drawDropInsertionLine(rowMinX, rowMaxX, compMaxY + 1.5f);
                        if (src != null && src.length == 2) {
                            pendingDragMove = new int[]{src[0], src[1], cIdx, resolvedEnd};
                        }
                    }
                }
                ImGui.endDragDropTarget();
            }
            if (open) {
                int elemCount = comp.getEntryCount();
                if (elemCount == 0) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.55f, 0.55f, 0.55f, 1.0f);
                    ImGui.selectable("  (empty - drop layer here)##empty_" + cIdx, false);
                    float emptyMinY = ImGui.getItemRectMinY();
                    ImGui.popStyleColor();
                    if (ImGui.beginDragDropTarget()) {
                        if (activeDragPayload != null) {
                            int[] src = ImGui.acceptDragDropPayload("AVEE_LAYER", ImGuiDragDropFlags.AcceptNoDrawDefaultRect, int[].class);
                            drawDropInsertionLine(rowMinX, rowMaxX, emptyMinY - 1.5f);
                            if (src != null && src.length == 2) {
                                pendingDragMove = new int[]{src[0], src[1], cIdx, 0};
                            }
                        }
                        ImGui.endDragDropTarget();
                    }
                }
                for (int eIdx = 0; eIdx < elemCount; eIdx++) {
                    CustomPropertiesList elem = comp.getData(eIdx);
                    if (elem == null) continue;

                    ImGui.pushID(cIdx * 10000 + eIdx);
                    boolean vis = elem.getPropertyBool("visible", true);
                    ImBoolean visBox = new ImBoolean(vis);
                    if (ImGui.checkbox("##vis", visBox)) {
                        elem.putPropertyBoolValue("visible", visBox.get());
                        applySceneChanges(false);
                    }
                    ImGui.sameLine();

                    String typeName = elem.getTypeName("Unknown");
                    String tag = elem.getTagName();
                    String label = typeName + ((tag != null && !tag.isEmpty()) ? (" [" + tag + "]") : "");
                    boolean isSel = (selectedCompIdx == cIdx && selectedElemIdx == eIdx);
                    if (ImGui.selectable(label + "##el_" + eIdx, isSel)) {
                        selectedCompIdx = cIdx;
                        selectedElemIdx = eIdx;
                        if (rendererCore != null) {
                            rendererCore.triggerHighlightByIndices(cIdx, eIdx);
                        }
                    }
                    float rowMinY = ImGui.getItemRectMinY();
                    float rowMaxY = ImGui.getItemRectMaxY();
                    if (ImGui.beginDragDropSource()) {
                        activeDragPayload = new int[]{cIdx, eIdx};
                        activeCompDragPayload = -1;
                        ImGui.setDragDropPayload("AVEE_LAYER", activeDragPayload);
                        ImGui.text("Move " + label + " (drop between layers or on a Composition)");
                        ImGui.endDragDropSource();
                    }
                    if (ImGui.beginDragDropTarget()) {
                        if (activeDragPayload != null) {
                            boolean topHalf = ImGui.getMousePosY() <= (rowMinY + rowMaxY) * 0.5f;
                            int insertSlot = topHalf ? eIdx : (eIdx + 1);
                            int resolved = resolveLayerDropIndex(activeDragPayload[0], activeDragPayload[1], cIdx, insertSlot);
                            int[] src = ImGui.acceptDragDropPayload("AVEE_LAYER", ImGuiDragDropFlags.AcceptNoDrawDefaultRect, int[].class);
                            if (resolved >= 0) {
                                float lineY = topHalf ? (rowMinY - 1.5f) : (rowMaxY + 1.5f);
                                drawDropInsertionLine(rowMinX, rowMaxX, lineY);
                                if (src != null && src.length == 2) {
                                    pendingDragMove = new int[]{src[0], src[1], cIdx, resolved};
                                }
                            }
                        }
                        ImGui.endDragDropTarget();
                    }
                    ImGui.popID();
                }
                ImGui.treePop();
            }
        }
        ImGui.endChild();
        if (pendingCompMove != null) {
            moveComposition(pendingCompMove[0], pendingCompMove[1]);
            activeCompDragPayload = -1;
            activeDragPayload = null;
        } else if (pendingDragMove != null) {
            moveElement(pendingDragMove[0], pendingDragMove[1], pendingDragMove[2], pendingDragMove[3]);
            activeDragPayload = null;
            activeCompDragPayload = -1;
        } else if (!ImGui.isMouseDown(0)) {
            activeDragPayload = null;
            activeCompDragPayload = -1;
        }
    }

    private void renderPreviewViewportPanel() {
        // Top preview bar: [Templates...] [Loaded Template Name]              [FPS: N]
        String fpsText = "FPS: " + rendererCore.getFrameStats().fps;
        float fpsW = ImGui.calcTextSize(fpsText).x;
        float winMaxX = ImGui.getWindowPosX() + ImGui.getWindowContentRegionMaxX();
        float fpsScreenX = Math.max(ImGui.getCursorScreenPosX() + 140f, winMaxX - fpsW - 4f);

        if (ImGui.button("Templates...")) {
            templatePicker.open();
        }
        ImGui.sameLine(0.0f, 10.0f);
        float nameStartX = ImGui.getCursorScreenPosX();
        float nameY = ImGui.getCursorScreenPosY();
        float nameH = ImGui.getFrameHeight();
        String tplName = getActiveTemplateDisplayName();
        ImDrawList topDl = ImGui.getWindowDrawList();
        topDl.pushClipRect(nameStartX, nameY, Math.max(nameStartX + 20f, fpsScreenX - 10f), nameY + nameH, true);
        ImGui.alignTextToFramePadding();
        ImGui.textUnformatted(tplName);
        topDl.popClipRect();

        ImGui.sameLine(Math.max(120f, ImGui.getWindowContentRegionMaxX() - fpsW - 4f));
        ImGui.alignTextToFramePadding();
        ImGui.textDisabled(fpsText);

        float availW = Math.max(64f, ImGui.getContentRegionAvailX());
        PreviewBottomLayout bl = computePreviewBottomBarLayout(availW);
        float availH = Math.max(64f, ImGui.getContentRegionAvailY() - bl.totalBarHeight - 6.0f);

        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0f, 0f);
        ImGui.beginChild("##PreviewViewportCanvas", availW, availH, true,
                ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoMove);
        float canvasX0 = ImGui.getCursorScreenPosX();
        float canvasY0 = ImGui.getCursorScreenPosY();
        float cW = Math.max(64f, ImGui.getContentRegionAvailX());
        float cH = Math.max(64f, ImGui.getContentRegionAvailY());

        if (exportDialog.isExporting() || previewTex == 0) {
            ImDrawList dl = ImGui.getWindowDrawList();
            dl.addRectFilled(canvasX0, canvasY0, canvasX0 + cW, canvasY0 + cH, ImGui.getColorU32(0.05f, 0.06f, 0.08f, 1.0f));
            String msg = "Preview unloaded during export for maximum performance";
            float mw = ImGui.calcTextSize(msg).x;
            dl.addText(canvasX0 + Math.max(12f, (cW - mw) * 0.5f), canvasY0 + cH * 0.44f,
                    ImGui.getColorU32(0.70f, 0.76f, 0.84f, 1.0f), msg);
            float openBtnW = 148f;
            ImGui.setCursorScreenPos(canvasX0 + Math.max(12f, (cW - openBtnW) * 0.5f), canvasY0 + cH * 0.44f + 28f);
            if (ImGui.button("Open Export Page", openBtnW, 28f)) {
                openExportPage();
            }
        } else {
            // Exclusive hit item prevents parent window from dragging when panning inside preview
            ImGui.invisibleButton("##PreviewCanvasHit", cW, cH);
            float[] fitRect = computeZoomedPreviewRect(cW, cH, previewW, previewH, previewZoom, previewPanX, previewPanY);
            float drawW = fitRect[2];
            float drawH = fitRect[3];

            if (!inspector.getColorPicker().isActive()) {
                if (ImGui.isItemHovered()) {
                    float wheel = ImGui.getIO().getMouseWheel();
                    if (Math.abs(wheel) > 0.01f) {
                        float factor = wheel > 0.0f ? 1.15f : (1.0f / 1.15f);
                        previewZoom = clampPreviewZoom(previewZoom * factor);
                        fitRect = computeZoomedPreviewRect(cW, cH, previewW, previewH, previewZoom, previewPanX, previewPanY);
                        drawW = fitRect[2];
                        drawH = fitRect[3];
                        previewPanX = clampPreviewPan(previewPanX, cW, drawW);
                        previewPanY = clampPreviewPan(previewPanY, cH, drawH);
                    }
                    if (ImGui.isMouseDoubleClicked(0)) {
                        previewZoom = 1.0f;
                        previewPanX = 0.0f;
                        previewPanY = 0.0f;
                    }
                }
                if ((ImGui.isItemActive() && ImGui.isMouseDragging(0, 1.0f))
                        || (ImGui.isItemHovered() && ImGui.isMouseDragging(2, 1.0f))) {
                    previewPanX = clampPreviewPan(previewPanX + ImGui.getIO().getMouseDeltaX(), cW, drawW);
                    previewPanY = clampPreviewPan(previewPanY + ImGui.getIO().getMouseDeltaY(), cH, drawH);
                }
            }

            fitRect = computeZoomedPreviewRect(cW, cH, previewW, previewH, previewZoom, previewPanX, previewPanY);
            ImDrawList dl = ImGui.getWindowDrawList();
            dl.pushClipRect(canvasX0, canvasY0, canvasX0 + cW, canvasY0 + cH, true);
            dl.addRectFilled(canvasX0, canvasY0, canvasX0 + cW, canvasY0 + cH, ImGui.getColorU32(0.05f, 0.06f, 0.08f, 1.0f));
            // Flip V (0 -> 1 bottom-to-top) for OpenGL FBO texture orientation in ImGui
            dl.addImage(previewTex,
                    canvasX0 + fitRect[0], canvasY0 + fitRect[1],
                    canvasX0 + fitRect[0] + fitRect[2], canvasY0 + fitRect[1] + fitRect[3],
                    0f, 1f, 1f, 0f);
            dl.popClipRect();
        }
        ImGui.endChild();
        ImGui.popStyleVar();

        // Bottom preview controls bar: Resolution + HQ Blur + Zoom, auto-resized to preview width
        String[] resLabels = bl.compactResLabels ? RESOLUTION_LABELS_COMPACT : RESOLUTION_LABELS;
        ImGui.setNextItemWidth(bl.resComboW);
        if (ImGui.combo("##res", resCombo, resLabels)) {
            int[] res = RESOLUTIONS[resCombo.get()];
            previewW = res[0];
            previewH = res[1];
        }
        if (!bl.twoRows) {
            ImGui.sameLine(0.0f, 8.0f);
        }
        hqBlurChk.set(audioEngine.isHighQualityBlur());
        if (ImGui.checkbox("HQ Blur", hqBlurChk)) {
            audioEngine.setHighQualityBlur(hqBlurChk.get());
        }

        String zoomLabel = Math.round(previewZoom * 100.0f) + "%##zoomReset";
        float zoomClusterW = 22f + Math.max(40f, ImGui.calcTextSize("800%").x + 12f) + 22f + 12f;
        float hqRightLocalX = ImGui.getItemRectMaxX() - ImGui.getWindowPosX();
        float rightZoomX = Math.max(hqRightLocalX + 8f, ImGui.getWindowContentRegionMaxX() - zoomClusterW);
        ImGui.sameLine(rightZoomX);
        if (ImGui.smallButton("-##zoomOut")) {
            previewZoom = clampPreviewZoom(previewZoom / 1.25f);
            if (previewZoom <= 1.0f) {
                previewPanX = 0.0f;
                previewPanY = 0.0f;
            }
        }
        ImGui.sameLine(0.0f, 4.0f);
        if (ImGui.smallButton(zoomLabel)) {
            previewZoom = 1.0f;
            previewPanX = 0.0f;
            previewPanY = 0.0f;
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Mouse wheel over preview to zoom, drag inside canvas to pan, double-click/click % to reset");
        }
        ImGui.sameLine(0.0f, 4.0f);
        if (ImGui.smallButton("+##zoomIn")) {
            previewZoom = clampPreviewZoom(previewZoom * 1.25f);
        }
    }

    public static float clampPreviewZoom(float z) {
        return Math.max(0.25f, Math.min(8.0f, z));
    }

    public static float clampPreviewPan(float pan, float canvasDim, float drawDim) {
        float maxPan = Math.max(canvasDim * 0.45f, drawDim * 0.45f);
        return Math.max(-maxPan, Math.min(maxPan, pan));
    }

    public static float[] computeZoomedPreviewRect(float canvasW, float canvasH,
                                                   int previewW, int previewH,
                                                   float zoom, float panX, float panY) {
        float aspect = (float) Math.max(1, previewW) / (float) Math.max(1, previewH);
        float fitW = canvasW;
        float fitH = fitW / aspect;
        if (fitH > canvasH) {
            fitH = canvasH;
            fitW = fitH * aspect;
        }
        float z = clampPreviewZoom(zoom);
        float drawW = fitW * z;
        float drawH = fitH * z;
        float offX = ((canvasW - drawW) * 0.5f) + panX;
        float offY = ((canvasH - drawH) * 0.5f) + panY;
        return new float[]{offX, offY, drawW, drawH};
    }

    public static String formatTimestamp(long ms) {
        long clamped = Math.max(0L, ms);
        long totalSec = clamped / 1000L;
        long h = totalSec / 3600L;
        long m = (totalSec % 3600L) / 60L;
        long s = totalSec % 60L;
        if (h > 0L) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.US, "%d:%02d", m, s);
    }

    public static long parseTimestampMs(String text, long durationMs) {
        if (text == null) return -1L;
        String t = text.trim();
        if (t.isEmpty()) return -1L;
        if (t.endsWith("s") || t.endsWith("S")) {
            t = t.substring(0, t.length() - 1).trim();
        }
        try {
            double totalSec;
            if (t.contains(":")) {
                String[] parts = t.split(":");
                if (parts.length == 2) {
                    long mins = Long.parseLong(parts[0].trim());
                    double secs = Double.parseDouble(parts[1].trim());
                    if (mins < 0 || secs < 0.0 || Double.isNaN(secs) || Double.isInfinite(secs)) return -1L;
                    totalSec = mins * 60.0 + secs;
                } else if (parts.length == 3) {
                    long hrs = Long.parseLong(parts[0].trim());
                    long mins = Long.parseLong(parts[1].trim());
                    double secs = Double.parseDouble(parts[2].trim());
                    if (hrs < 0 || mins < 0 || secs < 0.0 || Double.isNaN(secs) || Double.isInfinite(secs)) return -1L;
                    totalSec = hrs * 3600.0 + mins * 60.0 + secs;
                } else {
                    return -1L;
                }
            } else {
                totalSec = Double.parseDouble(t);
                if (totalSec < 0.0 || Double.isNaN(totalSec) || Double.isInfinite(totalSec)) return -1L;
            }
            long ms = Math.round(totalSec * 1000.0);
            if (durationMs > 0L) {
                ms = Math.max(0L, Math.min(durationMs, ms));
            }
            return Math.max(0L, ms);
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    private void renderInspectorPanel() {
        ImGui.text("Property Inspector");
        ImGui.separator();
        if (currentScene == null) return;

        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        if (comp == null) {
            ImGui.textDisabled("No selection");
            return;
        }
        if (selectedElemIdx >= 0) {
            CustomPropertiesList elem = comp.getData(selectedElemIdx);
            String title = elem != null ? (elem.getTypeName("Element") + " (#" + selectedElemIdx + ")") : "Element";
            if (inspector.render(title, elem, currentScene)) {
                applySceneChanges(true);
            }
        } else {
            if (inspector.render("Composition " + selectedCompIdx, comp, currentScene)) {
                applySceneChanges(true);
            }
        }
    }

    private void loadBuiltInPreset(int idx) {
        if (templateLibrary != null) {
            loadTemplateFromLibrary(TemplateLibraryStore.presetId(idx));
            return;
        }
        inspector.getColorPicker().cancel();
        try {
            currentScene = VizArchiveManager.loadPreset(idx);
            currentProjectFile = null;
            activePresetIdx = idx;
            selectedCompIdx = 0;
            selectedElemIdx = currentScene.getEntryCount() > 0 && currentScene.getEntry(0).getEntryCount() > 0 ? 0 : -1;
            rendererCore.setThemeElements(currentScene);
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
            statusMessage = "Loaded Preset " + idx;
        } catch (Exception e) {
            statusMessage = "Error: " + e.getMessage();
            e.printStackTrace();
        }
    }

    private void loadTemplateFile(File f) {
        if (templateLibrary != null) {
            importIntoLibraryAndSelect(f);
            return;
        }
        inspector.getColorPicker().cancel();
        try {
            currentScene = VizArchiveManager.loadFile(f);
            currentProjectFile = f;
            activePresetIdx = -1;
            selectedCompIdx = 0;
            selectedElemIdx = (currentScene.getEntryCount() > 0 && currentScene.getEntry(0).getEntryCount() > 0) ? 0 : -1;
            rendererCore.setThemeElements(currentScene);
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
            statusMessage = "Loaded " + f.getName();
        } catch (Exception e) {
            statusMessage = "Load failed: " + e.getMessage();
            e.printStackTrace();
        }
    }

    private void loadTemplateFromLibrary(String id) {
        inspector.getColorPicker().cancel();
        try {
            if (templateLibrary != null) {
                templateLibrary.flushPendingSavesSync(1000L);
                TemplateLibraryStore.TemplateEntry e = templateLibrary.getEntry(id);
                currentScene = templateLibrary.loadTemplate(id);
                activeTemplateId = id;
                activePresetIdx = (e != null) ? e.builtInIndex : -1;
                currentProjectFile = null;
                selectedCompIdx = 0;
                selectedElemIdx = (currentScene.getEntryCount() > 0 && currentScene.getEntry(0).getEntryCount() > 0) ? 0 : -1;
                rendererCore.setThemeElements(currentScene);
                currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
                warmedPreviewIds.remove(id);
                previewRefreshPending = true;
                previewDirtyAtNs = System.nanoTime();
                statusMessage = "Loaded " + (e != null ? e.title : id);
            }
        } catch (Exception ex) {
            statusMessage = "Template load failed: " + ex.getMessage();
            ex.printStackTrace();
        }
    }

    private void importIntoLibraryAndSelect(File f) {
        inspector.getColorPicker().cancel();
        try {
            if (templateLibrary != null) {
                templateLibrary.flushPendingSavesSync(1000L);
                TemplateLibraryStore.TemplateEntry imported = templateLibrary.importTemplateFile(f, null);
                loadTemplateFromLibrary(imported.id);
                statusMessage = "Imported " + imported.title + " into library";
            }
        } catch (Exception ex) {
            statusMessage = "Import failed: " + ex.getMessage();
            ex.printStackTrace();
        }
    }

    private void triggerAutoSave() {
        if (templateLibrary == null || activeTemplateId == null || currentScene == null) return;
        templateLibrary.scheduleAutoSave(activeTemplateId, currentScene,
                com.daaw.avee.comp.AppPreferences.AppPreferences.getActiveWorkspaceFolder());
        previewDirtyAtNs = System.nanoTime();
        previewRefreshPending = true;
    }

    private void saveVizFile(File outFile) {
        try {
            VizArchiveManager.saveViz(outFile, currentScene, rendererCore);
            currentProjectFile = outFile;
            statusMessage = "Saved " + outFile.getName();
        } catch (Exception e) {
            statusMessage = "Save failed: " + e.getMessage();
            e.printStackTrace();
        }
    }

    private void saveJsonFile(File outFile) {
        try {
            VizArchiveManager.saveJson(outFile, currentScene, rendererCore);
            statusMessage = "Saved " + outFile.getName();
        } catch (Exception e) {
            statusMessage = "Save failed: " + e.getMessage();
            e.printStackTrace();
        }
    }

    private void exportPreviewPng(File outFile) {
        try {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
            Bitmap bmp = GraphicsUtils.savePixels(0, 0, previewW, previewH, null);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
            try (FileOutputStream fos = new FileOutputStream(outFile)) {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }
            statusMessage = "Saved frame " + outFile.getName();
        } catch (Exception e) {
            statusMessage = "Frame export failed: " + e.getMessage();
        }
    }

    private void applySceneChanges(boolean refreshSchema) {
        if (currentScene == null || rendererCore == null) return;
        rendererCore.setThemeCustomizationData(currentScene);
        if (refreshSchema) {
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        }
        triggerAutoSave();
    }

    private void addComposition() {
        if (currentScene == null) return;
        CustomComposition newComp = currentScene.createDataEntry();
        newComp.putChildTypeValue("Composition");
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        selectedCompIdx = Math.max(0, currentScene.getEntryCount() - 1);
        selectedElemIdx = -1;
        triggerAutoSave();
        statusMessage = "Added Composition " + selectedCompIdx;
    }

    private void addElementToSelectedComp(String typeName) {
        if (currentScene == null) return;
        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        if (comp == null) return;
        if (ElementsFactory.create(typeName, "", null) == null) return;
        CustomPropertiesList newEntry = comp.createDataEntry();
        newEntry.putChildTypeValue(typeName);
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        CustomComposition refreshed = currentScene.getEntry(selectedCompIdx);
        if (refreshed != null && refreshed.getEntryCount() > 0) {
            int lastIdx = refreshed.getEntryCount() - 1;
            if (selectedCompIdx == 0 && !"AppLogo".equals(typeName) && lastIdx >= 1
                    && "AppLogo".equals(refreshed.getData(lastIdx).getTypeName(""))) {
                selectedElemIdx = lastIdx - 1;
            } else {
                selectedElemIdx = lastIdx;
            }
        } else {
            selectedElemIdx = -1;
        }
        rendererCore.triggerHighlightByIndices(selectedCompIdx, selectedElemIdx);
        triggerAutoSave();
        statusMessage = "Added " + typeName;
    }

    private void duplicateSelectedElement() {
        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        if (comp == null || selectedElemIdx < 0) return;
        CustomPropertiesList src = comp.getData(selectedElemIdx);
        if (src == null) return;
        comp.createDataEntryClonedFrom(src);
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        selectedElemIdx = currentScene.getEntry(selectedCompIdx).getEntryCount() - 1;
        triggerAutoSave();
        statusMessage = "Duplicated element";
    }

    public static int[] computeStepMoveTarget(CustomScene scene, int compIdx, int elemIdx, int delta) {
        if (scene == null || delta == 0 || compIdx < 0 || compIdx >= scene.getEntryCount()) {
            return null;
        }
        CustomComposition srcComp = scene.getEntry(compIdx);
        if (srcComp == null) {
            return null;
        }
        int srcN = srcComp.getEntryCount();
        if (elemIdx < 0 || elemIdx >= srcN) {
            return null;
        }
        if (delta < 0) {
            if (elemIdx > 0) {
                return new int[]{compIdx, elemIdx - 1};
            }
            if (compIdx > 0) {
                CustomComposition above = scene.getEntry(compIdx - 1);
                return above != null ? new int[]{compIdx - 1, above.getEntryCount()} : null;
            }
            return null;
        }
        if (elemIdx < srcN - 1) {
            return new int[]{compIdx, elemIdx + 1};
        }
        if (compIdx < scene.getEntryCount() - 1) {
            return new int[]{compIdx + 1, 0};
        }
        return null;
    }

    public static int[] moveElementInScene(CustomScene scene, int srcCompIdx, int srcElemIdx, int dstCompIdx, int dstElemIdx) {
        if (scene == null) return null;
        CustomComposition srcComp = scene.getEntry(srcCompIdx);
        CustomComposition dstComp = scene.getEntry(dstCompIdx);
        if (srcComp == null || dstComp == null) return null;
        int srcN = srcComp.getEntryCount();
        if (srcElemIdx < 0 || srcElemIdx >= srcN) return null;

        if (srcCompIdx == dstCompIdx) {
            int clampedDst = Math.max(0, Math.min(srcN - 1, dstElemIdx));
            if (clampedDst == srcElemIdx) return null;
            List<CustomPropertiesList> items = new ArrayList<>(srcN);
            for (int i = 0; i < srcN; i++) items.add(srcComp.getData(i));
            CustomPropertiesList moving = items.remove(srcElemIdx);
            items.add(clampedDst, moving);
            for (int i = 0; i < srcN; i++) srcComp.setEntry(i, items.get(i));
            return new int[]{dstCompIdx, clampedDst};
        }
        CustomPropertiesList moving = srcComp.getData(srcElemIdx);
        if (moving == null) return null;
        int dstN = dstComp.getEntryCount();
        int insertIdx = Math.max(0, Math.min(dstN, dstElemIdx));
        List<CustomPropertiesList> dstItems = new ArrayList<>(dstN + 1);
        for (int i = 0; i < dstN; i++) dstItems.add(dstComp.getData(i));
        CustomPropertiesList cloned = dstComp.createDataEntryClonedFrom(moving);
        dstItems.add(insertIdx, cloned);
        for (int i = 0; i < dstItems.size(); i++) dstComp.setEntry(i, dstItems.get(i));
        srcComp.removeEntry(srcElemIdx);
        return new int[]{dstCompIdx, insertIdx};
    }

    public static int deleteCompositionMergingLayers(CustomScene scene, int deletedIdx) {
        if (scene == null || deletedIdx <= 0 || deletedIdx >= scene.getEntryCount()) {
            return -1;
        }
        CustomComposition above = scene.getEntry(deletedIdx - 1);
        CustomComposition victim = scene.getEntry(deletedIdx);
        int moved = 0;
        if (above != null && victim != null) {
            for (int i = 0; i < victim.getEntryCount(); i++) {
                CustomPropertiesList layer = victim.getData(i);
                if (layer != null) {
                    above.createDataEntryClonedFrom(layer);
                    moved++;
                }
            }
        }
        scene.removeEntry(deletedIdx);
        remapCompositionRefsAfterDelete(scene, deletedIdx);
        return moved;
    }

    private static void drawDropInsertionLine(float minX, float maxX, float y) {
        if (maxX <= minX) return;
        ImDrawList dl = ImGui.getWindowDrawList();
        int col = ImGui.getColorU32(0.30f, 0.78f, 1.0f, 0.98f);
        dl.addLine(minX + 3.0f, y, maxX - 3.0f, y, col, 2.5f);
        dl.addCircleFilled(minX + 3.0f, y, 3.0f, col);
        dl.addCircleFilled(maxX - 3.0f, y, 3.0f, col);
    }

    public static int resolveLayerDropIndex(int srcCompIdx, int srcElemIdx, int dstCompIdx, int insertSlot) {
        if (srcCompIdx == dstCompIdx) {
            if (insertSlot == srcElemIdx || insertSlot == srcElemIdx + 1) {
                return -1;
            }
            return insertSlot > srcElemIdx ? (insertSlot - 1) : insertSlot;
        }
        return Math.max(0, insertSlot);
    }

    public static int resolveCompositionDropIndex(int srcCompIdx, int insertSlot, int compCount) {
        if (srcCompIdx <= 0 || srcCompIdx >= compCount || insertSlot < 1 || insertSlot > compCount) {
            return -1;
        }
        if (insertSlot == srcCompIdx || insertSlot == srcCompIdx + 1) {
            return -1;
        }
        int dstIdx = insertSlot > srcCompIdx ? (insertSlot - 1) : insertSlot;
        return (dstIdx >= 1 && dstIdx < compCount && dstIdx != srcCompIdx) ? dstIdx : -1;
    }

    public static int moveCompositionInScene(CustomScene scene, int srcIdx, int dstIdx) {
        if (scene == null) return -1;
        int n = scene.getEntryCount();
        if (srcIdx <= 0 || srcIdx >= n || dstIdx <= 0 || dstIdx >= n || srcIdx == dstIdx) {
            return -1;
        }
        List<CustomComposition> comps = new ArrayList<>(n);
        List<Integer> perm = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            comps.add(scene.getEntry(i));
            perm.add(i);
        }
        CustomComposition moving = comps.remove(srcIdx);
        comps.add(dstIdx, moving);
        int movedOldIdx = perm.remove(srcIdx);
        perm.add(dstIdx, movedOldIdx);
        for (int i = 0; i < n; i++) {
            scene.setEntry(i, comps.get(i));
        }
        int[] oldToNew = new int[n];
        for (int newIdx = 0; newIdx < n; newIdx++) {
            oldToNew[perm.get(newIdx)] = newIdx;
        }
        remapCompositionRefsWithMap(scene, oldToNew, -1, 1);
        return dstIdx;
    }

    private void moveSelectedComposition(int delta) {
        if (currentScene == null || delta == 0 || selectedCompIdx <= 0) return;
        int dstIdx = selectedCompIdx + (delta < 0 ? -1 : 1);
        if (dstIdx >= 1 && dstIdx < currentScene.getEntryCount()) {
            moveComposition(selectedCompIdx, dstIdx);
        }
    }

    private void moveComposition(int srcIdx, int dstIdx) {
        int result = moveCompositionInScene(currentScene, srcIdx, dstIdx);
        if (result < 0) return;
        selectedCompIdx = result;
        selectedElemIdx = -1;
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        if (currentScene != null && currentScene.getEntryCount() > 0) {
            selectedCompIdx = Math.max(0, Math.min(selectedCompIdx, currentScene.getEntryCount() - 1));
        } else {
            selectedCompIdx = 0;
        }
        rendererCore.triggerHighlightByIndices(selectedCompIdx, -1);
        triggerAutoSave();
        statusMessage = "Moved Composition " + srcIdx + " -> Composition " + selectedCompIdx;
    }

    private void moveSelectedElement(int delta) {
        if (currentScene == null || selectedElemIdx < 0) return;
        int[] target = computeStepMoveTarget(currentScene, selectedCompIdx, selectedElemIdx, delta);
        if (target != null) {
            moveElement(selectedCompIdx, selectedElemIdx, target[0], target[1]);
        }
    }

    private void moveElement(int srcCompIdx, int srcElemIdx, int dstCompIdx, int dstElemIdx) {
        int[] result = moveElementInScene(currentScene, srcCompIdx, srcElemIdx, dstCompIdx, dstElemIdx);
        if (result == null) return;
        selectedCompIdx = result[0];
        selectedElemIdx = result[1];
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        if (currentScene != null && currentScene.getEntryCount() > 0) {
            selectedCompIdx = Math.max(0, Math.min(selectedCompIdx, currentScene.getEntryCount() - 1));
            CustomComposition refreshed = currentScene.getEntry(selectedCompIdx);
            int refreshedN = refreshed != null ? refreshed.getEntryCount() : 0;
            selectedElemIdx = refreshedN > 0 ? Math.max(0, Math.min(selectedElemIdx, refreshedN - 1)) : -1;
        } else {
            selectedCompIdx = 0;
            selectedElemIdx = -1;
        }
        rendererCore.triggerHighlightByIndices(selectedCompIdx, selectedElemIdx);
        triggerAutoSave();
        statusMessage = (srcCompIdx != dstCompIdx) ? ("Moved layer to Composition " + dstCompIdx) : "Reordered layer";
    }

    private void deleteSelected() {
        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        if (comp == null) return;
        if (selectedElemIdx >= 0) {
            if (selectedElemIdx < comp.getEntryCount()) {
                comp.removeEntry(selectedElemIdx);
                rendererCore.setThemeElements(currentScene);
                currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
                int newCount = currentScene.getEntry(selectedCompIdx) != null
                        ? currentScene.getEntry(selectedCompIdx).getEntryCount() : 0;
                selectedElemIdx = newCount > 0 ? Math.min(selectedElemIdx, newCount - 1) : -1;
                triggerAutoSave();
                statusMessage = "Deleted layer";
            }
        } else if (selectedCompIdx == 0) {
            statusMessage = "Composition 0 (Final) cannot be deleted";
        } else if (selectedCompIdx > 0) {
            int deletedIdx = selectedCompIdx;
            int moved = deleteCompositionMergingLayers(currentScene, deletedIdx);
            if (moved >= 0) {
                rendererCore.setThemeElements(currentScene);
                currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
                selectedCompIdx = Math.max(0, Math.min(deletedIdx - 1, currentScene.getEntryCount() - 1));
                selectedElemIdx = -1;
                triggerAutoSave();
                statusMessage = "Deleted Composition " + deletedIdx
                        + (moved > 0 ? " (merged " + moved + " layer" + (moved == 1 ? "" : "s")
                        + " into Composition " + (deletedIdx - 1) + ")" : "");
            }
        }
    }

    private static void remapCompositionRefsAfterDelete(CustomScene scene, int deletedIdx) {
        if (scene == null) return;
        int newCount = scene.getEntryCount();
        int origCount = newCount + 1;
        int[] oldToNew = new int[origCount];
        for (int i = 0; i < origCount; i++) {
            if (i < deletedIdx) {
                oldToNew[i] = i;
            } else if (i == deletedIdx) {
                oldToNew[i] = -1;
            } else {
                oldToNew[i] = i - 1;
            }
        }
        int fallback = Math.max(1, Math.min(deletedIdx - 1, Math.max(1, newCount - 1)));
        remapCompositionRefsWithMap(scene, oldToNew, deletedIdx, fallback);
    }

    private static void remapCompositionRefsWithMap(CustomScene scene, int[] oldToNew, int deletedIdx, int sourceCompFallback) {
        if (scene == null || oldToNew == null) return;
        for (int c = 0; c < scene.getEntryCount(); c++) {
            CustomComposition comp = scene.getEntry(c);
            if (comp == null) continue;
            for (int e = 0; e < comp.getEntryCount(); e++) {
                CustomPropertiesList elem = comp.getData(e);
                if (elem == null) continue;
                for (String prop : new String[]{"TargetImage", "targetImage", "customImage", "MaskImage"}) {
                    String val = elem.getPropertyString(prop, "");
                    if (val != null && val.startsWith("composition:")) {
                        try {
                            int ref = Integer.parseInt(val.substring("composition:".length()).trim());
                            if (ref == deletedIdx) {
                                elem.putPropertyStringValue(prop, "");
                            } else if (ref >= 0 && ref < oldToNew.length && oldToNew[ref] >= 0) {
                                elem.putPropertyStringValue(prop, "composition:" + oldToNew[ref]);
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                int ref = elem.getPropertyInt("sourceCompositionIndex", -1);
                if (ref >= 0) {
                    if (ref == deletedIdx) {
                        elem.putPropertyIntValue("sourceCompositionIndex", sourceCompFallback);
                    } else if (ref < oldToNew.length && oldToNew[ref] >= 0) {
                        elem.putPropertyIntValue("sourceCompositionIndex", oldToNew[ref]);
                    }
                }
            }
        }
    }

    private void renderAboutDialog() {
        if (aboutOpen) {
            ImGui.openPopup("About AveeCraft##Modal");
            aboutOpen = false;
        }
        ImGuiViewport vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getCenterX(), vp.getCenterY(), imgui.flag.ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(480f, 0f, imgui.flag.ImGuiCond.Always);
        if (ImGui.beginPopupModal("About AveeCraft##Modal", imgui.flag.ImGuiWindowFlags.NoResize)) {
            if (aboutIconTex != 0) {
                ImGui.image(aboutIconTex, 64f, 64f, 0f, 0f, 1f, 1f);
                ImGui.sameLine();
            }
            ImGui.beginGroup();
            ImGui.text("AveeCraft " + UpdateManager.getCurrentVersion());
            ImGui.textDisabled("Desktop Audio Visualizer Editor & GPU Video Exporter");
            ImGui.endGroup();
            ImGui.separator();
            ImGui.spacing();
            ImGui.textWrapped("Cross-platform Java/OpenGL visualizer studio, .viz template editor, and hardware-accelerated exporter ported from Android Avee Player.");
            ImGui.spacing();
            ImGui.text("Repository:");
            ImGui.textColored(0.45f, 0.78f, 1.0f, 1.0f, REPO_URL);
            if (ImGui.button("Open Repository", 150f, 0f)) {
                openBrowserUrl(REPO_URL);
            }
            ImGui.sameLine();
            if (ImGui.button("Copy URL", 100f, 0f)) {
                ImGui.setClipboardText(REPO_URL);
                statusMessage = "Copied repository URL";
            }
            ImGui.sameLine();
            if (ImGui.button("Close", 100f, 0f)) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private void renderUpdateDialog() {
        if (updateDialogOpen) {
            ImGui.openPopup("AveeCraft Updates##Modal");
            updateDialogOpen = false;
        }
        ImGuiViewport vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getCenterX(), vp.getCenterY(), imgui.flag.ImGuiCond.Appearing, 0.5f, 0.5f);
        ImGui.setNextWindowSize(520f, 0f, imgui.flag.ImGuiCond.Always);
        if (ImGui.beginPopupModal("AveeCraft Updates##Modal", ImGuiWindowFlags.NoResize)) {
            ImGui.text("Current version: " + updateManager.getEffectiveCurrentVersion());
            UpdateManager.State st = updateManager.getState();
            String stText = updateManager.getStatusText();
            if (stText != null && !stText.isEmpty()) {
                if (st == UpdateManager.State.ERROR) {
                    ImGui.textColored(1.0f, 0.45f, 0.45f, 1.0f, stText);
                } else if (st == UpdateManager.State.AVAILABLE || st == UpdateManager.State.READY) {
                    ImGui.textColored(0.45f, 0.92f, 0.55f, 1.0f, stText);
                } else {
                    ImGui.textDisabled(stText);
                }
            }
            ImGui.separator();

            UpdateManager.ReleaseInfo rel = updateManager.getLatestRelease();
            if (rel != null) {
                ImGui.text("Latest release: " + rel.tagName + (rel.prerelease ? " (pre-release)" : ""));
                UpdateManager.ReleaseAsset asset = updateManager.getSelectedAsset();
                if (asset != null) {
                    String sizeMb = asset.size > 0
                            ? String.format(java.util.Locale.ROOT, " (%.1f MB)", asset.size / (1024.0 * 1024.0))
                            : "";
                    ImGui.textDisabled("Package: " + asset.name + sizeMb);
                }
                if (rel.body != null && !rel.body.trim().isEmpty()) {
                    ImGui.beginChild("##RelNotes", 0f, 150f, true);
                    ImGui.textWrapped(rel.body.trim());
                    ImGui.endChild();
                }
            }

            if (st == UpdateManager.State.DOWNLOADING) {
                float prog = updateManager.getDownloadProgress();
                long dlKb = updateManager.getDownloadedBytes() / 1024L;
                long totKb = updateManager.getTotalBytes() / 1024L;
                String overlay = totKb > 0 ? (dlKb + " / " + totKb + " KB") : (dlKb + " KB");
                ImGui.progressBar(prog, -1f, 0f, overlay);
            }

            ImGui.spacing();
            if (st != UpdateManager.State.CHECKING && st != UpdateManager.State.DOWNLOADING) {
                if (ImGui.button("Check Again", 110f, 0f)) {
                    updateManager.checkForUpdatesAsync(true);
                }
                ImGui.sameLine();
            }
            if (st == UpdateManager.State.AVAILABLE && updateManager.getSelectedAsset() != null) {
                if (ImGui.button("Download Update", 135f, 0f)) {
                    updateManager.downloadUpdateAsync();
                }
                ImGui.sameLine();
                if (ImGui.button("Skip Version", 105f, 0f)) {
                    updateManager.skipLatestVersion();
                }
                ImGui.sameLine();
            } else if (st == UpdateManager.State.READY) {
                if (ImGui.button("Install & Restart", 135f, 0f)) {
                    try {
                        boolean shouldExit = updateManager.applyDownloadedUpdate();
                        statusMessage = updateManager.getStatusText();
                        if (shouldExit) {
                            long win = GLFW.glfwGetCurrentContext();
                            if (win != 0L) GLFW.glfwSetWindowShouldClose(win, true);
                        }
                    } catch (Exception ex) {
                        statusMessage = "Install failed: " + ex.getMessage();
                    }
                }
                ImGui.sameLine();
            }
            if (rel != null && rel.htmlUrl != null && !rel.htmlUrl.isEmpty()) {
                if (ImGui.button("Release Page", 110f, 0f)) {
                    openBrowserUrl(rel.htmlUrl);
                }
                ImGui.sameLine();
            }
            if (ImGui.button("Close", 80f, 0f)) {
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }
    }

    private static void openBrowserUrl(String url) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                return;
            }
        } catch (Throwable ignored) {}
        try {
            String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
            } else {
                new ProcessBuilder("xdg-open", url).start();
            }
        } catch (Throwable ignored) {}
    }

    private void pickAndLoadAudio() {
        File f = openDialog("Load Audio Track",
                new String[]{"*.mp3", "*.wav", "*.flac", "*.ogg", "*.m4a", "*.aac"},
                "Audio Files (*.mp3, *.wav, *.flac, *.ogg, *.m4a)");
        if (f != null) {
            statusMessage = "Decoding " + f.getName() + "...";
            playQueue(java.util.Collections.singletonList(f), 0);
        }
    }

    private static File openDialog(String title, String[] patterns, String filterDesc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pb = stack.mallocPointer(patterns.length);
            for (String p : patterns) pb.put(stack.UTF8(p));
            pb.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(title, "", pb, filterDesc, false);
            return (path != null && !path.trim().isEmpty()) ? new File(path.trim()) : null;
        }
    }

    private static File saveDialog(String title, String defName, String[] patterns, String filterDesc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pb = stack.mallocPointer(patterns.length);
            for (String p : patterns) pb.put(stack.UTF8(p));
            pb.flip();
            String path = TinyFileDialogs.tinyfd_saveFileDialog(title, defName, pb, filterDesc);
            return (path != null && !path.trim().isEmpty()) ? new File(path.trim()) : null;
        }
    }

    private static File ensureExt(File f, String ext) {
        if (f.getName().toLowerCase().endsWith(ext)) return f;
        return new File(f.getParentFile(), f.getName() + ext);
    }

    private void toggleMcpBridge(boolean enable) {
        if (enable) {
            try {
                mcpBridge.start();
                statusMessage = "MCP Bridge listening on 127.0.0.1:" + mcpBridge.getPort();
            } catch (Exception e) {
                statusMessage = "MCP Bridge failed: " + e.getMessage();
            }
        } else {
            mcpBridge.stop();
            statusMessage = "MCP Bridge stopped";
        }
    }

    private static String buildMcpClientConfigJson() {
        String appPath = System.getProperty("jpackage.app-path", "").trim();
        org.json.JSONObject server = new org.json.JSONObject();
        if (!appPath.isEmpty()) {
            server.put("command", appPath);
            server.put("args", new org.json.JSONArray(java.util.Arrays.asList("--mcp")));
        } else {
            String javaBin = new File(System.getProperty("java.home"), "bin/java").getAbsolutePath();
            String cp = System.getProperty("java.class.path", "");
            server.put("command", javaBin);
            server.put("args", new org.json.JSONArray(java.util.Arrays.asList(
                    "-cp", cp, AveeEditorApp.class.getName(), "--mcp")));
        }
        return new org.json.JSONObject()
                .put("mcpServers", new org.json.JSONObject().put("aveecraft", server))
                .toString(2);
    }

    @Override
    public String getMode() { return "live"; }

    @Override
    public boolean isReadOnly() { return mcpReadOnly; }

    @Override
    public CustomScene getScene() { return currentScene; }

    @Override
    public void setScene(CustomScene scene, boolean rebuildElements) {
        this.currentScene = scene;
        if (rendererCore != null && scene != null) {
            if (rebuildElements) rendererCore.setThemeElements(scene);
            else rendererCore.setThemeCustomizationData(scene);
        }
        triggerAutoSave();
    }

    @Override
    public void refreshLiveScene() {
        if (rendererCore != null) {
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
            triggerAutoSave();
        }
    }

    @Override
    public RendererCore getRendererCore() { return rendererCore; }

    @Override
    public DesktopAudioEngine getAudioEngine() { return audioEngine; }

    @Override
    public int getSelectedCompIdx() { return selectedCompIdx; }

    @Override
    public int getSelectedElemIdx() { return selectedElemIdx; }

    @Override
    public void setSelection(int compIdx, int elemIdx, boolean triggerHighlight) {
        this.selectedCompIdx = compIdx;
        this.selectedElemIdx = elemIdx;
        if (currentScene != null && currentScene.getEntryCount() > 0) {
            selectedCompIdx = Math.max(0, Math.min(selectedCompIdx, currentScene.getEntryCount() - 1));
            CustomComposition comp = currentScene.getEntry(selectedCompIdx);
            int n = comp != null ? comp.getEntryCount() : 0;
            if (selectedElemIdx >= n) selectedElemIdx = n - 1;
        }
        if (triggerHighlight && rendererCore != null) {
            rendererCore.triggerHighlightByIndices(selectedCompIdx, selectedElemIdx);
        }
    }

    @Override
    public int getPreviewWidth() { return previewW; }

    @Override
    public int getPreviewHeight() { return previewH; }

    @Override
    public void setPreviewSize(int w, int h) {
        previewW = Math.max(128, Math.min(3840, w));
        previewH = Math.max(128, Math.min(2160, h));
    }

    @Override
    public File getProjectFile() { return currentProjectFile; }

    @Override
    public void setProjectFile(File file) { this.currentProjectFile = file; }

    @Override
    public int getActivePresetIndex() { return activePresetIdx; }

    @Override
    public void setActivePresetIndex(int idx) { this.activePresetIdx = idx; }

    @Override
    public void setStatusMessage(String msg) {
        if (msg != null) this.statusMessage = msg;
    }

    @Override
    public BufferedImage capturePreviewImage(int advanceFrames, long seekMs) {
        if (seekMs >= 0L) {
            audioEngine.stepOfflineFrame(seekMs, 0.016f);
        }
        ensurePreviewFbo(previewW, previewH);
        int steps = Math.max(1, Math.min(120, advanceFrames));
        for (int i = 0; i < steps; i++) {
            Looper.drainMainQueue();
            audioEngine.tickFrame(0.016f);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
            GLES20.setRedirectFramebuffer0(previewFbo);
            rendererCore.onDrawFrame(null);
            GLES20.setRedirectFramebuffer0(0);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        }
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
        Bitmap bmp = GraphicsUtils.savePixels(0, 0, previewW, previewH, null);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        return bmp != null ? bmp.getBufferedImage() : null;
    }

    @Override
    public void captureWindowImageAsync(java.util.concurrent.CompletableFuture<BufferedImage> future) {
        this.pendingWindowScreenshot = future;
    }

    public static void main(String[] args) throws Exception {
        if (args != null && args.length >= 1 && "--mcp".equals(args[0])) {
            String[] mcpArgs = java.util.Arrays.copyOfRange(args, 1, args.length);
            McpStdioServer.main(mcpArgs);
            return;
        }
        if (args != null && args.length >= 2 && "--export".equals(args[0])) {
            ExportWorker.main(new String[]{args[1]});
            return;
        }
        tlog.d("Starting AveeCraft...");
        if (Integer.getInteger("avee.smokeFrames", 0) > 0) {
            final Thread mainT = Thread.currentThread();
            Thread wd = new Thread(() -> {
                try {
                    Thread.sleep(6000);
                    System.err.println("[SMOKE WATCHDOG] AveeEditorApp still alive after 6s; non-daemon threads:");
                    for (java.util.Map.Entry<Thread, StackTraceElement[]> e : Thread.getAllStackTraces().entrySet()) {
                        Thread th = e.getKey();
                        if (!th.isDaemon() && th.isAlive()) {
                            System.err.println("  THREAD: " + th.getName() + " state=" + th.getState());
                            for (StackTraceElement el : e.getValue()) {
                                System.err.println("    at " + el);
                            }
                        }
                    }
                    Runtime.getRuntime().halt(2);
                } catch (InterruptedException ignored) {}
            }, "SmokeWatchdog");
            wd.setDaemon(true);
            wd.start();
        }
        String display = System.getenv("DISPLAY");
        if (display != null && !display.isEmpty()) {
            try {
                org.lwjgl.glfw.GLFW.glfwInitHint(
                        org.lwjgl.glfw.GLFW.GLFW_PLATFORM,
                        org.lwjgl.glfw.GLFW.GLFW_PLATFORM_X11);
            } catch (Throwable ignored) {
            }
        }
        launch(new AveeEditorApp());
    }
}

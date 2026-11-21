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
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImFontGlyphRangesBuilder;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.app.Application;
import imgui.app.Configuration;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
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

    private DesktopAudioEngine audioEngine;
    private RendererCore rendererCore;
    private CustomScene currentScene;
    private final PropertyInspectorPanel inspector = new PropertyInspectorPanel();
    private final ExportDialogPanel exportDialog = new ExportDialogPanel();

    private int selectedCompIdx = 0;
    private int selectedElemIdx = -1; // -1 selects the composition itself
    private int[] activeDragPayload = null;

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
    private final ImBoolean synthChk = new ImBoolean(true);
    private final ImBoolean muteChk = new ImBoolean(false);
    private final float[] seekTmp = new float[1];
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
            ImFontGlyphRangesBuilder builder = new ImFontGlyphRangesBuilder();
            builder.addRanges(fonts.getGlyphRangesDefault());
            builder.addRanges(fonts.getGlyphRangesVietnamese());
            builder.addRanges(new short[]{0x0100, 0x024F, 0x1E00, 0x1EFF, 0x2000, 0x26FF, 0});
            ImFontConfig cfg = new ImFontConfig();
            if (fonts.addFontFromFileTTF(fontPath, 15.0f, cfg, builder.buildRanges()) == null) {
                fonts.addFontDefault();
            }
            cfg.destroy();
        }
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

        loadBuiltInPreset(0);
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

        // Run main-thread callbacks (e.g. image decode results), drain MCP requests, and tick clock
        Looper.drainMainQueue();
        mcpBridge.drainUiTasks();
        audioEngine.tickFrame(dtSec);

        // Render Avee scene into our offscreen preview FBO
        ensurePreviewFbo(previewW, previewH);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, previewFbo);
        GLES20.setRedirectFramebuffer0(previewFbo);
        rendererCore.onDrawFrame(null);
        GLES20.setRedirectFramebuffer0(0);
        GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, 0);
        audioEngine.setLatestStats(rendererCore.getFrameStats());

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
        inspector.dispose();
        if (audioEngine != null) {
            audioEngine.shutdown();
        }
        if (aboutIconTex != 0) {
            GL11.glDeleteTextures(aboutIconTex);
            aboutIconTex = 0;
        }
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

    private void renderEditorUi() {
        renderMainMenuBar();

        ImGuiViewport vp = ImGui.getMainViewport();
        ImGui.setNextWindowPos(vp.getWorkPosX(), vp.getWorkPosY());
        ImGui.setNextWindowSize(vp.getWorkSizeX(), vp.getWorkSizeY());
        int hostFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoCollapse | ImGuiWindowFlags.NoResize
                | ImGuiWindowFlags.NoMove | ImGuiWindowFlags.NoBringToFrontOnFocus | ImGuiWindowFlags.NoNavFocus;

        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 6f, 6f);
        ImGui.begin("##AveeCraftHost", hostFlags);
        ImGui.popStyleVar();

        float totalW = ImGui.getContentRegionAvailX();
        float totalH = ImGui.getContentRegionAvailY();
        float leftW = Math.max(250f, Math.min(310f, totalW * 0.21f));
        float rightW = Math.max(330f, Math.min(430f, totalW * 0.28f));
        float centerW = Math.max(280f, totalW - leftW - rightW - 16f);

        // Left: Scene Tree
        ImGui.beginChild("##SceneTreePane", leftW, totalH, true);
        renderSceneTreePanel();
        ImGui.endChild();

        ImGui.sameLine();

        // Center: Live Preview + Audio Transport
        ImGui.beginChild("##CenterPane", centerW, totalH, true);
        renderPreviewAndTransportPanel();
        ImGui.endChild();

        ImGui.sameLine();

        // Right: Property Inspector
        ImGui.beginChild("##InspectorPane", rightW, totalH, true);
        renderInspectorPanel();
        ImGui.endChild();

        ImGui.end();

        exportDialog.render(currentScene, rendererCore, audioEngine, previewW, previewH);
        renderAboutDialog();
        renderUpdateDialog();
    }

    private void renderMainMenuBar() {
        if (ImGui.beginMainMenuBar()) {
            if (ImGui.beginMenu("File")) {
                if (ImGui.menuItem("Open .viz / .json...")) {
                    File f = openDialog("Open Avee Template", new String[]{"*.viz", "*.json", "*.txt"}, "Avee Templates (*.viz, *.json)");
                    if (f != null) loadTemplateFile(f);
                }
                if (ImGui.beginMenu("Load Built-in Preset")) {
                    for (int i = 0; i <= 17; i++) {
                        if (ImGui.menuItem("Visualizer Preset " + i)) {
                            loadBuiltInPreset(i);
                        }
                    }
                    ImGui.endMenu();
                }
                ImGui.separator();
                if (ImGui.menuItem("Save .viz...", currentProjectFile != null)) {
                    if (currentProjectFile != null && currentProjectFile.getName().toLowerCase().endsWith(".viz")) {
                        saveVizFile(currentProjectFile);
                    } else {
                        File f = saveDialog("Save .viz Archive", "template.viz", new String[]{"*.viz"}, "Avee Template Archive (*.viz)");
                        if (f != null) saveVizFile(ensureExt(f, ".viz"));
                    }
                }
                if (ImGui.menuItem("Save .viz As...")) {
                    File f = saveDialog("Save .viz Archive", "template.viz", new String[]{"*.viz"}, "Avee Template Archive (*.viz)");
                    if (f != null) saveVizFile(ensureExt(f, ".viz"));
                }
                if (ImGui.menuItem("Save scene.json As...")) {
                    File f = saveDialog("Save Scene JSON", "scene.json", new String[]{"*.json"}, "JSON (*.json)");
                    if (f != null) saveJsonFile(ensureExt(f, ".json"));
                }
                ImGui.separator();
                if (ImGui.menuItem("Export Video...")) {
                    exportDialog.openDialog(audioEngine);
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
                if (ImGui.menuItem("Synthetic Test Signal", null, audioEngine.isSyntheticBeat())) {
                    audioEngine.setSyntheticBeat(!audioEngine.isSyntheticBeat());
                }
                ImGui.endMenu();
            }

            if (ImGui.beginMenu("Export")) {
                if (ImGui.menuItem("Export Video (GPU Accelerated)...")) {
                    exportDialog.openDialog(audioEngine);
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

        // Action row for selected element
        boolean canEditElem = currentScene != null && selectedElemIdx >= 0;
        if (ImGui.button("Up") && canEditElem) {
            moveSelectedElement(-1);
        }
        ImGui.sameLine();
        if (ImGui.button("Down") && canEditElem) {
            moveSelectedElement(1);
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
            if (ImGui.isItemClicked()) {
                selectedCompIdx = cIdx;
                selectedElemIdx = -1;
                if (rendererCore != null) {
                    rendererCore.triggerHighlightByIndices(cIdx, -1);
                }
            }
            if (ImGui.beginDragDropTarget()) {
                int[] src = ImGui.acceptDragDropPayload("AVEE_LAYER", int[].class);
                if (src != null && src.length == 2) {
                    pendingDragMove = new int[]{src[0], src[1], cIdx, comp.getEntryCount()};
                }
                ImGui.endDragDropTarget();
            }
            if (open) {
                int elemCount = comp.getEntryCount();
                if (elemCount == 0) {
                    ImGui.pushStyleColor(ImGuiCol.Text, 0.55f, 0.55f, 0.55f, 1.0f);
                    ImGui.selectable("  (empty - drop layer here)##empty_" + cIdx, false);
                    ImGui.popStyleColor();
                    if (ImGui.beginDragDropTarget()) {
                        int[] src = ImGui.acceptDragDropPayload("AVEE_LAYER", int[].class);
                        if (src != null && src.length == 2) {
                            pendingDragMove = new int[]{src[0], src[1], cIdx, 0};
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
                    if (ImGui.beginDragDropSource()) {
                        activeDragPayload = new int[]{cIdx, eIdx};
                        ImGui.setDragDropPayload("AVEE_LAYER", activeDragPayload);
                        ImGui.text("Move " + label + " (drop on any layer or Composition)");
                        ImGui.endDragDropSource();
                    }
                    if (ImGui.beginDragDropTarget()) {
                        int[] src = ImGui.acceptDragDropPayload("AVEE_LAYER", int[].class);
                        if (src != null && src.length == 2) {
                            pendingDragMove = new int[]{src[0], src[1], cIdx, eIdx};
                        }
                        ImGui.endDragDropTarget();
                    }
                    ImGui.popID();
                }
                ImGui.treePop();
            }
        }
        ImGui.endChild();
        if (pendingDragMove != null) {
            moveElement(pendingDragMove[0], pendingDragMove[1], pendingDragMove[2], pendingDragMove[3]);
            activeDragPayload = null;
        } else if (!ImGui.isMouseDown(0)) {
            activeDragPayload = null;
        }
    }

    private void renderPreviewAndTransportPanel() {
        // Top preview controls bar
        if (ImGui.combo("Resolution##res", resCombo, RESOLUTION_LABELS)) {
            int[] res = RESOLUTIONS[resCombo.get()];
            previewW = res[0];
            previewH = res[1];
        }
        ImGui.sameLine();
        hqBlurChk.set(audioEngine.isHighQualityBlur());
        if (ImGui.checkbox("HQ Blur", hqBlurChk)) {
            audioEngine.setHighQualityBlur(hqBlurChk.get());
        }
        ImGui.sameLine();
        ImGui.textDisabled("FPS: " + rendererCore.getFrameStats().fps);
        ImGui.sameLine();
        if (ImGui.button("Export Video...")) {
            exportDialog.openDialog(audioEngine);
        }

        float transportH = 72f;
        float availW = Math.max(64f, ImGui.getContentRegionAvailX());
        float availH = Math.max(64f, ImGui.getContentRegionAvailY() - transportH);

        float aspect = (float) previewW / (float) Math.max(1, previewH);
        float drawW = availW;
        float drawH = drawW / aspect;
        if (drawH > availH) {
            drawH = availH;
            drawW = drawH * aspect;
        }
        float padX = Math.max(0f, (availW - drawW) * 0.5f);
        float padY = Math.max(0f, (availH - drawH) * 0.5f);
        if (padX > 0f) ImGui.setCursorPosX(ImGui.getCursorPosX() + padX);
        if (padY > 0f) ImGui.setCursorPosY(ImGui.getCursorPosY() + padY);

        // Flip V (0 -> 1 bottom-to-top) for OpenGL FBO texture orientation in ImGui
        ImGui.image(previewTex, drawW, drawH, 0f, 1f, 1f, 0f);

        // Bottom Audio Transport bar
        ImGui.setCursorPosY(ImGui.getWindowHeight() - transportH);
        ImGui.separator();
        if (ImGui.button("Load Song...")) {
            pickAndLoadAudio();
        }
        ImGui.sameLine();
        if (audioEngine.hasAudioLoaded()) {
            if (ImGui.button(audioEngine.isAudioPlaying() ? "Pause" : "Play")) {
                audioEngine.setPlaying(!audioEngine.isAudioPlaying());
            }
            ImGui.sameLine();
        }
        muteChk.set(audioEngine.isMuted());
        if (ImGui.checkbox("Mute", muteChk)) {
            audioEngine.setMuted(muteChk.get());
        }
        ImGui.sameLine();
        synthChk.set(audioEngine.isSyntheticBeat());
        if (ImGui.checkbox("Test Signal", synthChk)) {
            audioEngine.setSyntheticBeat(synthChk.get());
        }
        ImGui.sameLine();
        ImGui.text(audioEngine.getTitle());

        long durMs = audioEngine.getDurationMs();
        long posMs = audioEngine.getPositionMs();
        seekTmp[0] = posMs / 1000f;
        float maxSec = Math.max(1f, durMs / 1000f);
        ImGui.setNextItemWidth(-120f);
        if (ImGui.sliderFloat("##seek", seekTmp, 0f, maxSec, "%.1fs")) {
            audioEngine.seekMs((long) (seekTmp[0] * 1000f));
        }
        ImGui.sameLine();
        ImGui.text(String.format("%d:%02d / %d:%02d",
                (posMs / 1000) / 60, (posMs / 1000) % 60,
                (durMs / 1000) / 60, (durMs / 1000) % 60));
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
    }

    private void addComposition() {
        if (currentScene == null) return;
        CustomComposition newComp = currentScene.createDataEntry();
        newComp.putChildTypeValue("Composition");
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        selectedCompIdx = Math.max(0, currentScene.getEntryCount() - 1);
        selectedElemIdx = -1;
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
                statusMessage = "Deleted Composition " + deletedIdx
                        + (moved > 0 ? " (merged " + moved + " layer" + (moved == 1 ? "" : "s")
                        + " into Composition " + (deletedIdx - 1) + ")" : "");
            }
        }
    }

    private static void remapCompositionRefsAfterDelete(CustomScene scene, int deletedIdx) {
        if (scene == null) return;
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
                            } else if (ref > deletedIdx) {
                                elem.putPropertyStringValue(prop, "composition:" + (ref - 1));
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
                if (elem.getPropertyType("sourceCompositionIndex") != null) {
                    int ref = elem.getPropertyInt("sourceCompositionIndex", -1);
                    if (ref == deletedIdx) {
                        int fallback = Math.max(1, Math.min(deletedIdx - 1, Math.max(1, scene.getEntryCount() - 1)));
                        elem.putPropertyIntValue("sourceCompositionIndex", fallback);
                    } else if (ref > deletedIdx) {
                        elem.putPropertyIntValue("sourceCompositionIndex", ref - 1);
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
            if (audioEngine.loadAudioFile(f)) {
                statusMessage = "Playing " + f.getName();
            } else {
                statusMessage = "Failed to decode " + f.getName() + " (requires ffmpeg)";
            }
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
    }

    @Override
    public void refreshLiveScene() {
        if (rendererCore != null) {
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
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

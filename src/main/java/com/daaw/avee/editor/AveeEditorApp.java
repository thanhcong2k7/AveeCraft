package com.daaw.avee.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.os.Looper;
import com.daaw.avee.Common.tlog;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.Visualizer.CustomComposition;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Base.Element;
import com.daaw.avee.comp.Visualizer.Elements.ElementsFactory;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
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
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public class AveeEditorApp extends Application {
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
        Context ctx = PlayerCore.s().getAppContext();
        audioEngine = new DesktopAudioEngine();
        rendererCore = new RendererCore(ctx, ctx.getResources(), audioEngine);
        rendererCore.onSurfaceCreated(null, null);
        ensurePreviewFbo(previewW, previewH);
        rendererCore.onSurfaceChanged(null, previewW, previewH);

        loadBuiltInPreset(0);
    }

    @Override
    public void process() {
        long nowNs = System.nanoTime();
        float dtSec = (float) Math.max(0.001, Math.min(0.1, (nowNs - lastFrameNs) / 1_000_000_000.0));
        lastFrameNs = nowNs;

        // Run main-thread callbacks (e.g. image decode results) and update audio engine clock
        Looper.drainMainQueue();
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
        if (exportDialog.isExporting()) {
            exportDialog.cancelExport();
        }
        inspector.dispose();
        if (audioEngine != null) {
            audioEngine.shutdown();
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
        int compCount = currentScene.getEntryCount();
        for (int cIdx = 0; cIdx < compCount; cIdx++) {
            CustomComposition comp = currentScene.getEntry(cIdx);
            if (comp == null) continue;
            boolean compSelected = (selectedCompIdx == cIdx && selectedElemIdx == -1);
            int flags = ImGuiTreeNodeFlags.DefaultOpen | ImGuiTreeNodeFlags.OpenOnArrow
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
            if (open) {
                int elemCount = comp.getEntryCount();
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
                    ImGui.popID();
                }
                ImGui.treePop();
            }
        }
        ImGui.endChild();
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
            if (inspector.render(title, elem)) {
                applySceneChanges(true);
            }
        } else {
            if (inspector.render("Composition " + selectedCompIdx, comp)) {
                applySceneChanges(true);
            }
        }
    }

    private void loadBuiltInPreset(int idx) {
        try {
            currentScene = VizArchiveManager.loadPreset(idx);
            currentProjectFile = null;
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
        Element el = ElementsFactory.create(typeName, "", null);
        if (el == null) return;
        CustomPropertiesList newEntry = comp.createDataEntry();
        el.getCustomization(newEntry, 0, name -> {});
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

    private void moveSelectedElement(int delta) {
        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        if (comp == null || selectedElemIdx < 0) return;
        int n = comp.getEntryCount();
        int dst = selectedElemIdx + delta;
        if (dst < 0 || dst >= n) return;

        List<CustomPropertiesList> items = new ArrayList<>(n);
        for (int i = 0; i < n; i++) items.add(comp.getData(i));
        CustomPropertiesList moving = items.remove(selectedElemIdx);
        items.add(dst, moving);
        for (int i = 0; i < n; i++) comp.setEntry(i, items.get(i));

        selectedElemIdx = dst;
        rendererCore.setThemeElements(currentScene);
        currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
        statusMessage = "Reordered element";
    }

    private void deleteSelected() {
        CustomComposition comp = currentScene.getEntry(selectedCompIdx);
        if (comp == null) return;
        if (selectedElemIdx >= 0) {
            int n = comp.getEntryCount();
            List<CustomPropertiesList> keep = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (i != selectedElemIdx) keep.add(comp.getData(i));
            }
            comp.removeAllEntries();
            for (CustomPropertiesList item : keep) {
                comp.createDataEntryClonedFrom(item);
            }
            rendererCore.setThemeElements(currentScene);
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
            selectedElemIdx = Math.min(selectedElemIdx, keep.size() - 1);
            statusMessage = "Deleted element";
        } else if (selectedCompIdx > 0) {
            int cCount = currentScene.getEntryCount();
            List<CustomComposition> keep = new ArrayList<>();
            for (int i = 0; i < cCount; i++) {
                if (i != selectedCompIdx) keep.add(currentScene.getEntry(i));
            }
            currentScene.removeAllEntries();
            for (int i = 0; i < keep.size(); i++) {
                CustomComposition dst = currentScene.createDataEntry();
                CustomComposition src = keep.get(i);
                for (int e = 0; e < src.getEntryCount(); e++) {
                    dst.createDataEntryClonedFrom(src.getData(e));
                }
            }
            rendererCore.setThemeElements(currentScene);
            currentScene = VizArchiveManager.readLiveScene(currentScene, rendererCore, null);
            selectedCompIdx = Math.max(0, selectedCompIdx - 1);
            selectedElemIdx = -1;
            statusMessage = "Deleted composition";
        }
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

    public static void main(String[] args) {
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

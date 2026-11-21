package com.daaw.avee.mcp;

import com.daaw.avee.Common.MultiList;
import com.daaw.avee.Common.Vec2f;
import com.daaw.avee.comp.AppPreferences.AppPreferences;
import com.daaw.avee.comp.Common.MeasureDefs;
import com.daaw.avee.comp.Visualizer.CustomComposition;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Base.MVariableFloat;
import com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar;
import com.daaw.avee.comp.Visualizer.Elements.ElementsFactory;
import com.daaw.avee.comp.Visualizer.FrameStats;
import com.daaw.avee.comp.Visualizer.Graphic.BlendMode;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.editor.AveeEditorApp;
import com.daaw.avee.editor.DesktopAudioEngine;
import com.daaw.avee.editor.PropertyInspectorPanel;
import com.daaw.avee.editor.UpdateManager;
import com.daaw.avee.editor.VizArchiveManager;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import javax.imageio.ImageIO;
import org.json.JSONArray;
import org.json.JSONObject;

public class AveeSceneService {
    private static final int MAX_HISTORY = 50;

    private static class HistorySnapshot {
        final String sceneJson;
        final int compIdx;
        final int elemIdx;

        HistorySnapshot(String sceneJson, int compIdx, int elemIdx) {
            this.sceneJson = sceneJson;
            this.compIdx = compIdx;
            this.elemIdx = elemIdx;
        }
    }

    private final SceneHost host;
    private final Deque<HistorySnapshot> undoStack = new ArrayDeque<>();
    private final Deque<HistorySnapshot> redoStack = new ArrayDeque<>();

    public AveeSceneService(SceneHost host) {
        this.host = host;
    }

    public SceneHost getHost() {
        return host;
    }

    public void dispatch(String method, JSONObject params, CompletableFuture<JSONObject> resultFuture) {
        try {
            JSONObject p = params != null ? params : new JSONObject();
            if ("screenshot_window".equals(method)) {
                handleScreenshotWindow(p, resultFuture);
                return;
            }
            JSONObject out = executeSync(method, p);
            resultFuture.complete(out);
        } catch (Throwable t) {
            resultFuture.completeExceptionally(t);
        }
    }

    public JSONObject executeSync(String method, JSONObject p) throws Exception {
        switch (method) {
            case "get_state":
                return getState();
            case "get_scene":
                return getScene();
            case "get_layer":
                return getLayer(p);
            case "get_property":
                return getProperty(p);
            case "list_catalog":
                return listCatalog();
            case "render_preview":
                return renderPreview(p);
            case "load_preset":
                requireWritable();
                return loadPreset(p);
            case "open_project":
                requireWritable();
                return openProject(p);
            case "save_project":
                return saveProject(p);
            case "select_layer":
                return selectLayer(p);
            case "add_composition":
                requireWritable();
                return addComposition();
            case "delete_composition":
                requireWritable();
                return deleteComposition(p);
            case "add_layer":
                requireWritable();
                return addLayer(p);
            case "duplicate_layer":
                requireWritable();
                return duplicateLayer(p);
            case "delete_layer":
                requireWritable();
                return deleteLayer(p);
            case "move_layer":
                requireWritable();
                return moveLayer(p);
            case "set_property":
                requireWritable();
                return setProperty(p);
            case "set_properties":
                requireWritable();
                return setProperties(p);
            case "set_audio":
                requireWritable();
                return setAudio(p);
            case "undo":
                requireWritable();
                return undo();
            case "redo":
                requireWritable();
                return redo();
            default:
                throw new IllegalArgumentException("Unknown MCP tool: " + method);
        }
    }

    private void requireWritable() {
        if (host.isReadOnly()) {
            throw new IllegalStateException("MCP server is currently in read-only mode (enable edits in Tools -> MCP Server or launch without --read-only).");
        }
    }

    private void pushUndoSnapshot() {
        CustomScene scene = host.getScene();
        if (scene == null) return;
        undoStack.push(new HistorySnapshot(scene.serialize(), host.getSelectedCompIdx(), host.getSelectedElemIdx()));
        while (undoStack.size() > MAX_HISTORY) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    public JSONObject getState() {
        JSONObject out = new JSONObject();
        CustomScene scene = host.getScene();
        DesktopAudioEngine audio = host.getAudioEngine();
        out.put("mode", host.getMode());
        out.put("readOnly", host.isReadOnly());
        out.put("version", UpdateManager.getCurrentVersion());
        out.put("previewWidth", host.getPreviewWidth());
        out.put("previewHeight", host.getPreviewHeight());
        out.put("compositionCount", scene != null ? scene.getEntryCount() : 0);
        out.put("presetIndex", host.getActivePresetIndex());
        out.put("projectFile", host.getProjectFile() != null ? host.getProjectFile().getAbsolutePath() : JSONObject.NULL);
        out.put("undoAvailable", undoStack.size());
        out.put("redoAvailable", redoStack.size());

        JSONObject sel = new JSONObject();
        sel.put("compIndex", host.getSelectedCompIdx());
        sel.put("layerIndex", host.getSelectedElemIdx());
        out.put("selection", sel);

        if (audio != null) {
            JSONObject a = new JSONObject();
            a.put("title", audio.getTitle());
            a.put("hasAudio", audio.hasAudioLoaded());
            a.put("playing", audio.isAudioPlaying());
            a.put("syntheticBeat", audio.isSyntheticBeat());
            a.put("muted", audio.isMuted());
            a.put("highQualityBlur", audio.isHighQualityBlur());
            a.put("positionMs", audio.getPositionMs());
            a.put("durationMs", audio.getDurationMs());
            out.put("audio", a);
        }
        if (host.getRendererCore() != null) {
            FrameStats stats = host.getRendererCore().getFrameStats();
            out.put("fps", stats != null ? stats.fps : 0);
        }
        return out;
    }

    public JSONObject getScene() {
        CustomScene scene = host.getScene();
        if (scene == null) {
            throw new IllegalStateException("No scene loaded");
        }
        JSONObject out = new JSONObject();
        out.put("mode", host.getMode());
        out.put("compositionCount", scene.getEntryCount());
        out.put("selectedCompIndex", host.getSelectedCompIdx());
        out.put("selectedLayerIndex", host.getSelectedElemIdx());

        JSONArray comps = new JSONArray();
        for (int c = 0; c < scene.getEntryCount(); c++) {
            CustomComposition comp = scene.getEntry(c);
            if (comp == null) continue;
            JSONObject cObj = new JSONObject();
            cObj.put("index", c);
            cObj.put("isFinal", c == 0);
            cObj.put("layerCount", comp.getEntryCount());
            cObj.put("blendMode", comp.getChild("blendMode").getTypeName(""));
            cObj.put("introFadeDurationSec", comp.getPropertyFloat("introFadeDurationSec", 0f));
            cObj.put("outroFadeDurationSec", comp.getPropertyFloat("outroFadeDurationSec", 0f));

            JSONArray layers = new JSONArray();
            for (int e = 0; e < comp.getEntryCount(); e++) {
                CustomPropertiesList elem = comp.getData(e);
                if (elem == null) continue;
                JSONObject lObj = new JSONObject();
                lObj.put("index", e);
                lObj.put("type", elem.getTypeName("Unknown"));
                lObj.put("tag", elem.getTagName());
                lObj.put("visible", elem.getPropertyBool("visible", true));
                lObj.put("blendMode", elem.getChild("blendMode").getTypeName(""));
                lObj.put("position", elem.getPropertyVec2f("position", new Vec2f(0.5f, 0.5f)).toString());
                lObj.put("scale", elem.getPropertyVec2f("scale", new Vec2f(0.5f, 0.5f)).toString());

                JSONObject links = new JSONObject();
                for (String mediaKey : new String[]{"customImage", "MaskImage", "TargetImage", "targetImage", "customVideo"}) {
                    String mv = elem.getPropertyString(mediaKey, "");
                    if (mv != null && !mv.isEmpty()) {
                        links.put(mediaKey, mv);
                    }
                }
                if (!elem.getPropertyType("sourceCompositionIndex").isEmpty()) {
                    links.put("sourceCompositionIndex", elem.getPropertyInt("sourceCompositionIndex", 1));
                }
                if (links.length() > 0) {
                    lObj.put("links", links);
                }
                layers.put(lObj);
            }
            cObj.put("layers", layers);
            comps.put(cObj);
        }
        out.put("compositions", comps);
        return out;
    }

    public JSONObject getLayer(JSONObject p) {
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        int layerIdx = p.optInt("layerIndex", host.getSelectedElemIdx());
        CustomScene scene = host.getScene();
        CustomPropertiesList target = resolveTarget(scene, compIdx, layerIdx);

        JSONObject out = new JSONObject();
        out.put("compIndex", compIdx);
        out.put("layerIndex", layerIdx);
        out.put("type", layerIdx < 0 ? "Composition" : target.getTypeName("Unknown"));
        out.put("version", target.getVersionId());
        out.put("properties", serializePropertiesGrouped(target, scene != null ? scene.getEntryCount() : 0));
        return out;
    }

    public JSONObject getProperty(JSONObject p) {
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        int layerIdx = p.optInt("layerIndex", host.getSelectedElemIdx());
        String path = p.getString("property");
        CustomScene scene = host.getScene();
        CustomPropertiesList target = resolveTarget(scene, compIdx, layerIdx);
        PropLocation loc = resolvePropertyLocation(target, path);
        JSONObject out = new JSONObject();
        out.put("compIndex", compIdx);
        out.put("layerIndex", layerIdx);
        out.put("property", path);
        out.put("schema", serializeSingleProperty(loc.container, loc.key, scene != null ? scene.getEntryCount() : 0));
        return out;
    }

    public JSONObject listCatalog() {
        JSONObject out = new JSONObject();
        JSONArray elements = new JSONArray();
        for (String name : ElementsFactory.getAddableTypeNames()) {
            if (!"Composition".equals(name)) {
                elements.put(name);
            }
        }
        out.put("addableElements", elements);
        out.put("builtInPresetCount", 18);
        out.put("blendModes", new JSONArray(Arrays.asList(BlendMode.useableModes)));
        out.put("measures1d", new JSONArray(Arrays.asList(MeasureDefs.measures1dMVar)));
        out.put("measures2d", new JSONArray(Arrays.asList(MeasureDefs.measures2dMVar)));

        JSONArray wsFiles = new JSONArray();
        File ws = AppPreferences.getActiveWorkspaceFolder();
        if (ws != null && ws.isDirectory()) {
            File[] files = ws.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) {
                    if (f.isFile()) wsFiles.put("local:" + f.getName());
                }
            }
        }
        out.put("workspaceAssets", wsFiles);
        return out;
    }

    private JSONObject renderPreview(JSONObject p) throws IOException {
        int maxWidth = p.optInt("maxWidth", 960);
        int advanceFrames = p.optInt("advanceFrames", 2);
        long timeMs = p.has("timeMs") ? p.optLong("timeMs", -1L) : -1L;
        boolean highlight = p.optBoolean("highlightSelected", false);
        String savePath = p.optString("savePath", "").trim();

        if (p.has("width") && p.has("height")) {
            host.setPreviewSize(p.getInt("width"), p.getInt("height"));
        }
        if (highlight && host.getRendererCore() != null) {
            host.getRendererCore().triggerHighlightByIndices(host.getSelectedCompIdx(), host.getSelectedElemIdx());
        }
        BufferedImage raw = host.capturePreviewImage(advanceFrames, timeMs);
        if (raw == null) {
            throw new IllegalStateException("Failed to capture preview framebuffer");
        }
        return encodeImageResponse(raw, maxWidth, savePath, "preview");
    }

    private void handleScreenshotWindow(JSONObject p, CompletableFuture<JSONObject> resultFuture) {
        int maxWidth = p.optInt("maxWidth", 1280);
        String savePath = p.optString("savePath", "").trim();
        CompletableFuture<BufferedImage> imgFuture = new CompletableFuture<>();
        host.captureWindowImageAsync(imgFuture);
        imgFuture.whenComplete((img, err) -> {
            if (err != null) {
                resultFuture.completeExceptionally(err);
                return;
            }
            if (img == null) {
                resultFuture.completeExceptionally(new IllegalStateException("Failed to capture window framebuffer"));
                return;
            }
            try {
                resultFuture.complete(encodeImageResponse(img, maxWidth, savePath, "window"));
            } catch (Throwable t) {
                resultFuture.completeExceptionally(t);
            }
        });
    }

    private JSONObject encodeImageResponse(BufferedImage raw, int maxWidth, String savePath, String target) throws IOException {
        BufferedImage img = downscaleIfNeeded(raw, maxWidth);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        if (!ImageIO.write(img, "png", baos)) {
            throw new IOException("PNG encoding failed");
        }
        byte[] pngBytes = baos.toByteArray();
        String saved = null;
        if (savePath != null && !savePath.isEmpty()) {
            File outFile = new File(savePath);
            if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
            Files.write(outFile.toPath(), pngBytes);
            saved = outFile.getAbsolutePath();
        }
        JSONObject out = new JSONObject();
        out.put("_mcpImageBase64", Base64.getEncoder().encodeToString(pngBytes));
        out.put("_mcpMimeType", "image/png");
        out.put("target", target);
        out.put("mode", host.getMode());
        out.put("width", img.getWidth());
        out.put("height", img.getHeight());
        if (saved != null) out.put("savedPath", saved);
        return out;
    }

    private static BufferedImage downscaleIfNeeded(BufferedImage src, int maxWidth) {
        if (maxWidth <= 0 || src.getWidth() <= maxWidth) return src;
        int w = maxWidth;
        int h = Math.max(1, Math.round((float) src.getHeight() * w / Math.max(1, src.getWidth())));
        BufferedImage scaled = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return scaled;
    }

    private JSONObject loadPreset(JSONObject p) throws IOException {
        int idx = p.getInt("presetIndex");
        if (idx < 0 || idx > 17) {
            throw new IllegalArgumentException("presetIndex must be between 0 and 17");
        }
        pushUndoSnapshot();
        CustomScene scene = VizArchiveManager.loadPreset(idx);
        host.setProjectFile(null);
        host.setActivePresetIndex(idx);
        host.setScene(scene, true);
        host.refreshLiveScene();
        int elem = (host.getScene().getEntryCount() > 0 && host.getScene().getEntry(0).getEntryCount() > 0) ? 0 : -1;
        host.setSelection(0, elem, false);
        host.setStatusMessage("MCP loaded Preset " + idx);
        return getScene();
    }

    private JSONObject openProject(JSONObject p) throws IOException {
        File file = new File(p.getString("path"));
        pushUndoSnapshot();
        CustomScene scene = VizArchiveManager.loadFile(file);
        host.setProjectFile(file);
        host.setActivePresetIndex(-1);
        host.setScene(scene, true);
        host.refreshLiveScene();
        int elem = (host.getScene().getEntryCount() > 0 && host.getScene().getEntry(0).getEntryCount() > 0) ? 0 : -1;
        host.setSelection(0, elem, false);
        host.setStatusMessage("MCP opened " + file.getName());
        return getScene();
    }

    private JSONObject saveProject(JSONObject p) throws IOException {
        File outFile = new File(p.getString("path"));
        if (outFile.getParentFile() != null) outFile.getParentFile().mkdirs();
        CustomScene scene = host.getScene();
        if (scene == null) throw new IllegalStateException("No scene loaded");
        String lower = outFile.getName().toLowerCase(Locale.ROOT);
        if (lower.endsWith(".json") || lower.endsWith(".txt")) {
            VizArchiveManager.saveJson(outFile, scene, host.getRendererCore());
        } else {
            VizArchiveManager.saveViz(outFile, scene, host.getRendererCore());
        }
        host.setStatusMessage("MCP saved " + outFile.getName());
        JSONObject out = new JSONObject();
        out.put("savedPath", outFile.getAbsolutePath());
        out.put("sizeBytes", outFile.length());
        return out;
    }

    private JSONObject selectLayer(JSONObject p) {
        int compIdx = p.optInt("compIndex", 0);
        int layerIdx = p.optInt("layerIndex", -1);
        boolean highlight = p.optBoolean("highlight", true);
        resolveTarget(host.getScene(), compIdx, layerIdx);
        host.setSelection(compIdx, layerIdx, highlight);
        JSONObject out = new JSONObject();
        out.put("selectedCompIndex", host.getSelectedCompIdx());
        out.put("selectedLayerIndex", host.getSelectedElemIdx());
        return out;
    }

    private JSONObject addComposition() {
        CustomScene scene = host.getScene();
        if (scene == null) throw new IllegalStateException("No scene loaded");
        pushUndoSnapshot();
        CustomComposition newComp = scene.createDataEntry();
        newComp.putChildTypeValue("Composition");
        host.setScene(scene, true);
        host.refreshLiveScene();
        int newIdx = Math.max(0, host.getScene().getEntryCount() - 1);
        host.setSelection(newIdx, -1, true);
        host.setStatusMessage("MCP added Composition " + newIdx);
        JSONObject out = new JSONObject();
        out.put("compIndex", newIdx);
        out.put("compositionCount", host.getScene().getEntryCount());
        return out;
    }

    private JSONObject deleteComposition(JSONObject p) {
        CustomScene scene = host.getScene();
        if (scene == null) throw new IllegalStateException("No scene loaded");
        int compIdx = p.getInt("compIndex");
        if (compIdx <= 0 || compIdx >= scene.getEntryCount()) {
            throw new IllegalArgumentException("Cannot delete compIndex=" + compIdx + " (Composition 0 is Final; valid 1.." + (scene.getEntryCount() - 1) + ")");
        }
        pushUndoSnapshot();
        int moved = AveeEditorApp.deleteCompositionMergingLayers(scene, compIdx);
        host.setScene(scene, true);
        host.refreshLiveScene();
        host.setSelection(Math.max(0, compIdx - 1), -1, false);
        host.setStatusMessage("MCP deleted Composition " + compIdx + " (merged " + moved + " layers)");
        JSONObject out = getScene();
        out.put("mergedLayersCount", moved);
        return out;
    }

    private JSONObject addLayer(JSONObject p) {
        CustomScene scene = host.getScene();
        if (scene == null) throw new IllegalStateException("No scene loaded");
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        String typeName = p.getString("type");
        if ("Composition".equals(typeName) || "Root".equals(typeName) || ElementsFactory.create(typeName, "", null) == null) {
            throw new IllegalArgumentException("Unsupported element type: " + typeName);
        }
        CustomComposition comp = scene.getEntry(compIdx);
        if (comp == null) throw new IllegalArgumentException("Invalid compIndex: " + compIdx);

        pushUndoSnapshot();
        CustomPropertiesList newEntry = comp.createDataEntry();
        newEntry.putChildTypeValue(typeName);
        host.setScene(scene, true);
        host.refreshLiveScene();

        CustomComposition refreshed = host.getScene().getEntry(compIdx);
        int newLayerIdx = refreshed != null ? Math.max(0, refreshed.getEntryCount() - 1) : 0;
        if (compIdx == 0 && !"AppLogo".equals(typeName) && newLayerIdx >= 1
                && "AppLogo".equals(refreshed.getData(newLayerIdx).getTypeName(""))) {
            newLayerIdx--;
        }
        host.setSelection(compIdx, newLayerIdx, true);
        host.setStatusMessage("MCP added " + typeName);

        JSONObject out = getLayer(new JSONObject().put("compIndex", compIdx).put("layerIndex", newLayerIdx));
        out.put("added", true);
        return out;
    }

    private JSONObject duplicateLayer(JSONObject p) {
        CustomScene scene = host.getScene();
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        int layerIdx = p.optInt("layerIndex", host.getSelectedElemIdx());
        if (layerIdx < 0) throw new IllegalArgumentException("layerIndex must be >= 0");
        CustomPropertiesList src = resolveTarget(scene, compIdx, layerIdx);
        CustomComposition comp = scene.getEntry(compIdx);
        pushUndoSnapshot();
        comp.createDataEntryClonedFrom(src);
        host.setScene(scene, true);
        host.refreshLiveScene();
        int newIdx = host.getScene().getEntry(compIdx).getEntryCount() - 1;
        host.setSelection(compIdx, newIdx, true);
        host.setStatusMessage("MCP duplicated layer");
        return getLayer(new JSONObject().put("compIndex", compIdx).put("layerIndex", newIdx));
    }

    private JSONObject deleteLayer(JSONObject p) {
        CustomScene scene = host.getScene();
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        int layerIdx = p.optInt("layerIndex", host.getSelectedElemIdx());
        if (layerIdx < 0) throw new IllegalArgumentException("layerIndex must be >= 0 (use delete_composition to delete a Composition)");
        resolveTarget(scene, compIdx, layerIdx);
        CustomComposition comp = scene.getEntry(compIdx);
        pushUndoSnapshot();
        comp.removeEntry(layerIdx);
        host.setScene(scene, true);
        host.refreshLiveScene();
        int count = host.getScene().getEntry(compIdx).getEntryCount();
        host.setSelection(compIdx, count > 0 ? Math.min(layerIdx, count - 1) : -1, false);
        host.setStatusMessage("MCP deleted layer");
        return getScene();
    }

    private JSONObject moveLayer(JSONObject p) {
        CustomScene scene = host.getScene();
        int srcComp = p.getInt("srcCompIndex");
        int srcLayer = p.getInt("srcLayerIndex");
        int dstComp = p.getInt("dstCompIndex");
        int dstLayer = p.getInt("dstLayerIndex");
        resolveTarget(scene, srcComp, srcLayer);
        if (scene.getEntry(dstComp) == null) throw new IllegalArgumentException("Invalid dstCompIndex: " + dstComp);
        pushUndoSnapshot();
        int[] res = AveeEditorApp.moveElementInScene(scene, srcComp, srcLayer, dstComp, dstLayer);
        if (res == null) {
            undoStack.pop();
            return getScene();
        }
        host.setScene(scene, true);
        host.refreshLiveScene();
        host.setSelection(res[0], res[1], true);
        host.setStatusMessage("MCP moved layer");
        JSONObject out = getScene();
        out.put("movedToCompIndex", res[0]);
        out.put("movedToLayerIndex", res[1]);
        return out;
    }

    private JSONObject setProperty(JSONObject p) {
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        int layerIdx = p.optInt("layerIndex", host.getSelectedElemIdx());
        String path = p.getString("property");
        Object value = p.get("value");
        JSONObject updates = new JSONObject();
        updates.put(path, value);
        return setProperties(new JSONObject()
                .put("compIndex", compIdx)
                .put("layerIndex", layerIdx)
                .put("properties", updates));
    }

    private JSONObject setProperties(JSONObject p) {
        CustomScene scene = host.getScene();
        int compIdx = p.optInt("compIndex", host.getSelectedCompIdx());
        int layerIdx = p.optInt("layerIndex", host.getSelectedElemIdx());
        CustomPropertiesList target = resolveTarget(scene, compIdx, layerIdx);
        JSONObject updates = p.getJSONObject("properties");
        if (updates.length() == 0) {
            throw new IllegalArgumentException("properties map cannot be empty");
        }

        pushUndoSnapshot();
        boolean needsRebuild = false;
        Iterator<String> keys = updates.keys();
        while (keys.hasNext()) {
            String path = keys.next();
            Object val = updates.get(path);
            PropLocation loc = resolvePropertyLocation(target, path);
            if (applySinglePropertyValue(loc.container, loc.key, val)) {
                needsRebuild = true;
            }
        }
        host.setScene(scene, needsRebuild);
        host.refreshLiveScene();
        host.setSelection(compIdx, layerIdx, true);
        host.setStatusMessage("MCP updated " + updates.length() + " property(s)");
        return getLayer(new JSONObject().put("compIndex", compIdx).put("layerIndex", layerIdx));
    }

    private JSONObject setAudio(JSONObject p) {
        DesktopAudioEngine audio = host.getAudioEngine();
        if (audio == null) throw new IllegalStateException("Audio engine unavailable");
        if (p.has("path") && !p.optString("path", "").isEmpty()) {
            File f = new File(p.getString("path"));
            if (!audio.loadAudioFile(f)) {
                throw new IllegalArgumentException("Failed to decode audio file: " + f.getAbsolutePath());
            }
        }
        if (p.has("playing")) {
            audio.setPlaying(p.getBoolean("playing"));
        }
        if (p.has("syntheticBeat")) {
            audio.setSyntheticBeat(p.getBoolean("syntheticBeat"));
        }
        if (p.has("muted")) {
            audio.setMuted(p.getBoolean("muted"));
        }
        if (p.has("highQualityBlur")) {
            audio.setHighQualityBlur(p.getBoolean("highQualityBlur"));
        }
        if (p.has("timeMs")) {
            audio.seekMs(Math.max(0L, p.getLong("timeMs")));
        }
        return getState();
    }

    private JSONObject undo() {
        if (undoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to undo");
        }
        CustomScene cur = host.getScene();
        if (cur != null) {
            redoStack.push(new HistorySnapshot(cur.serialize(), host.getSelectedCompIdx(), host.getSelectedElemIdx()));
        }
        HistorySnapshot snap = undoStack.pop();
        CustomScene restored = CustomScene.CreateFromString(snap.sceneJson);
        host.setScene(restored, true);
        host.refreshLiveScene();
        host.setSelection(snap.compIdx, snap.elemIdx, true);
        host.setStatusMessage("MCP undo");
        return getScene();
    }

    private JSONObject redo() {
        if (redoStack.isEmpty()) {
            throw new IllegalStateException("Nothing to redo");
        }
        CustomScene cur = host.getScene();
        if (cur != null) {
            undoStack.push(new HistorySnapshot(cur.serialize(), host.getSelectedCompIdx(), host.getSelectedElemIdx()));
        }
        HistorySnapshot snap = redoStack.pop();
        CustomScene restored = CustomScene.CreateFromString(snap.sceneJson);
        host.setScene(restored, true);
        host.refreshLiveScene();
        host.setSelection(snap.compIdx, snap.elemIdx, true);
        host.setStatusMessage("MCP redo");
        return getScene();
    }

    private static CustomPropertiesList resolveTarget(CustomScene scene, int compIdx, int layerIdx) {
        if (scene == null) throw new IllegalStateException("No scene loaded");
        CustomComposition comp = scene.getEntry(compIdx);
        if (comp == null) throw new IllegalArgumentException("Invalid compIndex: " + compIdx);
        if (layerIdx < 0) return comp;
        CustomPropertiesList elem = comp.getData(layerIdx);
        if (elem == null) throw new IllegalArgumentException("Invalid layerIndex: " + layerIdx + " in Composition " + compIdx);
        return elem;
    }

    private static class PropLocation {
        final CustomPropertiesList container;
        final String key;

        PropLocation(CustomPropertiesList container, String key) {
            this.container = container;
            this.key = key;
        }
    }

    private static PropLocation resolvePropertyLocation(CustomPropertiesList root, String path) {
        if (path == null || path.trim().isEmpty()) {
            throw new IllegalArgumentException("Property path cannot be empty");
        }
        String[] segs = path.trim().split("[/.]");
        CustomPropertiesList cur = root;
        for (int i = 0; i < segs.length - 1; i++) {
            String childKey = segs[i];
            String t = cur.getPropertyType(childKey);
            if (!t.startsWith("_child")) {
                throw new IllegalArgumentException("Path segment '" + childKey + "' is not a child group");
            }
            cur = cur.getChild(childKey);
        }
        String leaf = segs[segs.length - 1];
        String t = cur.getPropertyType(leaf);
        if (t == null || t.isEmpty()) {
            throw new IllegalArgumentException("Property not found: " + path);
        }
        return new PropLocation(cur, leaf);
    }

    private static JSONArray serializePropertiesGrouped(CustomPropertiesList props, int compCount) {
        JSONArray groups = new JSONArray();
        Map<String, MultiList<String, Integer>> grouped = props.GetAllPropertiesGroupedSortedByOrder();
        for (Map.Entry<String, MultiList<String, Integer>> e : grouped.entrySet()) {
            MultiList<String, Integer> keys = e.getValue();
            if (keys == null || keys.size() == 0) continue;
            JSONArray propsArr = new JSONArray();
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i).obj1;
                if (isHiddenKey(props, k)) continue;
                propsArr.put(serializeSingleProperty(props, k, compCount));
            }
            if (propsArr.length() > 0) {
                JSONObject gObj = new JSONObject();
                gObj.put("group", e.getKey());
                gObj.put("properties", propsArr);
                groups.put(gObj);
            }
        }
        return groups;
    }

    private static boolean isHiddenKey(CustomPropertiesList props, String key) {
        if ("v".equals(key) || "objType".equals(key) || "ver".equals(key)
                || "_name".equals(key) || "_id".equals(key) || "elements".equals(key)) {
            return true;
        }
        String typeStr = props.getPropertyType(key);
        if (typeStr == null || typeStr.isEmpty()) return true;
        String base = CustomPropertiesList.getPropertyTypeParts(typeStr)[0];
        return "ih".equals(base);
    }

    private static JSONObject serializeSingleProperty(CustomPropertiesList props, String key, int compCount) {
        String typeStr = props.getPropertyType(key);
        String[] parts = CustomPropertiesList.getPropertyTypeParts(typeStr);
        String kind = parts[0];
        JSONObject pObj = new JSONObject();
        pObj.put("name", key);
        pObj.put("kind", kind);
        pObj.put("rawType", typeStr);

        if ("b".equals(kind) || "pb".equals(kind)) {
            pObj.put("value", props.getPropertyBool(key, false));
        } else if ("i".equals(kind)) {
            pObj.put("value", props.getPropertyInt(key, 0));
            if (parts.length >= 2) pObj.put("min", parseIntSafe(parts[1], 0));
            if (parts.length >= 3) pObj.put("max", parseIntSafe(parts[2], 100));
            if ("sourceCompositionIndex".equals(key) && compCount > 1) {
                JSONArray compOpts = new JSONArray();
                for (int c = 1; c < compCount; c++) compOpts.put(c);
                pObj.put("availableCompositions", compOpts);
            }
        } else if ("f".equals(kind)) {
            pObj.put("value", props.getPropertyFloat(key, 0f));
            if (parts.length >= 2) pObj.put("min", parseFloatSafe(parts[1], 0f));
            if (parts.length >= 3) pObj.put("max", parseFloatSafe(parts[2], 1f));
        } else if ("f2".equals(kind)) {
            Vec2f v = props.getPropertyVec2f(key, new Vec2f(0f, 0f));
            pObj.put("value", new JSONObject().put("x", v.x).put("y", v.y));
            if (parts.length >= 2) pObj.put("min", parseFloatSafe(parts[1], -1f));
            if (parts.length >= 3) pObj.put("max", parseFloatSafe(parts[2], 1f));
        } else if ("crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind)) {
            int argb = props.getPropertyInt(key, -1);
            pObj.put("value", String.format(Locale.ROOT, "#%08X", argb));
            pObj.put("intValue", argb);
        } else if ("chsla4f".equals(kind) || "chsl4f".equals(kind) || "crgba4f".equals(kind) || "crgb4f".equals(kind)) {
            float[] arr = props.getPropertyArray4f(key, new float[]{1f, 1f, 1f, 1f});
            JSONArray ja = new JSONArray();
            for (float f : arr) ja.put(f);
            pObj.put("value", ja);
        } else if ("sel".equals(kind)) {
            pObj.put("value", props.getPropertySelectString(key, ""));
            JSONArray opts = new JSONArray();
            for (int i = 1; i < parts.length; i++) opts.put(parts[i]);
            pObj.put("options", opts);
        } else if ("txt".equals(kind) || "ptxt".equals(kind)) {
            pObj.put("value", props.getPropertyString(key, ""));
        } else if ("img".equals(kind) || "pimg".equals(kind) || "asset".equals(kind) || "passet".equals(kind)) {
            pObj.put("value", props.getPropertyString(key, ""));
            boolean isFont = "typeFace".equals(key);
            boolean isVideoOnly = "customVideo".equals(key);
            pObj.put("options", new JSONArray(PropertyInspectorPanel.buildMediaOptions(parts, isFont, isVideoOnly, compCount)));
        } else if ("mvarf".equals(kind) || "mvarf2".equals(kind)) {
            MVariableFloat mv = props.getPropertyMVariableFloat(key, MVariableFloat.CreateConstantFloat(0f));
            JSONArray terms = new JSONArray();
            for (int i = 0; i < mv.getMeasuredVarCount(); i++) {
                MeasuredVar m = mv.getMeasuredVar(i);
                Vec2f arg = m.getMeasureArgVec2f();
                terms.put(new JSONObject().put("measure", m.getMeasure()).put("x", arg.x).put("y", arg.y));
            }
            pObj.put("value", terms);
            pObj.put("text", mv.toString());
            if (parts.length >= 2) pObj.put("min", parseFloatSafe(parts[1], -10f));
            if (parts.length >= 3) pObj.put("max", parseFloatSafe(parts[2], 10f));
        } else if ("mea".equals(kind)) {
            MeasuredVar m = props.getPropertyMeasuredVar(key, MeasuredVar.AsNothing());
            Vec2f arg = m.getMeasureArgVec2f();
            pObj.put("value", new JSONObject().put("measure", m.getMeasure()).put("x", arg.x).put("y", arg.y));
        } else if ("_child".equals(kind)) {
            CustomPropertiesList child = props.getChild(key);
            pObj.put("value", child.getTypeName(""));
            if (parts.length > 1) {
                JSONArray opts = new JSONArray();
                for (int i = 1; i < parts.length; i++) opts.put(parts[i]);
                pObj.put("options", opts);
            }
            pObj.put("children", serializePropertiesGrouped(child, compCount));
        }
        return pObj;
    }

    private static boolean applySinglePropertyValue(CustomPropertiesList props, String key, Object val) {
        String typeStr = props.getPropertyType(key);
        String[] parts = CustomPropertiesList.getPropertyTypeParts(typeStr);
        String kind = parts[0];

        if ("b".equals(kind) || "pb".equals(kind)) {
            boolean b = (val instanceof Boolean) ? (Boolean) val
                    : (val instanceof Number) ? (((Number) val).intValue() != 0)
                    : Boolean.parseBoolean(String.valueOf(val));
            props.putPropertyBoolValue(key, b);
            return false;
        }
        if ("i".equals(kind)) {
            int iv = (val instanceof Number) ? ((Number) val).intValue() : Integer.parseInt(String.valueOf(val).trim());
            props.putPropertyIntValue(key, iv);
            return "sourceCompositionIndex".equals(key);
        }
        if ("f".equals(kind)) {
            float fv = (val instanceof Number) ? ((Number) val).floatValue() : Float.parseFloat(String.valueOf(val).trim());
            props.putPropertyFloatValue(key, fv);
            return false;
        }
        if ("f2".equals(kind)) {
            Vec2f v = parseVec2f(val);
            props.putPropertyVec2fValue(key, v);
            return false;
        }
        if ("crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind)) {
            int argb = parseColorArgb(val, "crgba".equals(kind));
            props.putPropertyIntValue(key, argb);
            return false;
        }
        if ("chsla4f".equals(kind) || "chsl4f".equals(kind) || "crgba4f".equals(kind) || "crgb4f".equals(kind)) {
            float[] f4 = parseFloat4(val);
            props.putPropertyArray4fValue(key, f4);
            return false;
        }
        if ("sel".equals(kind)) {
            props.putPropertySelectStringValue(key, String.valueOf(val));
            return "measure".equals(key) || "gradientMode".equals(key);
        }
        if ("txt".equals(kind) || "ptxt".equals(kind)) {
            props.putPropertyStringValue(key, String.valueOf(val));
            return false;
        }
        if ("img".equals(kind) || "pimg".equals(kind) || "asset".equals(kind) || "passet".equals(kind)) {
            props.putPropertyStringValue(key, String.valueOf(val));
            return true;
        }
        if ("mvarf".equals(kind) || "mvarf2".equals(kind)) {
            MVariableFloat mv = parseMVariableFloat(val);
            props.putPropertyMVariableFloatValue(key, mv);
            return false;
        }
        if ("mea".equals(kind)) {
            MeasuredVar m = parseMeasuredVar(val);
            props.putPropertyMeasuredVar(key, m);
            return false;
        }
        if ("_child".equals(kind)) {
            CustomPropertiesList child = props.getChild(key);
            child.putChildTypeValue(String.valueOf(val));
            return !"blendMode".equals(key);
        }
        throw new IllegalArgumentException("Unsupported property kind '" + kind + "' for " + key);
    }

    private static Vec2f parseVec2f(Object val) {
        if (val instanceof JSONObject) {
            JSONObject o = (JSONObject) val;
            return new Vec2f((float) o.getDouble("x"), (float) o.getDouble("y"));
        }
        if (val instanceof JSONArray) {
            JSONArray a = (JSONArray) val;
            return new Vec2f((float) a.getDouble(0), (float) a.getDouble(1));
        }
        return Vec2f.FromString(String.valueOf(val), new Vec2f(0f, 0f));
    }

    private static int parseColorArgb(Object val, boolean keepAlpha) {
        if (val instanceof Number) {
            int argb = ((Number) val).intValue();
            return keepAlpha ? argb : (argb | 0xFF000000);
        }
        if (val instanceof JSONArray) {
            JSONArray a = (JSONArray) val;
            float r = (float) a.getDouble(0);
            float g = (float) a.getDouble(1);
            float b = (float) a.getDouble(2);
            float alpha = a.length() >= 4 ? (float) a.getDouble(3) : 1.0f;
            if (r > 1.0f || g > 1.0f || b > 1.0f || alpha > 1.0f) {
                r /= 255.0f;
                g /= 255.0f;
                b /= 255.0f;
                alpha /= 255.0f;
            }
            return GraphicsUtils.f4ColorToIntColor(keepAlpha ? alpha : 1.0f, r, g, b);
        }
        String s = String.valueOf(val).trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
            if (s.length() == 6) s = "FF" + s;
            int argb = (int) Long.parseLong(s, 16);
            return keepAlpha ? argb : (argb | 0xFF000000);
        }
        if (s.startsWith("0x") || s.startsWith("0X")) {
            int argb = (int) Long.parseLong(s.substring(2), 16);
            return keepAlpha ? argb : (argb | 0xFF000000);
        }
        int argb = Integer.parseInt(s);
        return keepAlpha ? argb : (argb | 0xFF000000);
    }

    private static float[] parseFloat4(Object val) {
        float[] out = new float[]{1f, 1f, 1f, 1f};
        if (val instanceof JSONArray) {
            JSONArray a = (JSONArray) val;
            for (int i = 0; i < Math.min(4, a.length()); i++) out[i] = (float) a.getDouble(i);
            return out;
        }
        String[] parts = String.valueOf(val).trim().split("[\\s,;]+");
        for (int i = 0; i < Math.min(4, parts.length); i++) {
            out[i] = Float.parseFloat(parts[i]);
        }
        return out;
    }

    private static MeasuredVar parseMeasuredVar(Object val) {
        if (val instanceof JSONObject) {
            JSONObject o = (JSONObject) val;
            String m = o.optString("measure", MeasureDefs.Constant);
            float x = (float) o.optDouble("x", o.optDouble("a", 0.0));
            float y = (float) o.optDouble("y", o.optDouble("b", x));
            return new MeasuredVar(m, x, y);
        }
        return MeasuredVar.FromString(String.valueOf(val), MeasuredVar.AsNothing());
    }

    private static MVariableFloat parseMVariableFloat(Object val) {
        if (val instanceof JSONArray) {
            JSONArray a = (JSONArray) val;
            if (a.length() == 0) return MVariableFloat.CreateConstantFloat(0f);
            MVariableFloat mv = new MVariableFloat(parseMeasuredVar(a.get(0)));
            for (int i = 1; i < a.length(); i++) {
                mv = new MVariableFloat(mv, parseMeasuredVar(a.get(i)));
            }
            return mv;
        }
        return MVariableFloat.FromString(String.valueOf(val), MVariableFloat.CreateConstantFloat(0f));
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s); } catch (Exception e) { return def; }
    }
}

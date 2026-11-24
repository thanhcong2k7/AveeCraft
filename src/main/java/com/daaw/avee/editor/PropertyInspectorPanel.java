package com.daaw.avee.editor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.daaw.avee.Common.MultiList;
import com.daaw.avee.Common.Vec2f;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.AlbumArt.AlbumArtCore;
import com.daaw.avee.comp.AlbumArt.InternalBitmapLoader;
import com.daaw.avee.comp.AppPreferences.AppPreferences;
import com.daaw.avee.comp.Common.MeasureDefs;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Elements.Base.MVariableFloat;
import com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar;
import com.daaw.avee.comp.Visualizer.Elements.Composition;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.headless.VideoFrameDecoder;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiColorEditFlags;
import imgui.flag.ImGuiCond;
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiSliderFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

public class PropertyInspectorPanel {
    private static final float ROW_H = 42.0f;
    private static final float ASSET_ROW_H = 48.0f;
    private static final float BOX_H = 28.0f;
    private static final float BOX_GAP = 5.0f;

    private static class ThumbEntry {
        int texId;
        int w;
        int h;
        String label;
    }

    private static class PreviewBoxSpec {
        final String text;
        final float minWidth;
        final float maxWidth;
        final float fillRatio; // < 0 hides mini bar
        final boolean muted;
        final boolean accent;

        PreviewBoxSpec(String text, float minWidth, float maxWidth, float fillRatio, boolean muted, boolean accent) {
            this.text = text != null ? text : "";
            this.minWidth = minWidth;
            this.maxWidth = maxWidth;
            this.fillRatio = fillRatio;
            this.muted = muted;
            this.accent = accent;
        }
    }

    private final Map<String, ThumbEntry> thumbCache = new HashMap<>();
    private final int[] tmpInt = new int[1];
    private final float[] tmpFloat = new float[1];
    private final float[] tmpFloat2 = new float[2];
    private final float[] tmpColor4 = new float[4];
    private final float[] tmpColor3 = new float[3];
    private final ImInt tmpCombo = new ImInt();

    private final Map<String, ImString> popupBuffers = new HashMap<>();
    private final ImString popupHexBuf = new ImString(24);
    private String activePopupUid = null;
    private float popupAnchorX = 0.0f;
    private float popupAnchorY = 0.0f;
    private float activePopupContentW = 340.0f;

    private CustomPropertiesList lastInspectedProps = null;
    private CustomScene activeScene = null;
    private final ScreenColorPicker colorPicker = new ScreenColorPicker();

    public ScreenColorPicker getColorPicker() {
        return colorPicker;
    }

    public boolean render(String title, CustomPropertiesList props) {
        return render(title, props, null);
    }

    public boolean render(String title, CustomPropertiesList props, CustomScene scene) {
        this.activeScene = scene;
        if (props != lastInspectedProps) {
            lastInspectedProps = props;
            activePopupUid = null;
            popupBuffers.clear();
            popupHexBuf.set("");
        }
        if (props == null) {
            ImGui.textDisabled("Select an Element or Composition to inspect its properties.");
            return false;
        }
        ImGui.text(title);
        String ver = props.getVersionId();
        if (ver != null && !ver.isEmpty()) {
            ImGui.sameLine();
            ImGui.textDisabled("(v" + ver + ")");
        }
        ImGui.textDisabled("Click any property or value box to edit (sliders + manual input)");
        ImGui.separator();

        return renderPropertiesGrouped(props, 0, "");
    }

    private boolean renderPropertiesGrouped(CustomPropertiesList props, int depth, String pathPrefix) {
        boolean changed = false;
        Map<String, MultiList<String, Integer>> grouped = props.GetAllPropertiesGroupedSortedByOrder();
        for (Map.Entry<String, MultiList<String, Integer>> groupEntry : grouped.entrySet()) {
            String tag = groupEntry.getKey();
            MultiList<String, Integer> keys = groupEntry.getValue();
            if (keys == null || keys.size() == 0) continue;

            boolean hasVisible = false;
            for (int i = 0; i < keys.size(); i++) {
                String k = keys.get(i).obj1;
                if (!isHiddenKey(props, k)) {
                    hasVisible = true;
                    break;
                }
            }
            if (!hasVisible) continue;

            if (depth == 0) {
                if (!ImGui.collapsingHeader(prettifyTag(tag) + "##grp_" + tag, ImGuiTreeNodeFlags.DefaultOpen)) {
                    continue;
                }
            }
            for (int i = 0; i < keys.size(); i++) {
                String key = keys.get(i).obj1;
                if (isHiddenKey(props, key)) continue;
                ImGui.pushID(key);
                if (renderProperty(props, key, depth, pathPrefix + "/" + key)) {
                    changed = true;
                }
                ImGui.popID();
            }
        }
        return changed;
    }

    private static boolean isHiddenKey(CustomPropertiesList props, String key) {
        if ("v".equals(key) || "objType".equals(key) || "ver".equals(key) || "_name".equals(key)
                || "_id".equals(key) || "elements".equals(key)) {
            return true;
        }
        String typeStr = props.getPropertyType(key);
        if (typeStr == null || typeStr.isEmpty()) return true;
        String base = CustomPropertiesList.getPropertyTypeParts(typeStr)[0];
        return "ih".equals(base);
    }

    private boolean renderProperty(CustomPropertiesList props, String key, int depth, String uid) {
        String typeStr = props.getPropertyType(key);
        String[] parts = CustomPropertiesList.getPropertyTypeParts(typeStr);
        String kind = parts[0];

        if ("b".equals(kind) || "pb".equals(kind)) {
            boolean cur = props.getPropertyBool(key, false);
            PreviewBoxSpec pill = new PreviewBoxSpec(cur ? "ON" : "OFF", 52.0f, 64.0f, -1.0f, !cur, cur);
            if (renderPropertyRowWithBoxes(uid, key, Collections.singletonList(pill), false)) {
                props.putPropertyBoolValue(key, !cur);
                return true;
            }
            return false;
        }

        if ("i".equals(kind)) {
            int min = parts.length >= 2 ? parseIntSafe(parts[1], 0) : 0;
            int max = parts.length >= 3 ? parseIntSafe(parts[2], 100) : 100;
            if ("sourceCompositionIndex".equals(key) && activeScene != null) {
                int compCount = activeScene.getEntryCount();
                int cur = props.getPropertyInt(key, 1);
                if (compCount > 1) {
                    String preview = (cur >= 1 && cur < compCount)
                            ? ("Comp " + cur)
                            : ("Comp " + cur + " (!)");
                    PreviewBoxSpec box = new PreviewBoxSpec(preview, 72.0f, 150.0f, -1.0f, cur < 1 || cur >= compCount, true);
                    String popId = "##pop_" + uid;
                    if (renderPropertyRowWithBoxes(uid, key, Collections.singletonList(box), ImGui.isPopupOpen(popId))) {
                        openPropertyPopup(uid);
                    }
                    boolean changed = false;
                    if (beginAnchoredPropertyPopup(uid, 290.0f, Math.min(260.0f, compCount * 30.0f + 56.0f))) {
                        renderPopupHeader(key, "1.." + (compCount - 1));
                        for (int ci = 1; ci < compCount; ci++) {
                            if (ImGui.selectable("Composition " + ci + "##comp_" + ci, cur == ci)) {
                                props.putPropertyIntValue(key, ci);
                                changed = true;
                                ImGui.closeCurrentPopup();
                            }
                        }
                        ImGui.endPopup();
                    }
                    return changed;
                }
                PreviewBoxSpec emptyBox = new PreviewBoxSpec("No Offscreen Comp", 120.0f, 180.0f, -1.0f, true, false);
                renderPropertyRowWithBoxes(uid, key + " (+ Comp)", Collections.singletonList(emptyBox), false);
                return false;
            }

            tmpInt[0] = props.getPropertyInt(key, min);
            float fill = computePreviewBarFillRatio((float) tmpInt[0], (float) min, (float) max);
            PreviewBoxSpec box = new PreviewBoxSpec(Integer.toString(tmpInt[0]), 54.0f, 110.0f, fill, false, false);
            String popId = "##pop_" + uid;
            if (renderPropertyRowWithBoxes(uid, key, Collections.singletonList(box), ImGui.isPopupOpen(popId))) {
                openPropertyPopup(uid);
            }
            boolean changed = false;
            if (beginAnchoredPropertyPopup(uid, 360.0f, 92.0f)) {
                renderPopupHeader(key, min + " .. " + max);
                if (renderPopupIntSliderAndInput(uid + "#0", null, tmpInt, min, max)) {
                    props.putPropertyIntValue(key, tmpInt[0]);
                    changed = true;
                }
                ImGui.endPopup();
            }
            return changed;
        }

        if ("f".equals(kind)) {
            float min = parts.length >= 2 ? parseFloatSafe(parts[1], 0f) : 0f;
            float max = parts.length >= 3 ? parseFloatSafe(parts[2], 1f) : 1f;
            float step = computeNiceFloatStep(min, max);
            tmpFloat[0] = props.getPropertyFloat(key, min);
            float fill = computePreviewBarFillRatio(tmpFloat[0], min, max);
            PreviewBoxSpec box = new PreviewBoxSpec(formatPreviewFloat(tmpFloat[0], step), 56.0f, 110.0f, fill, false, false);
            String popId = "##pop_" + uid;
            if (renderPropertyRowWithBoxes(uid, key, Collections.singletonList(box), ImGui.isPopupOpen(popId))) {
                openPropertyPopup(uid);
            }
            boolean changed = false;
            if (beginAnchoredPropertyPopup(uid, 360.0f, 92.0f)) {
                renderPopupHeader(key, formatPreviewFloat(min, step) + " .. " + formatPreviewFloat(max, step));
                if (renderPopupFloatSliderAndInput(uid + "#0", null, tmpFloat, 0, min, max, step)) {
                    props.putPropertyFloatValue(key, tmpFloat[0]);
                    changed = true;
                }
                ImGui.endPopup();
            }
            return changed;
        }

        if ("f2".equals(kind)) {
            float min = parts.length >= 2 ? parseFloatSafe(parts[1], -1f) : -1f;
            float max = parts.length >= 3 ? parseFloatSafe(parts[2], 1f) : 1f;
            float step = computeNiceFloatStep(min, max);
            Vec2f v = props.getPropertyVec2f(key, new Vec2f(0f, 0f));
            tmpFloat2[0] = v.x;
            tmpFloat2[1] = v.y;
            List<PreviewBoxSpec> boxes = new ArrayList<>(2);
            boxes.add(new PreviewBoxSpec(formatPreviewFloat(tmpFloat2[0], step), 54.0f, 94.0f,
                    computePreviewBarFillRatio(tmpFloat2[0], min, max), false, false));
            boxes.add(new PreviewBoxSpec(formatPreviewFloat(tmpFloat2[1], step), 54.0f, 94.0f,
                    computePreviewBarFillRatio(tmpFloat2[1], min, max), false, false));
            String popId = "##pop_" + uid;
            if (renderPropertyRowWithBoxes(uid, key, boxes, ImGui.isPopupOpen(popId))) {
                openPropertyPopup(uid);
            }
            boolean changed = false;
            if (beginAnchoredPropertyPopup(uid, 380.0f, 130.0f)) {
                renderPopupHeader(key, formatPreviewFloat(min, step) + " .. " + formatPreviewFloat(max, step));
                boolean chX = renderPopupFloatSliderAndInput(uid + "#0", "X", tmpFloat2, 0, min, max, step);
                boolean chY = renderPopupFloatSliderAndInput(uid + "#1", "Y", tmpFloat2, 1, min, max, step);
                if (chX || chY) {
                    props.putPropertyVec2fValue(key, new Vec2f(tmpFloat2[0], tmpFloat2[1]));
                    changed = true;
                }
                ImGui.endPopup();
            }
            return changed;
        }

        if ("crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind)
                || "chsla4f".equals(kind) || "chsl4f".equals(kind) || "crgba4f".equals(kind) || "crgb4f".equals(kind)) {
            return renderColorPropertyRowAndPopup(props, key, kind, uid);
        }

        if ("sel".equals(kind)) {
            String cur = props.getPropertySelectString(key, "");
            int n = Math.max(0, parts.length - 1);
            String[] opts = new String[n];
            for (int i = 0; i < n; i++) {
                opts[i] = parts[i + 1];
            }
            PreviewBoxSpec box = new PreviewBoxSpec(
                    cur.isEmpty() ? "(select) v" : (cur + "  v"), 84.0f, 175.0f, -1.0f, cur.isEmpty(), true);
            String popId = "##pop_" + uid;
            if (renderPropertyRowWithBoxes(uid, key, Collections.singletonList(box), ImGui.isPopupOpen(popId))) {
                openPropertyPopup(uid);
            }
            boolean changed = false;
            if (n > 0 && beginAnchoredPropertyPopup(uid, 270.0f, Math.min(280.0f, n * 28.0f + 54.0f))) {
                renderPopupHeader(key, n + " options");
                for (int i = 0; i < n; i++) {
                    if (ImGui.selectable(opts[i] + "##sel_" + i, opts[i].equals(cur))) {
                        props.putPropertySelectStringValue(key, opts[i]);
                        changed = true;
                        ImGui.closeCurrentPopup();
                    }
                }
                ImGui.endPopup();
            }
            return changed;
        }

        if ("txt".equals(kind) || "ptxt".equals(kind)) {
            String cur = props.getPropertyString(key, "");
            if (cur == null) cur = "";
            String singleLinePreview = cur.replace('\r', ' ').replace('\n', ' ').trim();
            if (singleLinePreview.isEmpty()) singleLinePreview = "(empty)";
            PreviewBoxSpec box = new PreviewBoxSpec(singleLinePreview, 100.0f, 200.0f, -1.0f, cur.isEmpty(), false);
            String popId = "##pop_" + uid;
            if (renderPropertyRowWithBoxes(uid, key, Collections.singletonList(box), ImGui.isPopupOpen(popId))) {
                openPropertyPopup(uid);
                popupBuffers.put(uid + "#txt", new ImString(cur, 1024));
            }
            boolean changed = false;
            if (beginAnchoredPropertyPopup(uid, 420.0f, 152.0f)) {
                renderPopupHeader(key, "Text Input");
                ImString buf = getPopupBuffer(uid + "#txt", cur, 1024);
                if (ImGui.inputTextMultiline("##mtxt_" + uid, buf, activePopupContentW, 82.0f)) {
                    props.putPropertyStringValue(key, buf.get());
                    changed = true;
                }
                if (ImGui.button("Done##done_" + uid, 80.0f, 0.0f)) {
                    ImGui.closeCurrentPopup();
                }
                ImGui.endPopup();
            }
            return changed;
        }

        if ("img".equals(kind) || "pimg".equals(kind) || "asset".equals(kind) || "passet".equals(kind)) {
            return renderAssetPropertyRowAndPopup(props, key, parts, uid);
        }

        if ("mvarf".equals(kind) || "mvarf2".equals(kind)) {
            return renderMVarPropertyRowAndPopup(props, key, parts, kind, uid);
        }

        if ("mea".equals(kind)) {
            return renderMeasuredVarPropertyRowAndPopup(props, key, uid);
        }

        if ("_child".equals(kind)) {
            CustomPropertiesList child = props.getChild(key);
            if (child != null) {
                boolean childChanged = false;
                String curType = child.getTypeName();
                String headerLabel = key + (curType.isEmpty() ? "" : (" [" + curType + "]"));
                boolean childOpen = ImGui.treeNodeEx(headerLabel + "##child_" + depth + "_" + key,
                        depth == 0 ? ImGuiTreeNodeFlags.DefaultOpen : 0);
                if (childOpen) {
                    if (parts.length > 1) {
                        PreviewBoxSpec typeBox = new PreviewBoxSpec(
                                curType.isEmpty() ? "(none) v" : (curType + "  v"), 84.0f, 175.0f, -1.0f, false, true);
                        String childTypeUid = uid + "/_type";
                        String popId = "##pop_" + childTypeUid;
                        if (renderPropertyRowWithBoxes(childTypeUid, "Type", Collections.singletonList(typeBox), ImGui.isPopupOpen(popId))) {
                            openPropertyPopup(childTypeUid);
                        }
                        if (beginAnchoredPropertyPopup(childTypeUid, 270.0f, Math.min(260.0f, (parts.length - 1) * 28.0f + 54.0f))) {
                            renderPopupHeader(key + " Type", (parts.length - 1) + " options");
                            for (int i = 1; i < parts.length; i++) {
                                if (ImGui.selectable(parts[i] + "##ctype_" + i, parts[i].equals(curType))) {
                                    child.putChildTypeValue(parts[i]);
                                    childChanged = true;
                                    ImGui.closeCurrentPopup();
                                }
                            }
                            ImGui.endPopup();
                        }
                    }
                    if (renderPropertiesGrouped(child, depth + 1, uid)) {
                        childChanged = true;
                    }
                    ImGui.treePop();
                }
                return childChanged;
            }
        }
        return false;
    }

    private boolean renderMVarPropertyRowAndPopup(CustomPropertiesList props, String key, String[] parts,
                                                  String kind, String uid) {
        MVariableFloat mv = props.getPropertyMVariableFloat(key, MVariableFloat.CreateConstantFloat(0f));
        boolean is2d = "mvarf2".equals(kind);
        float min = parts.length >= 2 ? parseFloatSafe(parts[1], -10f) : -10f;
        float max = parts.length >= 3 ? parseFloatSafe(parts[2], 10f) : 10f;
        float step = computeNiceFloatStep(min, max);
        int count = mv.getMeasuredVarCount();

        List<PreviewBoxSpec> boxes = new ArrayList<>();
        if (count > 0) {
            MeasuredVar m0 = mv.getMeasuredVar(0);
            String mName = m0.getMeasure();
            if (!MeasureDefs.Constant.equals(mName) && !MeasureDefs.Nothing.equals(mName)) {
                boxes.add(new PreviewBoxSpec(mName, 44.0f, 86.0f, -1.0f, false, true));
            }
            MeasureDefs.MeasureDef def0 = MeasureDefs.getMeasureDefByName(mName);
            boolean showB0 = (def0 == null) || is2d || def0.isBUsedFor1d;
            Vec2f arg0 = m0.getMeasureArgVec2f();
            boxes.add(new PreviewBoxSpec(formatPreviewFloat(arg0.x, step), 50.0f, 84.0f,
                    computePreviewBarFillRatio(arg0.x, min, max), false, false));
            if (showB0) {
                boxes.add(new PreviewBoxSpec(formatPreviewFloat(arg0.y, step), 50.0f, 84.0f,
                        computePreviewBarFillRatio(arg0.y, min, max), false, false));
            }
            if (count > 1) {
                boxes.add(new PreviewBoxSpec("+" + (count - 1), 30.0f, 44.0f, -1.0f, false, true));
            }
        }

        String popId = "##pop_" + uid;
        if (renderPropertyRowWithBoxes(uid, key, boxes, ImGui.isPopupOpen(popId))) {
            openPropertyPopup(uid);
        }

        boolean isModified = false;
        float estH = 90.0f + Math.max(1, count) * (is2d ? 116.0f : 96.0f);
        if (beginAnchoredPropertyPopup(uid, 430.0f, Math.min(460.0f, estH))) {
            renderPopupHeader(key, formatPreviewFloat(min, step) + " .. " + formatPreviewFloat(max, step));

            String[] opts = is2d ? MeasureDefs.measures2dMVar : MeasureDefs.measures1dMVar;
            int termCount = mv.getMeasuredVarCount();
            for (int mIdx = 0; mIdx < termCount; mIdx++) {
                ImGui.pushID(mIdx);
                if (mIdx > 0) ImGui.separator();
                MeasuredVar m = mv.getMeasuredVar(mIdx);
                int selIdx = 0;
                for (int j = 0; j < opts.length; j++) {
                    if (opts[j].equals(m.getMeasure())) {
                        selIdx = j;
                        break;
                    }
                }
                tmpCombo.set(selIdx);
                String curMeasure = m.getMeasure();
                Vec2f arg = m.getMeasureArgVec2f();
                tmpFloat2[0] = arg.x;
                tmpFloat2[1] = arg.y;
                boolean rowChanged = false;
                if (termCount > 1) {
                    ImGui.textDisabled("Term " + (mIdx + 1));
                }
                ImGui.setNextItemWidth(activePopupContentW);
                if (ImGui.combo("##src_" + mIdx, tmpCombo, opts)) {
                    curMeasure = opts[tmpCombo.get()];
                    rowChanged = true;
                }
                MeasureDefs.MeasureDef def = MeasureDefs.getMeasureDefByName(curMeasure);
                boolean showB = (def == null) || is2d || def.isBUsedFor1d;
                if (showB) {
                    String aHint = (def != null && !def.argAHint.isEmpty()) ? def.argAHint : "X";
                    String bHint = (def != null && !def.argBHint.isEmpty()) ? def.argBHint : "Y";
                    if (renderPopupFloatSliderAndInput(uid + "/mv" + mIdx + "#0", aHint, tmpFloat2, 0, min, max, step)) {
                        rowChanged = true;
                    }
                    if (renderPopupFloatSliderAndInput(uid + "/mv" + mIdx + "#1", bHint, tmpFloat2, 1, min, max, step)) {
                        rowChanged = true;
                    }
                } else {
                    String aHint = (def != null && !def.argAHint.isEmpty()) ? def.argAHint : "Amount";
                    if (renderPopupFloatSliderAndInput(uid + "/mv" + mIdx + "#0", aHint, tmpFloat2, 0, min, max, step)) {
                        rowChanged = true;
                    }
                }
                if (rowChanged) {
                    mv.setMeasuredVar(mIdx, new MeasuredVar(curMeasure, tmpFloat2[0], tmpFloat2[1]));
                    props.putPropertyMVariableFloatValue(key, mv);
                    isModified = true;
                }
                ImGui.popID();
            }

            ImGui.separator();
            if (ImGui.button("+ Term##add_" + key)) {
                props.putPropertyMVariableFloatValue(key,
                        new MVariableFloat(mv, new MeasuredVar(MeasureDefs.Constant, min, min)));
                popupBuffers.clear();
                isModified = true;
            }
            if (termCount > 1) {
                ImGui.sameLine();
                if (ImGui.button("- Term##rem_" + key)) {
                    props.putPropertyMVariableFloatValue(key, new MVariableFloat(mv, 0, termCount - 1));
                    popupBuffers.clear();
                    isModified = true;
                }
            }
            ImGui.endPopup();
        }
        return isModified;
    }

    private boolean renderMeasuredVarPropertyRowAndPopup(CustomPropertiesList props, String key, String uid) {
        MeasuredVar m = props.getPropertyMeasuredVar(key, MeasuredVar.AsNothing());
        if (m == null) m = MeasuredVar.AsNothing();
        String curMeasure = m.getMeasure();
        Vec2f arg = m.getMeasureArgVec2f();
        float step = 0.05f;
        List<PreviewBoxSpec> boxes = new ArrayList<>(3);
        boxes.add(new PreviewBoxSpec(curMeasure, 50.0f, 96.0f, -1.0f, MeasureDefs.Nothing.equals(curMeasure), true));
        boxes.add(new PreviewBoxSpec(formatPreviewFloat(arg.x, step), 46.0f, 76.0f,
                computePreviewBarFillRatio(arg.x, -10f, 10f), false, false));
        boxes.add(new PreviewBoxSpec(formatPreviewFloat(arg.y, step), 46.0f, 76.0f,
                computePreviewBarFillRatio(arg.y, -10f, 10f), false, false));

        String popId = "##pop_" + uid;
        if (renderPropertyRowWithBoxes(uid, key, boxes, ImGui.isPopupOpen(popId))) {
            openPropertyPopup(uid);
        }

        boolean mod = false;
        if (beginAnchoredPropertyPopup(uid, 410.0f, 168.0f)) {
            renderPopupHeader(key, "-10.0 .. 10.0");
            String[] opts = MeasureDefs.measures1d;
            int selIdx = 0;
            for (int j = 0; j < opts.length; j++) {
                if (opts[j].equals(curMeasure)) {
                    selIdx = j;
                    break;
                }
            }
            tmpCombo.set(selIdx);
            ImGui.setNextItemWidth(activePopupContentW);
            if (ImGui.combo("##msrc_" + uid, tmpCombo, opts)) {
                curMeasure = opts[tmpCombo.get()];
                mod = true;
            }
            tmpFloat2[0] = arg.x;
            tmpFloat2[1] = arg.y;
            if (renderPopupFloatSliderAndInput(uid + "/arg#0", "Arg A", tmpFloat2, 0, -10f, 10f, step)) {
                mod = true;
            }
            if (renderPopupFloatSliderAndInput(uid + "/arg#1", "Arg B", tmpFloat2, 1, -10f, 10f, step)) {
                mod = true;
            }
            if (mod) {
                props.putPropertyMeasuredVar(key, new MeasuredVar(curMeasure, tmpFloat2[0], tmpFloat2[1]));
            }
            ImGui.endPopup();
        }
        return mod;
    }

    private boolean renderColorPropertyRowAndPopup(CustomPropertiesList props, String key, String kind, String uid) {
        boolean isIntColor = "crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind);
        boolean hasAlpha = "crgba".equals(kind) || "crgba4f".equals(kind) || "chsla4f".equals(kind);
        boolean isHsla = "chsla4f".equals(kind) || "chsl4f".equals(kind);

        if (isIntColor) {
            int argb = props.getPropertyInt(key, -1);
            GraphicsUtils.intColorToF4Color(tmpColor4, argb);
        } else {
            float[] raw4 = props.getPropertyArray4f(key, new float[]{1f, 1f, 1f, 1f});
            if (isHsla) {
                GraphicsUtils.hlsa1ColorToF4Color(raw4, tmpColor4);
            } else {
                System.arraycopy(raw4, 0, tmpColor4, 0, 4);
            }
        }
        int curArgb = GraphicsUtils.f4ColorToIntColor(tmpColor4);
        String hexLabel = formatColorHex(curArgb, hasAlpha);

        float rowW = Math.max(120.0f, ImGui.getContentRegionAvailX());
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();
        String popId = "##pop_" + uid;
        boolean popupOpen = ImGui.isPopupOpen(popId);

        float pickBtnW = 42.0f;
        float rightReserve = pickBtnW + 8.0f;
        boolean rowClicked = ImGui.invisibleButton("##prop_" + uid, Math.max(40.0f, rowW - rightReserve), ROW_H);
        boolean rowHovered = ImGui.isItemHovered();

        ImGui.setCursorScreenPos(x + rowW - pickBtnW - 4.0f, y + (ROW_H - BOX_H) * 0.5f);
        boolean pickingThis = colorPicker.isTargeting(props, key);
        if (pickingThis) {
            ImGui.pushStyleColor(ImGuiCol.Button, 0.16f, 0.56f, 0.72f, 1.0f);
        }
        boolean pickClicked = ImGui.button((pickingThis ? "..." : "Pick") + "##pick_" + uid, pickBtnW, BOX_H);
        if (pickingThis) {
            ImGui.popStyleColor();
        }
        if (ImGui.isItemHovered()) {
            ImGui.setTooltip("Eyedropper: pick color from anywhere on screen");
        }
        ImGui.setCursorScreenPos(x, y + ROW_H);
        ImGui.dummy(0.0f, 0.0f);

        if (pickClicked) {
            colorPicker.beginPick(props, key, kind, tmpColor4[3]);
        }

        drawRowChrome(dl, x, y, rowW, ROW_H, rowHovered, popupOpen);

        // Right-aligned square color swatch + hex box (to the left of Pick button)
        float hexBoxW = Math.max(76.0f, ImGui.calcTextSize("#AARRGGBB").x + 14.0f);
        float swatchW = BOX_H;
        float hexX = x + rowW - rightReserve - hexBoxW - 4.0f;
        float boxY = y + (ROW_H - BOX_H) * 0.5f;
        float swatchX = hexX - BOX_GAP - swatchW;

        // Checkerboard + live RGBA swatch
        int chkA = ImGui.getColorU32(0.45f, 0.48f, 0.54f, 1.0f);
        int chkB = ImGui.getColorU32(0.28f, 0.30f, 0.35f, 1.0f);
        float midX = swatchX + swatchW * 0.5f;
        float midY = boxY + BOX_H * 0.5f;
        dl.addRectFilled(swatchX + 1f, boxY + 1f, midX, midY, chkA);
        dl.addRectFilled(midX, boxY + 1f, swatchX + swatchW - 1f, midY, chkB);
        dl.addRectFilled(swatchX + 1f, midY, midX, boxY + BOX_H - 1f, chkB);
        dl.addRectFilled(midX, midY, swatchX + swatchW - 1f, boxY + BOX_H - 1f, chkA);
        int fillCol = ImGui.getColorU32(tmpColor4[0], tmpColor4[1], tmpColor4[2], hasAlpha ? tmpColor4[3] : 1.0f);
        dl.addRectFilled(swatchX + 1f, boxY + 1f, swatchX + swatchW - 1f, boxY + BOX_H - 1f, fillCol, 3.0f);
        int borderCol = (rowHovered || popupOpen)
                ? ImGui.getColorU32(0.24f, 0.78f, 0.98f, 0.95f)
                : ImGui.getColorU32(0.32f, 0.37f, 0.46f, 0.95f);
        dl.addRect(swatchX, boxY, swatchX + swatchW, boxY + BOX_H, borderCol, 4.0f, 0, 1.2f);

        drawPreviewBox(dl, hexX, boxY, hexBoxW, BOX_H, new PreviewBoxSpec(hexLabel, hexBoxW, hexBoxW, -1f, false, false),
                rowHovered, popupOpen);

        drawRowLabelClipped(dl, x + 8.0f, y, swatchX - 10.0f, ROW_H, key);

        if (rowClicked) {
            openPropertyPopup(uid);
            popupHexBuf.set(hexLabel);
        }

        boolean edited = false;
        if (beginAnchoredPropertyPopup(uid, 350.0f, 360.0f)) {
            renderPopupHeader(key, hasAlpha ? "RGBA Color" : "RGB Color");
            int pickerFlags = ImGuiColorEditFlags.AlphaBar | ImGuiColorEditFlags.NoSidePreview;
            if (hasAlpha) {
                if (ImGui.colorPicker4("##cp4_" + uid, tmpColor4, pickerFlags)) {
                    edited = true;
                }
            } else {
                tmpColor3[0] = tmpColor4[0];
                tmpColor3[1] = tmpColor4[1];
                tmpColor3[2] = tmpColor4[2];
                if (ImGui.colorPicker3("##cp3_" + uid, tmpColor3, ImGuiColorEditFlags.NoSidePreview)) {
                    tmpColor4[0] = tmpColor3[0];
                    tmpColor4[1] = tmpColor3[1];
                    tmpColor4[2] = tmpColor3[2];
                    tmpColor4[3] = 1.0f;
                    edited = true;
                }
            }
            if (edited) {
                popupHexBuf.set(formatColorHex(GraphicsUtils.f4ColorToIntColor(tmpColor4), hasAlpha));
            }
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted("Hex");
            ImGui.sameLine(0.0f, 8.0f);
            ImGui.setNextItemWidth(132.0f);
            boolean hexEnter = ImGui.inputText("##hex_" + uid, popupHexBuf,
                    ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll);
            if (ImGui.isItemActive() && !edited) {
                Integer parsed = parseColorHex(popupHexBuf.get(), hasAlpha, Math.round(tmpColor4[3] * 255.0f));
                if (parsed != null && parsed.intValue() != curArgb) {
                    GraphicsUtils.intColorToF4Color(tmpColor4, parsed.intValue());
                    edited = true;
                }
            } else if (hexEnter || ImGui.isItemDeactivatedAfterEdit()) {
                Integer parsed = parseColorHex(popupHexBuf.get(), hasAlpha, Math.round(tmpColor4[3] * 255.0f));
                if (parsed != null) {
                    GraphicsUtils.intColorToF4Color(tmpColor4, parsed.intValue());
                    edited = true;
                }
                popupHexBuf.set(formatColorHex(GraphicsUtils.f4ColorToIntColor(tmpColor4), hasAlpha));
            }
            ImGui.sameLine(0.0f, 8.0f);
            float hexRowUsed = ImGui.calcTextSize("Hex").x + 8.0f + 132.0f + 8.0f;
            float pickScreenW = Math.max(88.0f, activePopupContentW - hexRowUsed);
            if (ImGui.button("Pick Screen##pop_pick_" + uid, pickScreenW, 0.0f)) {
                colorPicker.beginPick(props, key, kind, tmpColor4[3]);
                ImGui.closeCurrentPopup();
            }
            ImGui.endPopup();
        }

        if (!edited) return false;
        int newArgb = GraphicsUtils.f4ColorToIntColor(tmpColor4);
        if (isIntColor) {
            props.putPropertyIntValue(key, newArgb);
        } else {
            float[] out4 = new float[4];
            if (isHsla) {
                GraphicsUtils.intColorToHlsa1(out4, newArgb);
            } else {
                System.arraycopy(tmpColor4, 0, out4, 0, 4);
            }
            props.putPropertyArray4fValue(key, out4);
        }
        return true;
    }

    private boolean renderAssetPropertyRowAndPopup(CustomPropertiesList props, String key, String[] parts, String uid) {
        String cur = props.getPropertyString(key, "");
        if (cur == null) cur = "";
        boolean isFont = "typeFace".equals(key);
        boolean isVideoOnly = "customVideo".equals(key);
        boolean isMask = "MaskImage".equals(key);
        boolean allowCompositions = !isFont && !isVideoOnly && hasCompositionSentinel(parts);
        int compCount = activeScene != null ? activeScene.getEntryCount() : 0;

        ThumbEntry thumb = (!isFont && !cur.isEmpty()) ? getOrLoadThumbnail(cur) : null;
        String displayLabel = isMask ? (key + " (Mask)") : key;
        String shortRef = formatMediaOptionLabel(cur, isFont);
        if (shortRef.isEmpty()) shortRef = "(none)";
        if (shortRef.startsWith("local:")) shortRef = shortRef.substring("local:".length());
        if (shortRef.startsWith("internalres:")) shortRef = shortRef.substring("internalres:".length());

        float rowW = Math.max(120.0f, ImGui.getContentRegionAvailX());
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();
        String popId = "##pop_" + uid;
        boolean popupOpen = ImGui.isPopupOpen(popId);

        boolean rowClicked = ImGui.invisibleButton("##prop_" + uid, rowW, ASSET_ROW_H);
        boolean rowHovered = ImGui.isItemHovered();

        drawRowChrome(dl, x, y, rowW, ASSET_ROW_H, rowHovered, popupOpen);

        float thumbSize = 34.0f;
        float thumbY = y + (ASSET_ROW_H - thumbSize) * 0.5f;
        float textBoxW = Math.max(76.0f, Math.min(148.0f, ImGui.calcTextSize(shortRef).x + 18.0f));
        float textBoxX = x + rowW - 8.0f - textBoxW;
        float textBoxY = y + (ASSET_ROW_H - BOX_H) * 0.5f;
        float thumbX = textBoxX - BOX_GAP - thumbSize;

        // Square asset thumbnail box
        int bgCol = ImGui.getColorU32(0.11f, 0.14f, 0.19f, 1.0f);
        int brCol = (rowHovered || popupOpen)
                ? ImGui.getColorU32(0.24f, 0.78f, 0.98f, 0.95f)
                : ImGui.getColorU32(0.32f, 0.37f, 0.46f, 0.95f);
        dl.addRectFilled(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize, bgCol, 4.0f);
        if (thumb != null && thumb.texId != 0) {
            dl.addImage(thumb.texId, thumbX + 2f, thumbY + 2f, thumbX + thumbSize - 2f, thumbY + thumbSize - 2f, 0f, 0f, 1f, 1f);
        } else {
            String badge = isFont ? "Aa" : (cur.startsWith("composition:") ? "Cmp" : (cur.isEmpty() ? "--" : "Img"));
            float bw = ImGui.calcTextSize(badge).x;
            dl.addText(thumbX + (thumbSize - bw) * 0.5f, thumbY + (thumbSize - ImGui.getFontSize()) * 0.5f,
                    ImGui.getColorU32(0.70f, 0.76f, 0.85f, 1.0f), badge);
        }
        dl.addRect(thumbX, thumbY, thumbX + thumbSize, thumbY + thumbSize, brCol, 4.0f, 0, 1.2f);

        drawPreviewBox(dl, textBoxX, textBoxY, textBoxW, BOX_H,
                new PreviewBoxSpec(shortRef, textBoxW, textBoxW, -1f, cur.isEmpty(), false), rowHovered, popupOpen);

        drawRowLabelClipped(dl, x + 8.0f, y, thumbX - 10.0f, ASSET_ROW_H, displayLabel);

        if (rowClicked) {
            openPropertyPopup(uid);
            popupBuffers.put(uid + "#path", new ImString(cur, 512));
        }

        boolean isModified = false;
        if (beginAnchoredPropertyPopup(uid, 430.0f, 250.0f)) {
            renderPopupHeader(displayLabel, isFont ? "Font / Typeface" : "Asset / Media");
            ImString pathBuf = getPopupBuffer(uid + "#path", cur, 512);

            ImGui.setNextItemWidth(activePopupContentW);
            if (ImGui.inputText("##path_" + uid, pathBuf)) {
                props.putPropertyStringValue(key, pathBuf.get());
                isModified = true;
            }

            List<String> optList = buildMediaOptions(parts, isFont, isVideoOnly, compCount);
            if (!optList.isEmpty()) {
                String[] displayOpts = new String[optList.size() + 1];
                displayOpts[0] = isFont ? "(select font / media...)" : "(select integrated / workspace media...)";
                String normalizedCur = cur;
                if (isFont && normalizedCur.startsWith("internal_") && normalizedCur.indexOf(':') > 0) {
                    normalizedCur = normalizedCur.substring(0, normalizedCur.indexOf(':'));
                }
                int selIdx = 0;
                for (int i = 0; i < optList.size(); i++) {
                    String val = optList.get(i);
                    displayOpts[i + 1] = formatMediaOptionLabel(val, isFont);
                    if (val.equals(cur) || val.equals(normalizedCur)) {
                        selIdx = i + 1;
                    }
                }
                tmpCombo.set(selIdx);
                ImGui.setNextItemWidth(activePopupContentW);
                if (ImGui.combo("##preset_" + uid, tmpCombo, displayOpts) && tmpCombo.get() > 0) {
                    String pickedVal = optList.get(tmpCombo.get() - 1);
                    props.putPropertyStringValue(key, pickedVal);
                    pathBuf.set(pickedVal);
                    isModified = true;
                }
            }
            if (allowCompositions && compCount <= 1) {
                ImGui.textDisabled("Tip: Click '+ Comp' in Scene Hierarchy to create a Composition.");
            }

            String browseLabel = isFont ? "Browse Font...##br_" + uid
                    : (isVideoOnly ? "Browse Video...##br_" + uid
                    : (isMask ? "Browse Alpha Mask...##br_" + uid : "Browse Image / Video...##br_" + uid));
            if (ImGui.button(browseLabel)) {
                File picked = openAssetFileDialog(isFont, isVideoOnly);
                if (picked != null) {
                    try {
                        String localRef = VizArchiveManager.importLocalAsset(picked);
                        props.putPropertyStringValue(key, localRef);
                        pathBuf.set(localRef);
                        thumbCache.remove(localRef);
                        isModified = true;
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            ImGui.sameLine();
            if (ImGui.button("Clear##clr_" + uid)) {
                props.putPropertyStringValue(key, "");
                pathBuf.set("");
                isModified = true;
            }
            ImGui.sameLine();
            if (ImGui.button("Done##done_" + uid)) {
                ImGui.closeCurrentPopup();
            }

            String livePath = props.getPropertyString(key, "");
            if (!isFont && livePath != null && !livePath.isEmpty()) {
                ThumbEntry popThumb = getOrLoadThumbnail(livePath);
                if (popThumb != null) {
                    ImGui.textDisabled(popThumb.label);
                    if (popThumb.texId != 0) {
                        float scale = Math.min(200f / Math.max(1, popThumb.w), 110f / Math.max(1, popThumb.h));
                        float tw = Math.max(20f, popThumb.w * scale);
                        float th = Math.max(20f, popThumb.h * scale);
                        ImGui.image(popThumb.texId, tw, th, 0f, 0f, 1f, 1f);
                    }
                }
            }
            ImGui.endPopup();
        }
        return isModified;
    }

    private boolean renderPropertyRowWithBoxes(String uid, String label, List<PreviewBoxSpec> boxes, boolean popupOpen) {
        float rowW = Math.max(120.0f, ImGui.getContentRegionAvailX());
        float x = ImGui.getCursorScreenPosX();
        float y = ImGui.getCursorScreenPosY();
        ImDrawList dl = ImGui.getWindowDrawList();

        boolean clicked = ImGui.invisibleButton("##prop_" + uid, rowW, ROW_H);
        boolean hovered = ImGui.isItemHovered();

        drawRowChrome(dl, x, y, rowW, ROW_H, hovered, popupOpen);

        // Layout preview boxes right-to-left
        float availForBoxes = Math.max(60.0f, rowW * 0.56f);
        int count = boxes != null ? boxes.size() : 0;
        float[] boxWidths = new float[count];
        float totalBoxW = 0.0f;
        for (int i = 0; i < count; i++) {
            PreviewBoxSpec b = boxes.get(i);
            float tw = ImGui.calcTextSize(b.text).x + 16.0f;
            float w = Math.max(b.minWidth, Math.min(b.maxWidth, tw));
            boxWidths[i] = w;
            totalBoxW += w + (i > 0 ? BOX_GAP : 0.0f);
        }
        if (totalBoxW > availForBoxes && count > 0) {
            float scale = availForBoxes / totalBoxW;
            totalBoxW = 0.0f;
            for (int i = 0; i < count; i++) {
                boxWidths[i] = Math.max(28.0f, boxWidths[i] * scale);
                totalBoxW += boxWidths[i] + (i > 0 ? BOX_GAP : 0.0f);
            }
        }

        float curX = x + rowW - 8.0f - totalBoxW;
        float boxesLeftX = curX;
        float boxY = y + (ROW_H - BOX_H) * 0.5f;
        for (int i = 0; i < count; i++) {
            drawPreviewBox(dl, curX, boxY, boxWidths[i], BOX_H, boxes.get(i), hovered, popupOpen);
            curX += boxWidths[i] + BOX_GAP;
        }

        drawRowLabelClipped(dl, x + 8.0f, y, (count > 0 ? boxesLeftX : (x + rowW - 8.0f)) - 10.0f, ROW_H, label);
        return clicked;
    }

    private static void drawRowChrome(ImDrawList dl, float x, float y, float w, float h,
                                      boolean hovered, boolean popupOpen) {
        if (popupOpen) {
            dl.addRectFilled(x - 2f, y + 1f, x + w + 2f, y + h - 2f,
                    ImGui.getColorU32(0.15f, 0.24f, 0.34f, 0.72f), 4.0f);
            dl.addRectFilled(x - 2f, y + 3f, x + 1f, y + h - 4f,
                    ImGui.getColorU32(0.24f, 0.78f, 0.98f, 1.0f), 1.5f);
        } else if (hovered) {
            dl.addRectFilled(x - 2f, y + 1f, x + w + 2f, y + h - 2f,
                    ImGui.getColorU32(0.20f, 0.25f, 0.33f, 0.52f), 4.0f);
        }
        dl.addLine(x + 2f, y + h - 1f, x + w - 2f, y + h - 1f,
                ImGui.getColorU32(0.22f, 0.26f, 0.34f, 0.45f), 1.0f);
    }

    private static void drawRowLabelClipped(ImDrawList dl, float textX, float rowY, float maxX, float rowH, String label) {
        float limitX = Math.max(textX + 24.0f, maxX);
        dl.pushClipRect(textX, rowY, limitX, rowY + rowH, true);
        dl.addText(textX, rowY + (rowH - ImGui.getFontSize()) * 0.5f,
                ImGui.getColorU32(0.91f, 0.94f, 0.98f, 1.0f), label);
        dl.popClipRect();
    }

    private static void drawPreviewBox(ImDrawList dl, float bx, float by, float bw, float bh,
                                       PreviewBoxSpec spec, boolean hovered, boolean popupOpen) {
        int bgCol;
        if (spec.accent) {
            bgCol = ImGui.getColorU32(0.11f, 0.28f, 0.39f, 0.95f);
        } else if (spec.muted) {
            bgCol = ImGui.getColorU32(0.10f, 0.12f, 0.16f, 0.85f);
        } else {
            bgCol = ImGui.getColorU32(0.12f, 0.15f, 0.20f, 0.96f);
        }
        int borderCol;
        if (popupOpen || hovered) {
            borderCol = ImGui.getColorU32(0.24f, 0.78f, 0.98f, 0.92f);
        } else if (spec.accent) {
            borderCol = ImGui.getColorU32(0.20f, 0.62f, 0.84f, 0.90f);
        } else {
            borderCol = ImGui.getColorU32(0.28f, 0.33f, 0.42f, 0.88f);
        }

        dl.addRectFilled(bx, by, bx + bw, by + bh, bgCol, 4.0f);
        dl.addRect(bx, by, bx + bw, by + bh, borderCol, 4.0f, 0, 1.0f);

        if (spec.fillRatio >= 0.0f) {
            float barX0 = bx + 3.0f;
            float barX1 = bx + bw - 3.0f;
            float barY1 = by + bh - 2.0f;
            float barY0 = barY1 - 2.5f;
            dl.addRectFilled(barX0, barY0, barX1, barY1, ImGui.getColorU32(0.22f, 0.26f, 0.34f, 0.85f), 1.0f);
            float fillEnd = barX0 + (barX1 - barX0) * Math.max(0.0f, Math.min(1.0f, spec.fillRatio));
            if (fillEnd > barX0 + 0.5f) {
                dl.addRectFilled(barX0, barY0, fillEnd, barY1, ImGui.getColorU32(0.24f, 0.78f, 0.98f, 0.95f), 1.0f);
            }
        }

        int txtCol = spec.muted
                ? ImGui.getColorU32(0.56f, 0.61f, 0.69f, 1.0f)
                : (spec.accent ? ImGui.getColorU32(0.45f, 0.88f, 1.0f, 1.0f)
                : ImGui.getColorU32(0.93f, 0.96f, 1.0f, 1.0f));
        float tw = ImGui.calcTextSize(spec.text).x;
        float tx = bx + Math.max(5.0f, (bw - tw) * 0.5f);
        float ty = by + (bh - (spec.fillRatio >= 0f ? 3f : 0f) - ImGui.getFontSize()) * 0.5f;
        dl.pushClipRect(bx + 3.0f, by + 1.0f, bx + bw - 3.0f, by + bh - 2.0f, true);
        dl.addText(tx, ty, txtCol, spec.text);
        dl.popClipRect();
    }

    private ImString getPopupBuffer(String slotId, String initial, int capacity) {
        ImString buf = popupBuffers.get(slotId);
        if (buf == null) {
            buf = new ImString(initial != null ? initial : "", capacity);
            popupBuffers.put(slotId, buf);
        }
        return buf;
    }

    private void openPropertyPopup(String uid) {
        if (!uid.equals(activePopupUid)) {
            popupBuffers.clear();
            activePopupUid = uid;
        }
        popupAnchorX = ImGui.getItemRectMinX();
        popupAnchorY = ImGui.getItemRectMaxY() + 3.0f;
        ImGui.openPopup("##pop_" + uid);
    }

    private boolean beginAnchoredPropertyPopup(String uid, float preferredW, float estHeight) {
        ImGuiViewport vp = ImGui.getMainViewport();
        float vpMinX = vp.getWorkPosX() + 6.0f;
        float vpMinY = vp.getWorkPosY() + 6.0f;
        float vpMaxX = vp.getWorkPosX() + vp.getWorkSizeX() - 6.0f;
        float vpMaxY = vp.getWorkPosY() + vp.getWorkSizeY() - 6.0f;
        float px = Math.max(vpMinX, Math.min(popupAnchorX, vpMaxX - preferredW));
        float py = popupAnchorY;
        if (py + estHeight > vpMaxY) {
            py = Math.max(vpMinY, Math.min(popupAnchorY - ROW_H - estHeight - 6.0f, vpMaxY - estHeight));
        }
        py = Math.max(vpMinY, py);
        ImGui.setNextWindowPos(px, py, ImGuiCond.Appearing);
        ImGui.setNextWindowSize(preferredW, 0.0f, ImGuiCond.Appearing);
        ImGui.setNextWindowSizeConstraints(preferredW, 0.0f, preferredW, 640.0f);
        boolean open = ImGui.beginPopup("##pop_" + uid);
        if (open) {
            activePopupContentW = Math.max(160.0f, preferredW - ImGui.getStyle().getWindowPaddingX() * 2.0f);
        }
        return open;
    }

    private void renderPopupHeader(String title, String hint) {
        ImGui.textUnformatted(title);
        if (hint != null && !hint.isEmpty()) {
            ImGui.sameLine(0.0f, 8.0f);
            ImGui.textDisabled("(" + hint + ")");
        }
        float closeW = 24.0f;
        float closeX = Math.max(40.0f, ImGui.getStyle().getWindowPaddingX() + activePopupContentW - closeW);
        ImGui.sameLine(closeX);
        if (ImGui.smallButton("X##popClose")) {
            ImGui.closeCurrentPopup();
        }
        ImGui.separator();
    }

    private boolean renderPopupIntSliderAndInput(String slotId, String axisLabel, int[] val, int min, int max) {
        int step = computeNiceIntStep(min, max);
        ImString buf = popupBuffers.get(slotId);
        if (buf == null) {
            buf = new ImString(Integer.toString(val[0]), 64);
            popupBuffers.put(slotId, buf);
        }

        float axisW = 0.0f;
        if (axisLabel != null && !axisLabel.isEmpty()) {
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted(axisLabel);
            axisW = ImGui.calcTextSize(axisLabel).x + 8.0f;
            ImGui.sameLine(0.0f, 8.0f);
        }

        float inputW = 92.0f;
        float sliderW = Math.max(120.0f, activePopupContentW - axisW - inputW - 8.0f);
        boolean changed = false;

        ImGui.setNextItemWidth(sliderW);
        boolean slChanged = ImGui.sliderInt("##sl_" + slotId, val, min, max, "%d", ImGuiSliderFlags.NoInput);
        if (slChanged) {
            val[0] = quantizeInt(val[0], min, max, step);
            buf.set(Integer.toString(val[0]));
            changed = true;
        }

        ImGui.sameLine(0.0f, 8.0f);
        ImGui.setNextItemWidth(inputW);
        int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll;
        boolean entered = ImGui.inputText("##txt_" + slotId, buf, flags);
        if (ImGui.isItemActive() && !slChanged) {
            Integer parsed = parseEditedInt(buf.get());
            if (parsed != null && parsed.intValue() != val[0]) {
                val[0] = parsed.intValue();
                changed = true;
            }
        } else if (entered || ImGui.isItemDeactivatedAfterEdit()) {
            Integer parsed = parseEditedInt(buf.get());
            if (parsed != null) {
                if (parsed.intValue() != val[0]) {
                    val[0] = parsed.intValue();
                    changed = true;
                }
                buf.set(Integer.toString(val[0]));
            } else {
                buf.set(Integer.toString(val[0]));
            }
        }
        return changed;
    }

    private boolean renderPopupFloatSliderAndInput(String slotId, String axisLabel, float[] val, int idx,
                                                   float min, float max, float step) {
        ImString buf = popupBuffers.get(slotId);
        if (buf == null) {
            buf = new ImString(formatFloatForEdit(val[idx], step), 64);
            popupBuffers.put(slotId, buf);
        }

        float axisW = 0.0f;
        if (axisLabel != null && !axisLabel.isEmpty()) {
            ImGui.alignTextToFramePadding();
            ImGui.textUnformatted(axisLabel);
            axisW = ImGui.calcTextSize(axisLabel).x + 8.0f;
            ImGui.sameLine(0.0f, 8.0f);
        }

        float inputW = 92.0f;
        float sliderW = Math.max(120.0f, activePopupContentW - axisW - inputW - 8.0f);
        boolean changed = false;

        tmpFloat[0] = val[idx];
        String fmt = step < 0.005f ? "%.4f" : "%.3f";
        ImGui.setNextItemWidth(sliderW);
        boolean slChanged = ImGui.sliderFloat("##sl_" + slotId, tmpFloat, min, max, fmt, ImGuiSliderFlags.NoInput);
        if (slChanged) {
            float snapped = quantizeFloat(tmpFloat[0], min, max, step);
            if (Math.abs(snapped - val[idx]) > 1e-6f) {
                val[idx] = snapped;
                changed = true;
            }
            buf.set(formatFloatForEdit(val[idx], step));
        }

        ImGui.sameLine(0.0f, 8.0f);
        ImGui.setNextItemWidth(inputW);
        int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll;
        boolean entered = ImGui.inputText("##txt_" + slotId, buf, flags);
        if (ImGui.isItemActive() && !slChanged) {
            Float parsed = parseEditedFloat(buf.get());
            if (parsed != null && Float.compare(parsed.floatValue(), val[idx]) != 0) {
                val[idx] = parsed.floatValue();
                changed = true;
            }
        } else if (entered || ImGui.isItemDeactivatedAfterEdit()) {
            Float parsed = parseEditedFloat(buf.get());
            if (parsed != null) {
                if (Float.compare(parsed.floatValue(), val[idx]) != 0) {
                    val[idx] = parsed.floatValue();
                    changed = true;
                }
                buf.set(formatFloatForEdit(val[idx], step));
            } else {
                buf.set(formatFloatForEdit(val[idx], step));
            }
        }
        return changed;
    }

    static float computePreviewBarFillRatio(float val, float min, float max) {
        float span = max - min;
        if (Math.abs(span) < 1e-9f) return 0.0f;
        float ratio = (val - min) / span;
        return Math.max(0.0f, Math.min(1.0f, ratio));
    }

    static String formatPreviewFloat(float val, float step) {
        if (Math.abs(val) < 1e-5f) val = 0.0f;
        if (step >= 5.0f && Math.abs(val - Math.round(val)) < 0.01f) {
            return Integer.toString(Math.round(val));
        }
        if (step < 0.005f) return String.format(Locale.US, "%.4f", Float.valueOf(val));
        if (step < 0.05f) return String.format(Locale.US, "%.3f", Float.valueOf(val));
        if (step < 1.0f) return String.format(Locale.US, "%.2f", Float.valueOf(val));
        return String.format(Locale.US, "%.1f", Float.valueOf(val));
    }

    static String formatColorHex(int argb, boolean hasAlpha) {
        if (hasAlpha) {
            return String.format(Locale.US, "#%08X", Integer.valueOf(argb));
        }
        return String.format(Locale.US, "#%06X", Integer.valueOf(argb & 0x00FFFFFF));
    }

    static Integer parseColorHex(String text, boolean hasAlpha, int fallbackAlpha) {
        if (text == null) return null;
        String s = text.trim();
        if (s.startsWith("#")) s = s.substring(1);
        if (s.isEmpty()) return null;
        try {
            if (s.length() == 6) {
                int rgb = (int) Long.parseLong(s, 16) & 0x00FFFFFF;
                int alpha = hasAlpha ? (Math.max(0, Math.min(255, fallbackAlpha)) << 24) : 0xFF000000;
                return Integer.valueOf(alpha | rgb);
            }
            if (s.length() == 8) {
                int argb = (int) Long.parseLong(s, 16);
                if (!hasAlpha) argb |= 0xFF000000;
                return Integer.valueOf(argb);
            }
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    static String findNextFieldUid(List<String> order, String currentUid, boolean reverse) {
        if (order == null || order.isEmpty() || currentUid == null) {
            return null;
        }
        int idx = order.indexOf(currentUid);
        if (idx < 0) {
            return null;
        }
        int nextIdx = idx + (reverse ? -1 : 1);
        if (nextIdx < 0 || nextIdx >= order.size()) {
            return null;
        }
        return order.get(nextIdx);
    }

    static float computeNiceFloatStep(float min, float max) {
        float span = Math.abs(max - min);
        if (span <= 0.1f) return 0.001f;
        if (span <= 0.5f) return 0.005f;
        if (span <= 2.5f) return 0.01f;
        if (span <= 6.0f) return 0.05f;
        if (span <= 25.0f) return 0.1f;
        if (span <= 100.0f) return 1.0f;
        if (span <= 500.0f) return 5.0f;
        return 10.0f;
    }

    static float quantizeFloat(float raw, float min, float max, float step) {
        float lo = Math.min(min, max);
        float hi = Math.max(min, max);
        if (step <= 0.0f) {
            return Math.max(lo, Math.min(hi, raw));
        }
        double snapped = Math.round(raw / (double) step) * (double) step;
        if (snapped < lo) return lo;
        if (snapped > hi) return hi;
        return (float) snapped;
    }

    static int computeNiceIntStep(int min, int max) {
        long span = Math.abs((long) max - (long) min);
        if (span <= 100L) return 1;
        if (span <= 500L) return 5;
        if (span <= 2000L) return 10;
        if (span <= 10000L) return 50;
        return 1000;
    }

    static int quantizeInt(int raw, int min, int max, int step) {
        int lo = Math.min(min, max);
        int hi = Math.max(min, max);
        if (step <= 1) {
            return Math.max(lo, Math.min(hi, raw));
        }
        if (raw <= lo) return lo;
        if (raw >= hi) return hi;
        long snapped = Math.round(raw / (double) step) * (long) step;
        if (snapped < lo) return lo;
        if (snapped > hi) return hi;
        return (int) snapped;
    }

    static Float parseEditedFloat(String text) {
        if (text == null) return null;
        String trimmed = text.trim().replace(',', '.');
        if (trimmed.isEmpty()) return null;
        try {
            float v = Float.parseFloat(trimmed);
            return (Float.isNaN(v) || Float.isInfinite(v)) ? null : v;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Integer parseEditedInt(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.isEmpty()) return null;
        try {
            return Integer.parseInt(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String formatFloatForEdit(float val, float step) {
        return String.format(Locale.US, step < 0.005f ? "%.4f" : "%.3f", Float.valueOf(val));
    }

    private static boolean isPropertyKindToken(String token) {
        return "img".equals(token) || "pimg".equals(token) || "asset".equals(token) || "passet".equals(token);
    }

    private static boolean hasCompositionSentinel(String[] parts) {
        if (parts == null) return false;
        int startIdx = (parts.length > 0 && isPropertyKindToken(parts[0])) ? 1 : 0;
        for (int i = startIdx; i < parts.length; i++) {
            if (Composition.getIndexFromStringPath(parts[i]) >= 0) {
                return true;
            }
        }
        return false;
    }

    private static String formatMediaOptionLabel(String val, boolean isFont) {
        if (val == null || val.isEmpty()) {
            return isFont ? "Default" : "";
        }
        if (val.startsWith("composition:")) {
            return "Composition " + val.substring("composition:".length()) + " (" + val + ")";
        }
        if (isFont) {
            if ("default_bold".equals(val)) return "Default Bold";
            if ("internal_0".equals(val)) return "Great Vibes (internal_0)";
            if ("internal_1".equals(val)) return "Noto Mono (internal_1)";
            if ("internal_2".equals(val)) return "Orbitron Medium (internal_2)";
            if ("internal_3".equals(val)) return "Orkney Light (internal_3)";
        }
        return val;
    }

    public static List<String> buildMediaOptions(String[] parts, boolean isFont, boolean isVideoOnly, int compCount) {
        Set<String> set = new LinkedHashSet<>();
        int startIdx = (parts != null && parts.length > 0 && isPropertyKindToken(parts[0])) ? 1 : 0;
        boolean allowCompositions = false;
        boolean hasNonCompPreset = false;
        if (parts != null) {
            for (int i = startIdx; i < parts.length; i++) {
                String p = parts[i];
                if (p == null || p.isEmpty()) continue;
                if (Composition.getIndexFromStringPath(p) >= 0) {
                    allowCompositions = true;
                } else {
                    hasNonCompPreset = true;
                }
            }
        }
        if (allowCompositions && !isFont && !isVideoOnly) {
            for (int i = 1; i < compCount; i++) {
                set.add(Composition.makeIndexStringPath(i));
            }
        }
        if (isFont) {
            set.add("");
            set.add("default_bold");
        }
        File ws = AppPreferences.getActiveWorkspaceFolder();
        if (ws != null && ws.isDirectory()) {
            File[] files = ws.listFiles();
            if (files != null) {
                Arrays.sort(files, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
                for (File f : files) {
                    if (!f.isFile()) continue;
                    String lower = f.getName().toLowerCase(Locale.ROOT);
                    if (isFont) {
                        if (lower.endsWith(".ttf") || lower.endsWith(".otf") || lower.endsWith(".ttc")) {
                            set.add("local:" + f.getName());
                        }
                    } else if (isVideoOnly) {
                        if (VideoFrameDecoder.isVideoPath(lower) || lower.endsWith(".gif")) {
                            set.add("local:" + f.getName());
                        }
                    } else if (!lower.endsWith(".json") && !lower.endsWith(".viz")) {
                        set.add("local:" + f.getName());
                    }
                }
            }
        }
        if (parts != null) {
            for (int i = startIdx; i < parts.length; i++) {
                String p = parts[i];
                if (p == null || p.isEmpty()) continue;
                if (Composition.getIndexFromStringPath(p) >= 0) {
                    continue;
                }
                if (isFont || (!p.startsWith("internalres:") && !p.startsWith("local:") && p.matches(".*:\\d+$"))) {
                    int colon = p.indexOf(':');
                    p = colon > 0 ? p.substring(0, colon) : p;
                }
                set.add(p);
            }
        }
        if (!isFont && !isVideoOnly && (parts == null || parts.length <= startIdx || hasNonCompPreset)) {
            set.add("internalres:placeholderart");
        }
        return new ArrayList<>(set);
    }

    private ThumbEntry getOrLoadThumbnail(String path) {
        if (path.startsWith("composition:")) {
            ThumbEntry entry = new ThumbEntry();
            int idx = Composition.getIndexFromStringPath(path);
            if (idx <= 0) {
                entry.label = "[Invalid: Composition 0 is Final output]";
            } else if (activeScene != null && idx >= activeScene.getEntryCount()) {
                entry.label = "[Missing: Composition " + idx + " (only " + (activeScene.getEntryCount() - 1) + " offscreen comps)]";
            } else {
                entry.label = "[Linked to Composition " + idx + "]";
            }
            return entry;
        }
        ThumbEntry cached = thumbCache.get(path);
        if (cached != null) return cached;
        ThumbEntry entry = new ThumbEntry();
        Bitmap bmp = null;
        boolean isVid = VideoFrameDecoder.isVideoPath(path);
        int resId = InternalBitmapLoader.getResIdFromPath(path);
        if (resId > 0) {
            bmp = BitmapFactory.decodeResource(PlayerCore.s().getAppContext().getResources(), resId);
        } else {
            File f = AlbumArtCore.resolveLocalOrFile(path);
            if (f != null && f.exists() && f.isFile()) {
                if (isVid) {
                    bmp = extractVideoThumb(f);
                } else {
                    bmp = BitmapFactory.decodeFile(f.getAbsolutePath());
                }
            } else {
                entry.label = "[Missing file: " + path + "]";
                thumbCache.put(path, entry);
                return entry;
            }
        }
        if (bmp == null) {
            entry.label = isVid ? "[Video: " + path + "]" : "[Unreadable image: " + path + "]";
            thumbCache.put(path, entry);
            return entry;
        }
        entry.w = bmp.getWidth();
        entry.h = bmp.getHeight();
        entry.label = (isVid ? "[Video " : "[Image ") + entry.w + "x" + entry.h + "]";
        BufferedImage bi = bmp.getBufferedImage();
        int tw = Math.min(256, bi.getWidth());
        int th = Math.max(1, Math.round((float) bi.getHeight() * tw / Math.max(1, bi.getWidth())));
        if (th > 160) {
            th = 160;
            tw = Math.max(1, Math.round((float) bi.getWidth() * th / Math.max(1, bi.getHeight())));
        }
        BufferedImage scaled = new BufferedImage(tw, th, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.drawImage(bi, 0, 0, tw, th, null);
        g.dispose();
        int[] argb = scaled.getRGB(0, 0, tw, th, null, 0, tw);
        ByteBuffer buf = ByteBuffer.allocateDirect(tw * th * 4).order(ByteOrder.nativeOrder());
        for (int px : argb) {
            buf.put((byte) ((px >> 16) & 0xFF));
            buf.put((byte) ((px >> 8) & 0xFF));
            buf.put((byte) (px & 0xFF));
            buf.put((byte) ((px >> 24) & 0xFF));
        }
        buf.flip();
        int tex = GL11.glGenTextures();
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, tex);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, tw, th, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
        entry.texId = tex;
        bmp.recycle();
        thumbCache.put(path, entry);
        return entry;
    }

    private static Bitmap extractVideoThumb(File f) {
        try {
            Process p = new ProcessBuilder(
                    FfmpegLocator.ffmpeg(), "-v", "error", "-ss", "0.1", "-i", f.getAbsolutePath(),
                    "-frames:v", "1", "-vf", "scale=256:-2", "-f", "image2pipe", "-vcodec", "png", "pipe:1")
                    .start();
            byte[] png = p.getInputStream().readAllBytes();
            p.waitFor();
            if (png.length > 0) {
                return BitmapFactory.decodeStream(new java.io.ByteArrayInputStream(png));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public void dispose() {
        colorPicker.cancel();
        for (ThumbEntry e : thumbCache.values()) {
            if (e.texId != 0) {
                GL11.glDeleteTextures(e.texId);
                e.texId = 0;
            }
        }
        thumbCache.clear();
    }

    private static File openAssetFileDialog(boolean isFont, boolean isVideoOnly) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters;
            String desc;
            if (isFont) {
                filters = stack.mallocPointer(3);
                filters.put(stack.UTF8("*.ttf"));
                filters.put(stack.UTF8("*.otf"));
                filters.put(stack.UTF8("*.ttc"));
                desc = "Fonts (*.ttf, *.otf, *.ttc)";
            } else if (isVideoOnly) {
                filters = stack.mallocPointer(7);
                filters.put(stack.UTF8("*.mp4"));
                filters.put(stack.UTF8("*.mkv"));
                filters.put(stack.UTF8("*.webm"));
                filters.put(stack.UTF8("*.mov"));
                filters.put(stack.UTF8("*.m4v"));
                filters.put(stack.UTF8("*.avi"));
                filters.put(stack.UTF8("*.gif"));
                desc = "Videos / GIF (*.mp4, *.mkv, *.webm, *.mov, *.gif)";
            } else {
                filters = stack.mallocPointer(12);
                filters.put(stack.UTF8("*.png"));
                filters.put(stack.UTF8("*.jpg"));
                filters.put(stack.UTF8("*.jpeg"));
                filters.put(stack.UTF8("*.gif"));
                filters.put(stack.UTF8("*.bmp"));
                filters.put(stack.UTF8("*.webp"));
                filters.put(stack.UTF8("*.mp4"));
                filters.put(stack.UTF8("*.mkv"));
                filters.put(stack.UTF8("*.webm"));
                filters.put(stack.UTF8("*.mov"));
                filters.put(stack.UTF8("*.m4v"));
                filters.put(stack.UTF8("*.avi"));
                desc = "Images & Videos (*.png, *.jpg, *.gif, *.webp, *.mp4, *.webm, ...)";
            }
            filters.flip();
            String res = TinyFileDialogs.tinyfd_openFileDialog("Choose Media", "", filters, desc, false);
            return (res != null && !res.trim().isEmpty()) ? new File(res.trim()) : null;
        }
    }

    private static String prettifyTag(String tag) {
        if (tag == null || tag.isEmpty()) return "General";
        int idx = tag.indexOf('_');
        String s = (idx == 1 && Character.isDigit(tag.charAt(0))) ? tag.substring(2) : tag;
        if (s.isEmpty()) return tag;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1).replace('_', ' ');
    }

    private static int parseIntSafe(String s, int def) {
        try { return Integer.parseInt(s); } catch (Exception e) { return def; }
    }

    private static float parseFloatSafe(String s, float def) {
        try { return Float.parseFloat(s); } catch (Exception e) { return def; }
    }
}

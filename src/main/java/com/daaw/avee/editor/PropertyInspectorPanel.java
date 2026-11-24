package com.daaw.avee.editor;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import com.daaw.avee.Common.MultiList;
import com.daaw.avee.Common.Tuple2;
import com.daaw.avee.Common.Vec2f;
import com.daaw.avee.Defines;
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
import imgui.flag.ImGuiInputTextFlags;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiSliderFlags;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
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
    private static class ThumbEntry {
        int texId;
        int w;
        int h;
        String label;
    }

    private final Map<String, ThumbEntry> thumbCache = new HashMap<>();
    private final ImBoolean tmpBool = new ImBoolean();
    private final int[] tmpInt = new int[1];
    private final float[] tmpFloat = new float[1];
    private final float[] tmpFloat2 = new float[2];
    private final float[] tmpColor4 = new float[4];
    private final float[] tmpColor3 = new float[3];
    private final ImInt tmpCombo = new ImInt();
    private final ImString inlineEditBuf = new ImString(64);
    private final ImString tmpStr = new ImString(512);
    private String inlineEditKey = null;
    private boolean inlineEditBufNeedsInit = false;
    private int inlineEditFocusFrames = 0;
    private CustomPropertiesList lastInspectedProps = null;
    private final List<String> currentFrameFieldUids = new ArrayList<>();
    private final List<String> lastFrameFieldUids = new ArrayList<>();
    private CustomScene activeScene = null;
    private final float[] rowBounds = new float[3];

    private void beginPropertyRowBg() {
        float minX = ImGui.getCursorScreenPosX() - 4.0f;
        float minY = ImGui.getCursorScreenPosY();
        float maxX = ImGui.getCursorScreenPosX() + ImGui.getContentRegionAvailX() + 4.0f;
        rowBounds[0] = minX;
        rowBounds[1] = minY;
        rowBounds[2] = maxX;
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.channelsSplit(2);
        dl.channelsSetCurrent(1);
    }

    private void endPropertyRowBg() {
        float maxY = ImGui.getItemRectMaxY();
        if (maxY < rowBounds[1] + 4.0f) {
            maxY = ImGui.getCursorScreenPosY();
        }
        ImDrawList dl = ImGui.getWindowDrawList();
        dl.channelsSetCurrent(0);
        if (ImGui.isWindowHovered()
                && ImGui.isMouseHoveringRect(rowBounds[0], rowBounds[1] - 2.0f, rowBounds[2], maxY + 2.0f)) {
            int bgCol = ImGui.getColorU32(0.58f, 0.58f, 0.58f, 0.24f);
            dl.addRectFilled(rowBounds[0], rowBounds[1] - 2.0f, rowBounds[2], maxY + 2.0f, bgCol, 3.0f);
        }
        dl.channelsMerge();
    }

    public boolean render(String title, CustomPropertiesList props) {
        return render(title, props, null);
    }

    public boolean render(String title, CustomPropertiesList props, CustomScene scene) {
        this.activeScene = scene;
        if (props != lastInspectedProps) {
            lastInspectedProps = props;
            inlineEditKey = null;
            inlineEditBufNeedsInit = false;
            inlineEditFocusFrames = 0;
            lastFrameFieldUids.clear();
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
        ImGui.textDisabled("Tip: Double-click or Ctrl+Click any numeric field to type (Tab saves & advances)");
        ImGui.separator();

        currentFrameFieldUids.clear();
        ImGui.pushItemWidth(Math.max(120.0f, ImGui.getContentRegionAvailX() * 0.56f));
        boolean changed = renderPropertiesGrouped(props, 0, "");
        ImGui.popItemWidth();
        lastFrameFieldUids.clear();
        lastFrameFieldUids.addAll(currentFrameFieldUids);
        return changed;
    }

    @SuppressWarnings("unchecked")
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
            beginPropertyRowBg();
            try {
                tmpBool.set(props.getPropertyBool(key, false));
                if (ImGui.checkbox(key, tmpBool)) {
                    props.putPropertyBoolValue(key, tmpBool.get());
                    return true;
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("i".equals(kind)) {
            beginPropertyRowBg();
            try {
                int min = parts.length >= 2 ? parseIntSafe(parts[1], 0) : 0;
                int max = parts.length >= 3 ? parseIntSafe(parts[2], 100) : 100;
                if ("sourceCompositionIndex".equals(key) && activeScene != null) {
                    int compCount = activeScene.getEntryCount();
                    int cur = props.getPropertyInt(key, 1);
                    if (compCount > 1) {
                        String[] compLabels = new String[compCount - 1];
                        for (int i = 0; i < compLabels.length; i++) {
                            compLabels[i] = "Composition " + (i + 1);
                        }
                        int sel = (cur >= 1 && cur < compCount) ? (cur - 1) : 0;
                        tmpCombo.set(sel);
                        boolean ch = ImGui.combo(key + "##compidx", tmpCombo, compLabels);
                        if (cur < 1 || cur >= compCount) {
                            ImGui.textDisabled("(current value " + cur + " is out of range 1.." + (compCount - 1) + ")");
                        }
                        if (ch) {
                            props.putPropertyIntValue(key, tmpCombo.get() + 1);
                            return true;
                        }
                        return false;
                    } else {
                        ImGui.textDisabled(key + ": no offscreen compositions (click + Comp)");
                        max = Math.max(5, max);
                    }
                }
                tmpInt[0] = props.getPropertyInt(key, min);
                if (editIntField(key, uid, tmpInt, min, max)) {
                    props.putPropertyIntValue(key, tmpInt[0]);
                    return true;
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("f".equals(kind)) {
            beginPropertyRowBg();
            try {
                float min = parts.length >= 2 ? parseFloatSafe(parts[1], 0f) : 0f;
                float max = parts.length >= 3 ? parseFloatSafe(parts[2], 1f) : 1f;
                tmpFloat[0] = props.getPropertyFloat(key, min);
                if (editFloatField(key, uid, tmpFloat, min, max)) {
                    props.putPropertyFloatValue(key, tmpFloat[0]);
                    return true;
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("f2".equals(kind)) {
            beginPropertyRowBg();
            try {
                float min = parts.length >= 2 ? parseFloatSafe(parts[1], -1f) : -1f;
                float max = parts.length >= 3 ? parseFloatSafe(parts[2], 1f) : 1f;
                Vec2f v = props.getPropertyVec2f(key, new Vec2f(0f, 0f));
                tmpFloat2[0] = v.x;
                tmpFloat2[1] = v.y;
                float speed = Math.max(0.002f, (max - min) * 0.003f);
                if (editFloat2Field(key, uid, tmpFloat2, speed, min, max)) {
                    props.putPropertyVec2fValue(key, new Vec2f(tmpFloat2[0], tmpFloat2[1]));
                    return true;
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind)) {
            beginPropertyRowBg();
            try {
                int argb = props.getPropertyInt(key, -1);
                GraphicsUtils.intColorToF4Color(tmpColor4, argb);
                if ("crgba".equals(kind)) {
                    if (ImGui.colorEdit4(key, tmpColor4)) {
                        props.putPropertyIntValue(key, GraphicsUtils.f4ColorToIntColor(tmpColor4));
                        return true;
                    }
                } else {
                    tmpColor3[0] = tmpColor4[0];
                    tmpColor3[1] = tmpColor4[1];
                    tmpColor3[2] = tmpColor4[2];
                    if (ImGui.colorEdit3(key, tmpColor3)) {
                        tmpColor4[0] = tmpColor3[0];
                        tmpColor4[1] = tmpColor3[1];
                        tmpColor4[2] = tmpColor3[2];
                        tmpColor4[3] = 1.0f;
                        props.putPropertyIntValue(key, GraphicsUtils.f4ColorToIntColor(tmpColor4));
                        return true;
                    }
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("chsla4f".equals(kind) || "chsl4f".equals(kind) || "crgba4f".equals(kind) || "crgb4f".equals(kind)) {
            beginPropertyRowBg();
            try {
                float[] raw4 = props.getPropertyArray4f(key, new float[]{1f, 1f, 1f, 1f});
                boolean isHsla = "chsla4f".equals(kind) || "chsl4f".equals(kind);
                if (isHsla) {
                    GraphicsUtils.hlsa1ColorToF4Color(raw4, tmpColor4);
                } else {
                    System.arraycopy(raw4, 0, tmpColor4, 0, 4);
                }
                if (ImGui.colorEdit4(key, tmpColor4)) {
                    int newArgb = GraphicsUtils.f4ColorToIntColor(tmpColor4);
                    float[] out4 = new float[4];
                    if (isHsla) {
                        GraphicsUtils.intColorToHlsa1(out4, newArgb);
                    } else {
                        System.arraycopy(tmpColor4, 0, out4, 0, 4);
                    }
                    props.putPropertyArray4fValue(key, out4);
                    return true;
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("sel".equals(kind)) {
            beginPropertyRowBg();
            try {
                String cur = props.getPropertySelectString(key, "");
                int n = Math.max(0, parts.length - 1);
                String[] opts = new String[n];
                int idx = 0;
                for (int i = 0; i < n; i++) {
                    opts[i] = parts[i + 1];
                    if (opts[i].equals(cur)) idx = i;
                }
                if (n > 0) {
                    tmpCombo.set(idx);
                    if (ImGui.combo(key, tmpCombo, opts)) {
                        props.putPropertySelectStringValue(key, opts[tmpCombo.get()]);
                        return true;
                    }
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("txt".equals(kind) || "ptxt".equals(kind)) {
            beginPropertyRowBg();
            try {
                String cur = props.getPropertyString(key, "");
                tmpStr.set(cur != null ? cur : "");
                if (ImGui.inputText(key, tmpStr)) {
                    props.putPropertyStringValue(key, tmpStr.get());
                    return true;
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("img".equals(kind) || "pimg".equals(kind) || "asset".equals(kind) || "passet".equals(kind)) {
            beginPropertyRowBg();
            try {
                boolean isModified = false;
                String cur = props.getPropertyString(key, "");
                if (cur == null) cur = "";
                boolean isFont = "typeFace".equals(key);
                boolean isVideoOnly = "customVideo".equals(key);
                boolean isMask = "MaskImage".equals(key);
                boolean allowCompositions = !isFont && !isVideoOnly && hasCompositionSentinel(parts);
                int compCount = activeScene != null ? activeScene.getEntryCount() : 0;

                ImGui.text(isMask ? (key + " (Alpha Mask)") : key);
                tmpStr.set(cur);
                if (ImGui.inputText("Path##path_" + key, tmpStr)) {
                    props.putPropertyStringValue(key, tmpStr.get());
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
                    if (ImGui.combo("Media##preset_" + key, tmpCombo, displayOpts) && tmpCombo.get() > 0) {
                        props.putPropertyStringValue(key, optList.get(tmpCombo.get() - 1));
                        isModified = true;
                    }
                }
                if (allowCompositions && compCount <= 1) {
                    ImGui.textDisabled("Tip: Click '+ Comp' to create a Composition to link here.");
                }

                String browseLabel = isFont ? "Browse Font...##" + key
                        : (isVideoOnly ? "Browse Video...##" + key
                        : (isMask ? "Browse Alpha Mask...##" + key : "Browse Image / Video...##" + key));
                if (ImGui.button(browseLabel)) {
                    File picked = openAssetFileDialog(isFont, isVideoOnly);
                    if (picked != null) {
                        try {
                            String localRef = VizArchiveManager.importLocalAsset(picked);
                            props.putPropertyStringValue(key, localRef);
                            thumbCache.remove(localRef);
                            isModified = true;
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
                ImGui.sameLine();
                if (ImGui.button("Clear##" + key)) {
                    props.putPropertyStringValue(key, "");
                    isModified = true;
                }

                if (!isFont && !cur.isEmpty()) {
                    ThumbEntry thumb = getOrLoadThumbnail(cur);
                    if (thumb != null) {
                        ImGui.textDisabled(thumb.label);
                        if (thumb.texId != 0) {
                            float scale = Math.min(180f / Math.max(1, thumb.w), 96f / Math.max(1, thumb.h));
                            float tw = Math.max(16f, thumb.w * scale);
                            float th = Math.max(16f, thumb.h * scale);
                            ImGui.image(thumb.texId, tw, th, 0f, 0f, 1f, 1f);
                        }
                    }
                }
                return isModified;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("mvarf".equals(kind) || "mvarf2".equals(kind)) {
            boolean isModified = false;
            MVariableFloat mv = props.getPropertyMVariableFloat(key, MVariableFloat.CreateConstantFloat(0f));
            boolean is2d = "mvarf2".equals(kind);
            float min = parts.length >= 2 ? parseFloatSafe(parts[1], -10f) : -10f;
            float max = parts.length >= 3 ? parseFloatSafe(parts[2], 10f) : 10f;
            float speed = Math.max(0.002f, (max - min) * 0.003f);
            boolean mvarOpen;
            beginPropertyRowBg();
            try {
                mvarOpen = ImGui.treeNodeEx(key + "##mvar", ImGuiTreeNodeFlags.DefaultOpen);
            } finally {
                endPropertyRowBg();
            }
            if (mvarOpen) {
                String[] opts = is2d ? MeasureDefs.measures2dMVar : MeasureDefs.measures1dMVar;
                int count = mv.getMeasuredVarCount();
                for (int mIdx = 0; mIdx < count; mIdx++) {
                    ImGui.pushID(mIdx);
                    beginPropertyRowBg();
                    try {
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
                        if (ImGui.combo("Source", tmpCombo, opts)) {
                            curMeasure = opts[tmpCombo.get()];
                            rowChanged = true;
                        }
                        MeasureDefs.MeasureDef def = MeasureDefs.getMeasureDefByName(curMeasure);
                        boolean showB = (def == null) || is2d || def.isBUsedFor1d;
                        if (showB) {
                            String aHint = (def != null && !def.argAHint.isEmpty()) ? def.argAHint : "X";
                            String bHint = (def != null && !def.argBHint.isEmpty()) ? def.argBHint : "Y";
                            if (editFloat2Field("Amount (" + aHint + " / " + bHint + ")", uid + "/mv" + mIdx, tmpFloat2, speed, min, max)) {
                                rowChanged = true;
                            }
                        } else {
                            String aHint = (def != null && !def.argAHint.isEmpty()) ? def.argAHint : "Amount";
                            tmpFloat[0] = tmpFloat2[0];
                            if (editFloatField(aHint, uid + "/mv" + mIdx, tmpFloat, min, max)) {
                                tmpFloat2[0] = tmpFloat[0];
                                rowChanged = true;
                            }
                        }
                        if (rowChanged) {
                            mv.setMeasuredVar(mIdx, new MeasuredVar(curMeasure, tmpFloat2[0], tmpFloat2[1]));
                            props.putPropertyMVariableFloatValue(key, mv);
                            isModified = true;
                        }
                    } finally {
                        endPropertyRowBg();
                    }
                    ImGui.popID();
                }
                if (ImGui.button("+ Term##add_" + key)) {
                    props.putPropertyMVariableFloatValue(key, new MVariableFloat(mv, new MeasuredVar(MeasureDefs.Constant, min, min)));
                    isModified = true;
                }
                if (count > 1) {
                    ImGui.sameLine();
                    if (ImGui.button("- Term##rem_" + key)) {
                        props.putPropertyMVariableFloatValue(key, new MVariableFloat(mv, 0, count - 1));
                        isModified = true;
                    }
                }
                ImGui.treePop();
            }
            return isModified;
        }

        if ("mea".equals(kind)) {
            beginPropertyRowBg();
            try {
                MeasuredVar m = props.getPropertyMeasuredVar(key, MeasuredVar.AsNothing());
                if (m != null) {
                    String[] opts = MeasureDefs.measures1d;
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
                    boolean mod = false;
                    if (ImGui.combo(key + " Source", tmpCombo, opts)) {
                        curMeasure = opts[tmpCombo.get()];
                        mod = true;
                    }
                    if (editFloat2Field(key + " Arg", uid + "/arg", tmpFloat2, 0.01f, -10f, 10f)) {
                        mod = true;
                    }
                    if (mod) {
                        props.putPropertyMeasuredVar(key, new MeasuredVar(curMeasure, tmpFloat2[0], tmpFloat2[1]));
                        return true;
                    }
                }
                return false;
            } finally {
                endPropertyRowBg();
            }
        }

        if ("_child".equals(kind)) {
            CustomPropertiesList child = props.getChild(key);
            if (child != null) {
                boolean childChanged = false;
                String curType = child.getTypeName();
                String headerLabel = key + (curType.isEmpty() ? "" : (" [" + curType + "]"));
                boolean childOpen;
                beginPropertyRowBg();
                try {
                    childOpen = ImGui.treeNodeEx(headerLabel + "##child_" + depth + "_" + key,
                            depth == 0 ? ImGuiTreeNodeFlags.DefaultOpen : 0);
                } finally {
                    endPropertyRowBg();
                }
                if (childOpen) {
                    if (parts.length > 1) {
                        beginPropertyRowBg();
                        try {
                            String[] typeOpts = new String[parts.length - 1];
                            int selIdx = 0;
                            for (int i = 1; i < parts.length; i++) {
                                typeOpts[i - 1] = parts[i];
                                if (parts[i].equals(curType)) selIdx = i - 1;
                            }
                            tmpCombo.set(selIdx);
                            if (ImGui.combo("Type##" + key, tmpCombo, typeOpts)) {
                                child.putChildTypeValue(typeOpts[tmpCombo.get()]);
                                childChanged = true;
                            }
                        } finally {
                            endPropertyRowBg();
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

    private boolean editIntField(String label, String uid, int[] val, int min, int max) {
        String fieldUid = uid + "#0";
        currentFrameFieldUids.add(fieldUid);
        if (fieldUid.equals(inlineEditKey)) {
            if (inlineEditBufNeedsInit) {
                inlineEditBuf.set(Integer.toString(val[0]));
                inlineEditBufNeedsInit = false;
                inlineEditFocusFrames = 2;
            }
            if (inlineEditFocusFrames > 0) {
                ImGui.setKeyboardFocusHere();
                inlineEditFocusFrames--;
            }
            int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll;
            boolean entered = ImGui.inputText(label + "##edit_" + fieldUid, inlineEditBuf, flags);
            boolean esc = ImGui.isKeyPressed(ImGuiKey.Escape, false);
            boolean canCommit = (inlineEditFocusFrames == 0);
            boolean tab = canCommit
                    && (ImGui.isItemActive() || ImGui.isItemFocused() || ImGui.isItemDeactivated())
                    && ImGui.isKeyPressed(ImGuiKey.Tab, false);
            boolean deactivated = canCommit && (ImGui.isItemDeactivated() || ImGui.isItemDeactivatedAfterEdit());
            if (esc) {
                inlineEditKey = null;
                inlineEditBufNeedsInit = false;
                return false;
            }
            if (entered || tab || deactivated) {
                boolean changed = false;
                Integer parsed = parseEditedInt(inlineEditBuf.get());
                if (parsed != null && parsed.intValue() != val[0]) {
                    val[0] = parsed.intValue();
                    changed = true;
                }
                advanceOrClearInlineEdit(tab, fieldUid);
                return changed;
            }
            return false;
        }

        int step = computeNiceIntStep(min, max);
        int flags = ImGuiSliderFlags.NoInput;
        boolean ch = ImGui.sliderInt(label + "##" + fieldUid, val, min, max, "%d", flags);
        if (ch) {
            val[0] = quantizeInt(val[0], min, max, step);
        }
        handleInlineTrigger(fieldUid, Integer.toString(val[0]));
        return ch;
    }

    private boolean editFloatField(String label, String uid, float[] val, float min, float max) {
        return editSingleFloatSlot(label, uid + "#0", val, 0, min, max, computeNiceFloatStep(min, max));
    }

    private boolean editFloat2Field(String label, String uid, float[] val, float speed, float min, float max) {
        float totalW = ImGui.calcItemWidth();
        float spacing = ImGui.getStyle().getItemInnerSpacingX();
        float leftW = Math.max(44.0f, (totalW - spacing) * 0.5f);
        float rightW = Math.max(44.0f, totalW - leftW - spacing);
        float step = computeNiceFloatStep(min, max);
        boolean changed = false;

        ImGui.pushItemWidth(leftW);
        if (editSingleFloatSlot("##" + uid + "_0", uid + "#0", val, 0, min, max, step)) {
            changed = true;
        }
        ImGui.popItemWidth();

        ImGui.sameLine(0.0f, spacing);

        ImGui.pushItemWidth(rightW);
        if (editSingleFloatSlot("##" + uid + "_1", uid + "#1", val, 1, min, max, step)) {
            changed = true;
        }
        ImGui.popItemWidth();

        ImGui.sameLine(0.0f, spacing);
        ImGui.textUnformatted(label);
        return changed;
    }

    private boolean editSingleFloatSlot(String widgetLabel, String fieldUid, float[] val, int idx, float min, float max, float step) {
        currentFrameFieldUids.add(fieldUid);
        if (fieldUid.equals(inlineEditKey)) {
            if (inlineEditBufNeedsInit) {
                inlineEditBuf.set(formatFloatForEdit(val[idx], step));
                inlineEditBufNeedsInit = false;
                inlineEditFocusFrames = 2;
            }
            if (inlineEditFocusFrames > 0) {
                ImGui.setKeyboardFocusHere();
                inlineEditFocusFrames--;
            }
            int flags = ImGuiInputTextFlags.EnterReturnsTrue | ImGuiInputTextFlags.AutoSelectAll;
            String editLabel = widgetLabel.startsWith("##") ? ("##edit_" + fieldUid) : (widgetLabel + "##edit_" + fieldUid);
            boolean entered = ImGui.inputText(editLabel, inlineEditBuf, flags);
            boolean esc = ImGui.isKeyPressed(ImGuiKey.Escape, false);
            boolean canCommit = (inlineEditFocusFrames == 0);
            boolean tab = canCommit
                    && (ImGui.isItemActive() || ImGui.isItemFocused() || ImGui.isItemDeactivated())
                    && ImGui.isKeyPressed(ImGuiKey.Tab, false);
            boolean deactivated = canCommit && (ImGui.isItemDeactivated() || ImGui.isItemDeactivatedAfterEdit());
            if (esc) {
                inlineEditKey = null;
                inlineEditBufNeedsInit = false;
                return false;
            }
            if (entered || tab || deactivated) {
                boolean changed = false;
                Float parsed = parseEditedFloat(inlineEditBuf.get());
                if (parsed != null && Float.compare(parsed.floatValue(), val[idx]) != 0) {
                    val[idx] = parsed.floatValue();
                    changed = true;
                }
                advanceOrClearInlineEdit(tab, fieldUid);
                return changed;
            }
            return false;
        }

        tmpFloat[0] = val[idx];
        String fmt = step < 0.005f ? "%.4f" : "%.3f";
        String imguiLabel = widgetLabel.contains("##") ? widgetLabel : (widgetLabel + "##" + fieldUid);
        boolean ch = ImGui.sliderFloat(imguiLabel, tmpFloat, min, max, fmt, ImGuiSliderFlags.NoInput);
        if (ch) {
            float snapped = quantizeFloat(tmpFloat[0], min, max, step);
            if (Math.abs(snapped - val[idx]) > 1e-6f) {
                val[idx] = snapped;
            } else {
                ch = false;
            }
        }
        handleInlineTrigger(fieldUid, formatFloatForEdit(val[idx], step));
        return ch;
    }

    private void handleInlineTrigger(String fieldUid, String initialText) {
        if (ImGui.isItemHovered()) {
            if (!ImGui.isItemActive()) {
                ImGui.setTooltip("Double-click or Ctrl+Click to type (Tab saves & advances)");
            }
            if (ImGui.isMouseDoubleClicked(0) || (ImGui.isMouseClicked(0) && ImGui.getIO().getKeyCtrl())) {
                inlineEditKey = fieldUid;
                inlineEditBufNeedsInit = false;
                inlineEditBuf.set(initialText);
                inlineEditFocusFrames = 2;
            }
        }
    }

    private void advanceOrClearInlineEdit(boolean tabPressed, String currentUid) {
        if (tabPressed) {
            boolean reverse = ImGui.getIO().getKeyShift();
            String nextUid = findNextFieldUid(lastFrameFieldUids, currentUid, reverse);
            if (nextUid != null) {
                inlineEditKey = nextUid;
                inlineEditBufNeedsInit = true;
                inlineEditFocusFrames = 2;
                return;
            }
        }
        inlineEditKey = null;
        inlineEditBufNeedsInit = false;
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

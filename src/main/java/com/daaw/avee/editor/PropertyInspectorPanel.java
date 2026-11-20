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
import com.daaw.avee.comp.Visualizer.Elements.Base.MVariableFloat;
import com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.headless.VideoFrameDecoder;
import imgui.ImGui;
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
    private final ImString tmpStr = new ImString(512);

    public boolean render(String title, CustomPropertiesList props) {
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
        ImGui.separator();

        ImGui.pushItemWidth(Math.max(120.0f, ImGui.getContentRegionAvailX() * 0.56f));
        boolean changed = renderPropertiesGrouped(props, 0);
        ImGui.popItemWidth();
        return changed;
    }

    @SuppressWarnings("unchecked")
    private boolean renderPropertiesGrouped(CustomPropertiesList props, int depth) {
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
                if (renderProperty(props, key, depth)) {
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

    private boolean renderProperty(CustomPropertiesList props, String key, int depth) {
        String typeStr = props.getPropertyType(key);
        String[] parts = CustomPropertiesList.getPropertyTypeParts(typeStr);
        String kind = parts[0];

        if ("b".equals(kind)) {
            tmpBool.set(props.getPropertyBool(key, false));
            if (ImGui.checkbox(key, tmpBool)) {
                props.putPropertyBoolValue(key, tmpBool.get());
                return true;
            }
            return false;
        }

        if ("i".equals(kind)) {
            int min = parts.length >= 2 ? parseIntSafe(parts[1], 0) : 0;
            int max = parts.length >= 3 ? parseIntSafe(parts[2], 100) : 100;
            tmpInt[0] = props.getPropertyInt(key, min);
            if (ImGui.sliderInt(key, tmpInt, min, max)) {
                props.putPropertyIntValue(key, tmpInt[0]);
                return true;
            }
            return false;
        }

        if ("f".equals(kind)) {
            float min = parts.length >= 2 ? parseFloatSafe(parts[1], 0f) : 0f;
            float max = parts.length >= 3 ? parseFloatSafe(parts[2], 1f) : 1f;
            tmpFloat[0] = props.getPropertyFloat(key, min);
            if (ImGui.sliderFloat(key, tmpFloat, min, max, "%.3f")) {
                props.putPropertyFloatValue(key, tmpFloat[0]);
                return true;
            }
            return false;
        }

        if ("f2".equals(kind)) {
            float min = parts.length >= 2 ? parseFloatSafe(parts[1], -1f) : -1f;
            float max = parts.length >= 3 ? parseFloatSafe(parts[2], 1f) : 1f;
            Vec2f v = props.getPropertyVec2f(key, new Vec2f(0f, 0f));
            tmpFloat2[0] = v.x;
            tmpFloat2[1] = v.y;
            float speed = Math.max(0.002f, (max - min) * 0.003f);
            if (ImGui.dragFloat2(key, tmpFloat2, speed, min, max, "%.3f")) {
                props.putPropertyVec2fValue(key, new Vec2f(tmpFloat2[0], tmpFloat2[1]));
                return true;
            }
            return false;
        }

        if ("crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind)) {
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
        }

        if ("chsla4f".equals(kind) || "chsl4f".equals(kind) || "crgba4f".equals(kind) || "crgb4f".equals(kind)) {
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
        }

        if ("sel".equals(kind)) {
            String cur = props.getPropertyString(key, "");
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
                    props.putPropertyStringValue(key, opts[tmpCombo.get()]);
                    return true;
                }
            }
            return false;
        }

        if ("txt".equals(kind) || "ptxt".equals(kind)) {
            String cur = props.getPropertyString(key, "");
            tmpStr.set(cur != null ? cur : "");
            if (ImGui.inputText(key, tmpStr)) {
                props.putPropertyStringValue(key, tmpStr.get());
                return true;
            }
            return false;
        }

        if ("img".equals(kind) || "pimg".equals(kind) || "asset".equals(kind) || "passet".equals(kind)) {
            boolean isModified = false;
            String cur = props.getPropertyString(key, "");
            if (cur == null) cur = "";
            boolean isFont = "typeFace".equals(key);
            boolean isVideoOnly = "customVideo".equals(key);
            boolean isMask = "MaskImage".equals(key);

            ImGui.text(isMask ? (key + " (Alpha Mask)") : key);
            tmpStr.set(cur);
            if (ImGui.inputText("Path##path_" + key, tmpStr)) {
                props.putPropertyStringValue(key, tmpStr.get());
                isModified = true;
            }

            List<String> optList = buildMediaOptions(parts, isFont, isVideoOnly);
            if (!optList.isEmpty()) {
                String[] opts = new String[optList.size() + 1];
                opts[0] = "(select integrated / workspace media...)";
                int selIdx = 0;
                for (int i = 0; i < optList.size(); i++) {
                    opts[i + 1] = optList.get(i);
                    if (opts[i + 1].equals(cur)) selIdx = i + 1;
                }
                tmpCombo.set(selIdx);
                if (ImGui.combo("Media##preset_" + key, tmpCombo, opts) && tmpCombo.get() > 0) {
                    props.putPropertyStringValue(key, opts[tmpCombo.get()]);
                    isModified = true;
                }
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
        }

        if ("mvarf".equals(kind) || "mvarf2".equals(kind)) {
            boolean isModified = false;
            MVariableFloat mv = props.getPropertyMVariableFloat(key, MVariableFloat.CreateConstantFloat(0f));
            boolean is2d = "mvarf2".equals(kind);
            if (ImGui.treeNodeEx(key + "##mvar", ImGuiTreeNodeFlags.DefaultOpen)) {
                String[] opts = is2d ? MeasureDefs.measures2dMVar : MeasureDefs.measures1dMVar;
                int count = mv.getMeasuredVarCount();
                for (int mIdx = 0; mIdx < count; mIdx++) {
                    ImGui.pushID(mIdx);
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
                    if (ImGui.dragFloat2("Amount X/Y", tmpFloat2, 0.01f, -10f, 10f, "%.3f")) {
                        rowChanged = true;
                    }
                    if (rowChanged) {
                        mv.setMeasuredVar(mIdx, new MeasuredVar(curMeasure, tmpFloat2[0], tmpFloat2[1]));
                        props.putPropertyMVariableFloatValue(key, mv);
                        isModified = true;
                    }
                    ImGui.popID();
                }
                ImGui.treePop();
            }
            return isModified;
        }

        if ("mea".equals(kind)) {
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
                if (ImGui.dragFloat2(key + " Arg", tmpFloat2, 0.01f, -10f, 10f, "%.3f")) {
                    mod = true;
                }
                if (mod) {
                    props.putPropertyMeasuredVar(key, new MeasuredVar(curMeasure, tmpFloat2[0], tmpFloat2[1]));
                    return true;
                }
            }
            return false;
        }

        if ("_child".equals(kind)) {
            CustomPropertiesList child = props.getChild(key);
            if (child != null) {
                boolean childChanged = false;
                String curType = child.getTypeName();
                String headerLabel = key + (curType.isEmpty() ? "" : (" [" + curType + "]"));
                if (ImGui.treeNodeEx(headerLabel + "##child_" + depth + "_" + key,
                        depth == 0 ? ImGuiTreeNodeFlags.DefaultOpen : 0)) {
                    if (parts.length > 1) {
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
                    }
                    if (renderPropertiesGrouped(child, depth + 1)) {
                        childChanged = true;
                    }
                    ImGui.treePop();
                }
                return childChanged;
            }
        }
        return false;
    }

    private static List<String> buildMediaOptions(String[] parts, boolean isFont, boolean isVideoOnly) {
        Set<String> set = new LinkedHashSet<>();
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
            for (int i = 1; i < parts.length; i++) {
                set.add(parts[i]);
            }
        }
        if (!isFont && !isVideoOnly) {
            set.add("internalres:placeholderart");
        }
        return new ArrayList<>(set);
    }

    private ThumbEntry getOrLoadThumbnail(String path) {
        ThumbEntry cached = thumbCache.get(path);
        if (cached != null) return cached;
        ThumbEntry entry = new ThumbEntry();
        if (path.startsWith("composition:")) {
            entry.label = "[Composition reference]";
            thumbCache.put(path, entry);
            return entry;
        }
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
                    "ffmpeg", "-v", "error", "-ss", "0.1", "-i", f.getAbsolutePath(),
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

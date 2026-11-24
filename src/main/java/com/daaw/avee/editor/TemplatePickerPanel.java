package com.daaw.avee.editor;

import imgui.ImDrawList;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiWindowFlags;
import imgui.type.ImBoolean;
import imgui.type.ImString;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.imageio.ImageIO;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.system.MemoryUtil;

/**
 * Original AveePlayer-style template selection gallery displaying live/cached 16:9 previews,
 * search filtering, .viz import into private storage, and .viz export.
 */
public class TemplatePickerPanel {
    public interface HostCallbacks {
        void onSelectTemplate(String id);
        void onImportTemplate();
        void onExportTemplate(String id);
        void onDuplicateTemplate(String id);
        void onResetBuiltInTemplate(String id);
        void onDeleteTemplate(String id);
    }

    private static class ThumbTex {
        int texId;
        long loadedRevision;
    }

    private final Map<String, ThumbTex> glThumbs = new HashMap<>();
    private final ImBoolean openFlag = new ImBoolean(false);
    private final ImString filterBuf = new ImString(128);

    public void open() {
        openFlag.set(true);
    }

    public boolean isOpen() {
        return openFlag.get();
    }

    public void render(TemplateLibraryStore store, String activeId, HostCallbacks callbacks) {
        if (!openFlag.get() || store == null) return;

        ImGui.setNextWindowSize(860f, 600f, imgui.flag.ImGuiCond.FirstUseEver);
        int winFlags = ImGuiWindowFlags.NoCollapse;
        if (!ImGui.begin("Templates Library (AveePlayer Gallery)###TemplatesGalleryModal", openFlag, winFlags)) {
            ImGui.end();
            return;
        }

        if (ImGui.button("Import .viz / .json...")) {
            callbacks.onImportTemplate();
        }
        ImGui.sameLine();
        if (ImGui.button("Export Selected .viz...")) {
            callbacks.onExportTemplate(activeId);
        }
        ImGui.sameLine();
        if (ImGui.button("Duplicate")) {
            callbacks.onDuplicateTemplate(activeId);
        }
        TemplateLibraryStore.TemplateEntry activeEntry = store.getEntry(activeId);
        if (activeEntry != null) {
            ImGui.sameLine();
            if (activeEntry.builtIn) {
                if (ImGui.button("Reset Preset")) {
                    callbacks.onResetBuiltInTemplate(activeId);
                }
            } else {
                if (ImGui.button("Delete Imported")) {
                    callbacks.onDeleteTemplate(activeId);
                }
            }
        }
        ImGui.sameLine();
        ImGui.setNextItemWidth(200f);
        ImGui.inputTextWithHint("##tplFilter", "Search templates...", filterBuf);
        ImGui.sameLine();
        String saveBadge = store.isSavePending()
                ? "Auto-saving..."
                : ("Auto-saved (#" + store.getCompletedSaveCount() + ")");
        ImGui.textDisabled(saveBadge);
        ImGui.separator();

        String needle = filterBuf.get().trim().toLowerCase(Locale.ROOT);
        List<TemplateLibraryStore.TemplateEntry> entries = store.getEntries();

        ImGui.beginChild("##TemplatesGridScroll", 0f, -34f, false);
        float availW = Math.max(220f, ImGui.getContentRegionAvailX());
        float spacing = 12.0f;
        int cols = Math.max(2, Math.min(4, (int) ((availW + spacing) / 210.0f)));
        float cardW = Math.max(150f, (availW - spacing * (cols - 1) - 8.0f) / cols);
        float thumbW = cardW - 12.0f;
        float thumbH = thumbW * (9.0f / 16.0f);
        float cardH = thumbH + 52.0f;

        int shownIdx = 0;
        for (TemplateLibraryStore.TemplateEntry e : entries) {
            if (!needle.isEmpty() && !e.title.toLowerCase(Locale.ROOT).contains(needle)) {
                continue;
            }
            if (shownIdx > 0 && (shownIdx % cols) != 0) {
                ImGui.sameLine(0.0f, spacing);
            }
            boolean isActive = e.id.equals(activeId);
            ImGui.pushID("tpl_" + e.id);
            ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.ChildRounding, 6.0f);
            if (isActive) {
                ImGui.pushStyleColor(ImGuiCol.Border, 0.25f, 0.85f, 0.65f, 1.0f);
                ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.14f, 0.21f, 0.24f, 1.0f);
            } else {
                ImGui.pushStyleColor(ImGuiCol.Border, 0.28f, 0.30f, 0.36f, 0.90f);
                ImGui.pushStyleColor(ImGuiCol.ChildBg, 0.12f, 0.13f, 0.16f, 1.0f);
            }

            ImGui.beginChild("##card", cardW, cardH, true, ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoScrollWithMouse);
            float cardPosX = ImGui.getCursorScreenPosX();
            float cardPosY = ImGui.getCursorScreenPosY();

            int tex = ensureGlThumb(e);
            if (tex != 0) {
                ImGui.image(tex, thumbW, thumbH, 0f, 0f, 1f, 1f);
            } else {
                ImDrawList dl = ImGui.getWindowDrawList();
                dl.addRectFilled(cardPosX, cardPosY, cardPosX + thumbW, cardPosY + thumbH,
                        ImGui.getColorU32(0.08f, 0.09f, 0.11f, 1.0f), 4.0f);
                dl.addText(cardPosX + 10.0f, cardPosY + thumbH * 0.44f,
                        ImGui.getColorU32(0.55f, 0.60f, 0.68f, 1.0f), "Preview generating...");
                ImGui.dummy(thumbW, thumbH);
            }

            String badge = e.builtIn ? (e.modified ? "Built-in • Edited" : "Built-in") : "Imported";
            ImGui.text(e.title);
            ImGui.textDisabled(badge + (isActive ? "  [Active]" : ""));

            // Make full card clickable
            ImGui.setCursorScreenPos(cardPosX - 6f, cardPosY - 6f);
            if (ImGui.invisibleButton("##pickCard", cardW, cardH)) {
                callbacks.onSelectTemplate(e.id);
            }
            if (ImGui.isItemHovered() && ImGui.isMouseDoubleClicked(0)) {
                callbacks.onSelectTemplate(e.id);
                openFlag.set(false);
            }

            ImGui.endChild();
            ImGui.popStyleColor(2);
            ImGui.popStyleVar();
            ImGui.popID();
            shownIdx++;
        }
        ImGui.endChild();

        ImGui.separator();
        ImGui.textDisabled("Imported templates stay in app-private library storage; use Export .viz to share.");
        ImGui.sameLine(Math.max(120f, ImGui.getWindowWidth() - 95f));
        if (ImGui.button("Close", 80f, 0f)) {
            openFlag.set(false);
        }
        ImGui.end();
    }

    private int ensureGlThumb(TemplateLibraryStore.TemplateEntry entry) {
        if (!entry.previewFile.isFile()) return 0;
        ThumbTex cur = glThumbs.get(entry.id);
        if (cur != null && cur.texId != 0 && cur.loadedRevision == entry.previewRevision) {
            return cur.texId;
        }
        try {
            BufferedImage img = ImageIO.read(entry.previewFile);
            if (img == null) return cur != null ? cur.texId : 0;
            int w = img.getWidth();
            int h = img.getHeight();
            int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
            ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
            for (int px : argb) {
                buf.put((byte) ((px >> 16) & 0xFF));
                buf.put((byte) ((px >> 8) & 0xFF));
                buf.put((byte) (px & 0xFF));
                buf.put((byte) ((px >> 24) & 0xFF));
            }
            buf.flip();
            if (cur == null) {
                cur = new ThumbTex();
                cur.texId = GL11.glGenTextures();
                glThumbs.put(entry.id, cur);
            }
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, cur.texId);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, 0);
            MemoryUtil.memFree(buf);
            cur.loadedRevision = entry.previewRevision;
            return cur.texId;
        } catch (Throwable t) {
            return cur != null ? cur.texId : 0;
        }
    }

    public void dispose() {
        for (ThumbTex t : glThumbs.values()) {
            if (t.texId != 0) {
                GL11.glDeleteTextures(t.texId);
                t.texId = 0;
            }
        }
        glThumbs.clear();
    }
}

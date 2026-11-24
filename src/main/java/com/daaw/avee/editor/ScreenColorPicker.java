package com.daaw.avee.editor;

import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import imgui.ImDrawList;
import imgui.ImGui;
import imgui.ImGuiViewport;
import imgui.flag.ImGuiKey;
import imgui.flag.ImGuiMouseButton;
import imgui.flag.ImGuiMouseCursor;
import imgui.flag.ImGuiWindowFlags;
import java.awt.GraphicsEnvironment;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

/**
 * Screen-pixel eyedropper with a live zoomed pixel loupe shown to the right of the cursor.
 */
public class ScreenColorPicker {
    public static final int PATCH_RADIUS = 6;
    public static final int PATCH_SIZE = PATCH_RADIUS * 2 + 1;
    private static final float CELL_PX = 11.0f;
    private static final float BOX_PAD = 8.0f;
    private static final float FOOTER_H = 44.0f;

    private final int[] patchArgb = new int[PATCH_SIZE * PATCH_SIZE];
    private final ByteBuffer readBuf = ByteBuffer.allocateDirect(PATCH_SIZE * PATCH_SIZE * 4).order(ByteOrder.nativeOrder());

    private boolean active = false;
    private boolean justActivated = false;
    private CustomPropertiesList targetProps = null;
    private String targetKey = null;
    private String targetKind = null;
    private float preservedAlpha = 1.0f;

    private Robot awtRobot = null;
    private boolean awtRobotInitAttempted = false;

    public boolean isActive() {
        return active;
    }

    public boolean isTargeting(CustomPropertiesList props, String key) {
        return active && targetProps == props && key != null && key.equals(targetKey);
    }

    public void beginPick(CustomPropertiesList props, String key, String kind, float currentAlpha) {
        if (props == null || key == null || kind == null) return;
        this.targetProps = props;
        this.targetKey = key;
        this.targetKind = kind;
        this.preservedAlpha = currentAlpha > 0.01f ? Math.min(1.0f, currentAlpha) : 1.0f;
        this.active = true;
        this.justActivated = true;
        Arrays.fill(this.patchArgb, 0xFF000000);
        try {
            long ctx = GLFW.glfwGetCurrentContext();
            if (ctx != 0L) {
                captureFrontBufferPatch(ctx);
            }
        } catch (Throwable ignored) {
        }
    }

    public void cancel() {
        this.active = false;
        this.justActivated = false;
        this.targetProps = null;
        this.targetKey = null;
        this.targetKind = null;
    }

    public void captureFrontBufferPatch(long glfwWindow) {
        if (!active || glfwWindow == 0L) return;
        ImGuiViewport vp = ImGui.getMainViewport();
        float vpX = vp.getPosX();
        float vpY = vp.getPosY();
        float vpW = Math.max(1.0f, vp.getSizeX());
        float vpH = Math.max(1.0f, vp.getSizeY());

        int[] fbWArr = new int[1];
        int[] fbHArr = new int[1];
        GLFW.glfwGetFramebufferSize(glfwWindow, fbWArr, fbHArr);
        int fbW = Math.max(1, fbWArr[0]);
        int fbH = Math.max(1, fbHArr[0]);

        float mouseX = ImGui.getMousePosX();
        float mouseY = ImGui.getMousePosY();
        float relX = mouseX - vpX;
        float relY = mouseY - vpY;

        if (relX >= 0.0f && relY >= 0.0f && relX < vpW && relY < vpH) {
            float scaleX = fbW / vpW;
            float scaleY = fbH / vpH;
            int targetFbX = Math.max(0, Math.min(fbW - 1, Math.round(relX * scaleX)));
            int targetFbYFromTop = Math.max(0, Math.min(fbH - 1, Math.round(relY * scaleY)));
            int targetFbY = (fbH - 1) - targetFbYFromTop;
            readGlPatch(0, GL11.GL_FRONT, targetFbX, targetFbY, fbW, fbH, PATCH_RADIUS, patchArgb, readBuf);
        } else {
            captureDesktopFallback();
        }
    }

    private void captureDesktopFallback() {
        if (GraphicsEnvironment.isHeadless()) return;
        try {
            if (!awtRobotInitAttempted) {
                awtRobotInitAttempted = true;
                awtRobot = new Robot();
            }
            if (awtRobot == null || MouseInfo.getPointerInfo() == null) return;
            Point pt = MouseInfo.getPointerInfo().getLocation();
            BufferedImage shot = awtRobot.createScreenCapture(new Rectangle(pt.x - PATCH_RADIUS, pt.y - PATCH_RADIUS, PATCH_SIZE, PATCH_SIZE));
            for (int row = 0; row < PATCH_SIZE; row++) {
                for (int col = 0; col < PATCH_SIZE; col++) {
                    patchArgb[row * PATCH_SIZE + col] = (shot.getRGB(col, row) & 0x00FFFFFF) | 0xFF000000;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Reads a square (2*radius+1)x(2*radius+1) ARGB patch centered at OpenGL coords (targetFbX, targetFbY)
     * in top-to-bottom screen order (row 0 = top, col 0 = left) and returns the center ARGB pixel.
     */
    public static int readGlPatch(int readFbo, int readBuffer, int targetFbX, int targetFbY,
                                  int fbW, int fbH, int radius, int[] outArgb, ByteBuffer scratchBuf) {
        int size = radius * 2 + 1;
        Arrays.fill(outArgb, 0, Math.min(outArgb.length, size * size), 0xFF000000);
        if (fbW <= 0 || fbH <= 0) {
            return 0xFF000000;
        }
        int prevFbo = GL11.glGetInteger(GL30.GL_READ_FRAMEBUFFER_BINDING);
        int prevReadBuf = GL11.glGetInteger(GL11.GL_READ_BUFFER);
        int prevPackAlign = GL11.glGetInteger(GL11.GL_PACK_ALIGNMENT);
        try {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, readFbo);
            if (readBuffer != 0) {
                GL11.glReadBuffer(readBuffer);
            }
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);

            int minX = targetFbX - radius;
            int minY = targetFbY - radius;
            int x0 = Math.max(0, minX);
            int y0 = Math.max(0, minY);
            int x1 = Math.min(fbW, targetFbX + radius + 1);
            int y1 = Math.min(fbH, targetFbY + radius + 1);
            int rw = x1 - x0;
            int rh = y1 - y0;
            if (rw > 0 && rh > 0) {
                scratchBuf.clear();
                GL11.glReadPixels(x0, y0, rw, rh, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, scratchBuf);
                for (int gy = 0; gy < rh; gy++) {
                    int fbY = y0 + gy;
                    int outRow = (targetFbY + radius) - fbY;
                    if (outRow < 0 || outRow >= size) continue;
                    for (int gx = 0; gx < rw; gx++) {
                        int fbX = x0 + gx;
                        int outCol = fbX - minX;
                        if (outCol < 0 || outCol >= size) continue;
                        int pOff = (gy * rw + gx) * 4;
                        int r = scratchBuf.get(pOff) & 0xFF;
                        int g = scratchBuf.get(pOff + 1) & 0xFF;
                        int b = scratchBuf.get(pOff + 2) & 0xFF;
                        outArgb[outRow * size + outCol] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        } finally {
            GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, prevPackAlign);
            if (readFbo == 0 && prevReadBuf != 0) {
                GL11.glReadBuffer(prevReadBuf);
            }
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, prevFbo);
        }
        return outArgb[radius * size + radius];
    }

    /**
     * Positions the zoomed magnifier preview immediately to the right of the cursor (flipping left
     * only if the right viewport boundary would clip it) and clamps Y vertically inside the viewport.
     */
    public static float[] computeLoupeRect(float cursorX, float cursorY,
                                           float vpX, float vpY, float vpW, float vpH,
                                           float boxW, float boxH) {
        float offsetX = 18.0f;
        float x = cursorX + offsetX;
        if (x + boxW > vpX + vpW - 4.0f) {
            x = Math.max(vpX + 4.0f, cursorX - boxW - offsetX);
        }
        float minY = vpY + 4.0f;
        float maxY = Math.max(minY, vpY + vpH - boxH - 4.0f);
        float y = Math.max(minY, Math.min(maxY, cursorY - boxH * 0.5f));
        return new float[]{x, y, boxW, boxH};
    }

    public static boolean applyPickedColor(CustomPropertiesList props, String key, String kind,
                                           int pickedArgb, float preservedAlpha) {
        if (props == null || key == null || kind == null) return false;
        boolean hasAlpha = "crgba".equals(kind) || "crgba4f".equals(kind) || "chsla4f".equals(kind);
        float alpha = hasAlpha ? (preservedAlpha > 0.01f ? Math.min(1.0f, preservedAlpha) : 1.0f) : 1.0f;
        int finalArgb = (pickedArgb & 0x00FFFFFF) | ((Math.round(alpha * 255.0f) & 0xFF) << 24);

        if ("crgba".equals(kind) || "crgb".equals(kind) || "crgb_hl".equals(kind)) {
            props.putPropertyIntValue(key, finalArgb);
            return true;
        }
        if ("chsla4f".equals(kind) || "chsl4f".equals(kind) || "crgba4f".equals(kind) || "crgb4f".equals(kind)) {
            float[] out4 = new float[4];
            if ("chsla4f".equals(kind) || "chsl4f".equals(kind)) {
                GraphicsUtils.intColorToHlsa1(out4, finalArgb);
            } else {
                GraphicsUtils.intColorToF4Color(out4, finalArgb);
            }
            props.putPropertyArray4fValue(key, out4);
            return true;
        }
        return false;
    }

    /**
     * Renders the transparent click-blocking overlay + cursor crosshair + right-side pixel loupe.
     * Returns true when a pixel color was committed to the target property.
     */
    public boolean renderOverlay(Runnable onChanged) {
        if (!active) return false;

        ImGuiViewport vp = ImGui.getMainViewport();
        float vpX = vp.getPosX();
        float vpY = vp.getPosY();
        float vpW = Math.max(1.0f, vp.getSizeX());
        float vpH = Math.max(1.0f, vp.getSizeY());

        ImGui.setNextWindowPos(vpX, vpY);
        ImGui.setNextWindowSize(vpW, vpH);
        int blockerFlags = ImGuiWindowFlags.NoTitleBar | ImGuiWindowFlags.NoResize | ImGuiWindowFlags.NoMove
                | ImGuiWindowFlags.NoScrollbar | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoBackground
                | ImGuiWindowFlags.NoNav;
        ImGui.pushStyleVar(imgui.flag.ImGuiStyleVar.WindowPadding, 0.0f, 0.0f);
        ImGui.begin("##ScreenColorPickerOverlay", blockerFlags);
        ImGui.invisibleButton("##ScreenColorPickerCatch", vpW, vpH);
        ImGui.end();
        ImGui.popStyleVar();

        float cursorX = ImGui.getMousePosX();
        float cursorY = ImGui.getMousePosY();
        ImGui.setMouseCursor(ImGuiMouseCursor.None);

        ImDrawList dl = ImGui.getForegroundDrawList();
        // High-contrast crosshair with open center pixel
        int outlineCol = ImGui.getColorU32(0.0f, 0.0f, 0.0f, 0.92f);
        int crossCol = ImGui.getColorU32(1.0f, 1.0f, 1.0f, 0.96f);
        dl.addLine(cursorX - 11.0f, cursorY, cursorX - 3.0f, cursorY, outlineCol, 3.0f);
        dl.addLine(cursorX + 3.0f, cursorY, cursorX + 11.0f, cursorY, outlineCol, 3.0f);
        dl.addLine(cursorX, cursorY - 11.0f, cursorX, cursorY - 3.0f, outlineCol, 3.0f);
        dl.addLine(cursorX, cursorY + 3.0f, cursorX, cursorY + 11.0f, outlineCol, 3.0f);
        dl.addLine(cursorX - 10.0f, cursorY, cursorX - 3.0f, cursorY, crossCol, 1.2f);
        dl.addLine(cursorX + 3.0f, cursorY, cursorX + 10.0f, cursorY, crossCol, 1.2f);
        dl.addLine(cursorX, cursorY - 10.0f, cursorX, cursorY - 3.0f, crossCol, 1.2f);
        dl.addLine(cursorX, cursorY + 3.0f, cursorX, cursorY + 10.0f, crossCol, 1.2f);

        // Loupe preview window immediately to the right of cursor
        float gridSize = PATCH_SIZE * CELL_PX;
        float boxW = gridSize + BOX_PAD * 2.0f;
        float boxH = gridSize + FOOTER_H + BOX_PAD * 2.0f;
        float[] rect = computeLoupeRect(cursorX, cursorY, vpX, vpY, vpW, vpH, boxW, boxH);
        float bx = rect[0];
        float by = rect[1];

        dl.addRectFilled(bx + 2.0f, by + 2.0f, bx + boxW + 3.0f, by + boxH + 3.0f,
                ImGui.getColorU32(0.0f, 0.0f, 0.0f, 0.45f), 6.0f);
        dl.addRectFilled(bx, by, bx + boxW, by + boxH,
                ImGui.getColorU32(0.11f, 0.12f, 0.15f, 0.96f), 6.0f);
        dl.addRect(bx, by, bx + boxW, by + boxH,
                ImGui.getColorU32(0.75f, 0.80f, 0.88f, 0.90f), 6.0f, 0, 1.5f);

        float gx = bx + BOX_PAD;
        float gy = by + BOX_PAD;
        int gridLineCol = ImGui.getColorU32(0.5f, 0.5f, 0.5f, 0.28f);
        for (int row = 0; row < PATCH_SIZE; row++) {
            float cy0 = gy + row * CELL_PX;
            float cy1 = cy0 + CELL_PX;
            for (int col = 0; col < PATCH_SIZE; col++) {
                int argb = patchArgb[row * PATCH_SIZE + col];
                float r = ((argb >> 16) & 0xFF) / 255.0f;
                float g = ((argb >> 8) & 0xFF) / 255.0f;
                float b = (argb & 0xFF) / 255.0f;
                float cx0 = gx + col * CELL_PX;
                float cx1 = cx0 + CELL_PX;
                dl.addRectFilled(cx0, cy0, cx1, cy1, ImGui.getColorU32(r, g, b, 1.0f));
                dl.addRect(cx0, cy0, cx1, cy1, gridLineCol, 0.0f, 0, 1.0f);
            }
        }

        // Center pixel reticle
        float centerX0 = gx + PATCH_RADIUS * CELL_PX;
        float centerY0 = gy + PATCH_RADIUS * CELL_PX;
        dl.addRect(centerX0 - 1.0f, centerY0 - 1.0f, centerX0 + CELL_PX + 1.0f, centerY0 + CELL_PX + 1.0f,
                outlineCol, 0.0f, 0, 2.0f);
        dl.addRect(centerX0, centerY0, centerX0 + CELL_PX, centerY0 + CELL_PX,
                crossCol, 0.0f, 0, 1.5f);

        // Footer: color swatch + hex + RGB + hint
        int centerArgb = patchArgb[PATCH_RADIUS * PATCH_SIZE + PATCH_RADIUS];
        int cr = (centerArgb >> 16) & 0xFF;
        int cg = (centerArgb >> 8) & 0xFF;
        int cb = centerArgb & 0xFF;

        float fy = gy + gridSize + 6.0f;
        dl.addRectFilled(gx, fy, gx + 26.0f, fy + 26.0f,
                ImGui.getColorU32(cr / 255.0f, cg / 255.0f, cb / 255.0f, 1.0f), 3.0f);
        dl.addRect(gx, fy, gx + 26.0f, fy + 26.0f, crossCol, 3.0f, 0, 1.0f);

        String hexLine = String.format(Locale.US, "#%02X%02X%02X (%d,%d,%d)", cr, cg, cb, cr, cg, cb);
        dl.addText(gx + 33.0f, fy, ImGui.getColorU32(0.96f, 0.97f, 0.99f, 1.0f), hexLine);
        dl.addText(gx + 33.0f, fy + 15.0f, ImGui.getColorU32(0.70f, 0.75f, 0.82f, 1.0f),
                "Click: pick  Esc/RMB: cancel");

        if (ImGui.isKeyPressed(ImGuiKey.Escape, false) || ImGui.isMouseClicked(ImGuiMouseButton.Right)) {
            cancel();
            return false;
        }
        if (!justActivated && ImGui.isMouseClicked(ImGuiMouseButton.Left)) {
            boolean applied = applyPickedColor(targetProps, targetKey, targetKind, centerArgb, preservedAlpha);
            cancel();
            if (applied && onChanged != null) {
                onChanged.run();
            }
            return applied;
        }
        justActivated = false;
        return false;
    }
}

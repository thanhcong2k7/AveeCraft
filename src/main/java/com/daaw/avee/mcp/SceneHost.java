package com.daaw.avee.mcp;

import com.daaw.avee.comp.Visualizer.CustomScene;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import com.daaw.avee.editor.DesktopAudioEngine;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.concurrent.CompletableFuture;

public interface SceneHost {
    String getMode();

    boolean isReadOnly();

    CustomScene getScene();

    void setScene(CustomScene scene, boolean rebuildElements);

    void refreshLiveScene();

    RendererCore getRendererCore();

    DesktopAudioEngine getAudioEngine();

    int getSelectedCompIdx();

    int getSelectedElemIdx();

    void setSelection(int compIdx, int elemIdx, boolean triggerHighlight);

    int getPreviewWidth();

    int getPreviewHeight();

    void setPreviewSize(int w, int h);

    File getProjectFile();

    void setProjectFile(File file);

    int getActivePresetIndex();

    void setActivePresetIndex(int idx);

    void setStatusMessage(String msg);

    BufferedImage capturePreviewImage(int advanceFrames, long seekMs);

    void captureWindowImageAsync(CompletableFuture<BufferedImage> future);
}

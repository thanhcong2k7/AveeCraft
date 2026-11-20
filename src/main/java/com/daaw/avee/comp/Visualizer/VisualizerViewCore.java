package com.daaw.avee.comp.Visualizer;

import android.content.Context;
import com.daaw.avee.Common.Events.WeakEvent;
import com.daaw.avee.Common.Events.WeakEvent2;
import com.daaw.avee.Common.Events.WeakEvent3;
import com.daaw.avee.Common.Events.WeakEvent4;
import com.daaw.avee.Common.Events.WeakEventR;
import com.daaw.avee.Common.Events.WeakEventR1;
import com.daaw.avee.Common.Events.WeakEventR2;
import com.daaw.avee.Common.Events.WeakEventR5;
import com.daaw.avee.Common.Vec2f;
import com.daaw.avee.comp.AlbumArt.AlbumArtRequest;
import com.daaw.avee.comp.AlbumArt.AlbumArtRequest2;
import com.daaw.avee.comp.AlbumArt.ImageLoadedListener;
import com.daaw.avee.comp.Common.MeterOutputData;
import com.daaw.avee.comp.Common.TrackDurationInfo;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
import com.daaw.avee.comp.Visualizer.Graphic.RendererCore;
import com.daaw.avee.comp.playback.AudioFrameData;
import mdesl.graphics.Texture;

public class VisualizerViewCore {
    public static WeakEventR1<Integer, AudioFrameData> onRequestsSoundVisualizationData = new WeakEventR1<>();
    public static WeakEventR2<String, VisualizerViewCore, String> onRequestMeasureText = new WeakEventR2<>();
    public static WeakEventR5<String, Vec2f, Vec2f, MeterOutputData, IRenderState, Vec2f> onRequestMeasureVec2f = new WeakEventR5<>();
    public static WeakEventR<AlbumArtRequest> onRequestsAlbumArtPath = new WeakEventR<>();
    public static WeakEvent2<ImageLoadedListener, AlbumArtRequest2> onRequestAlbumArtPathAndBitmap = new WeakEvent2<>();
    public static WeakEventR<CustomScene> onRequestSelectedSkinThemePreset = new WeakEventR<>();
    public static WeakEvent onThemeChanged = new WeakEvent();
    public static WeakEvent2<Float, Float> onFrameStart = new WeakEvent2<>();
    public static WeakEventR<FrameCaptureDesc> onRequestFrameCaptureMode = new WeakEventR<>();
    public static WeakEvent4<int[], Integer, Integer, int[]> onFrameCaptured = new WeakEvent4<>();
    public static WeakEventR<Boolean> onRequestUseHighQualityBlur = new WeakEventR<>();
    public static WeakEventR<Integer> onRequestOverrideAudioFrameSamplesInCount = new WeakEventR<>();
    public static WeakEvent3<Texture, Boolean, String> onOffScreenContentRendered = new WeakEvent3<>();
    public static WeakEventR1<Integer, Integer> onRequestConvertDpToPx = new WeakEventR1<>();
    public static WeakEventR1<Context, Integer> onRequestRenderQualityEdgesValue = new WeakEventR1<>();
    public static WeakEventR1<Context, Integer> onRequestAudioProcessMultiThreadValue = new WeakEventR1<>();
    public static WeakEventR1<Context, TrackDurationInfo> onRequestTrackDurationInfo = new WeakEventR1<>();

    private RendererCore renderer;

    public RendererCore getRenderer() {
        return renderer;
    }

    public void setRenderer(RendererCore renderer) {
        this.renderer = renderer;
    }
}

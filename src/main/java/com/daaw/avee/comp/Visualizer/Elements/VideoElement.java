package com.daaw.avee.comp.Visualizer.Elements;

import android.graphics.RectF;
import com.daaw.avee.Common.Action3;
import com.daaw.avee.Common.Utils;
import com.daaw.avee.Common.Vec2f;
import com.daaw.avee.Design.AppDesign;
import com.daaw.avee.Design.VisualizerExporterDesign;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Elements.Base.Element;
import com.daaw.avee.comp.Visualizer.Elements.Base.ElementImageLoader;
import com.daaw.avee.comp.Visualizer.Elements.Base.ImageMask;
import com.daaw.avee.comp.Visualizer.Elements.Base.MVariableFloat;
import com.daaw.avee.comp.Visualizer.Elements.Base.MeasuredVar;
import com.daaw.avee.comp.Visualizer.Graphic.AtlasTexture;
import com.daaw.avee.comp.Visualizer.Graphic.GraphicsUtils;
import com.daaw.avee.comp.Visualizer.Graphic.IAtlasTexture;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
import com.daaw.avee.comp.Visualizer.Graphic.RenderPassData;
import com.daaw.avee.comp.Visualizer.Graphic.RenderState;
import com.daaw.avee.comp.Visualizer.Graphic.VFrameBuffer;
import com.daaw.avee.comp.Visualizer.Graphic.VMatrix;
import com.daaw.avee.comp.Visualizer.Graphic.VShaderBinder;
import com.daaw.avee.comp.Visualizer.Graphic.VShaderProgram;
import com.daaw.avee.comp.Visualizer.ICompositionDependencies;
import com.daaw.avee.comp.Visualizer.IDependencyResourceCounter;
import com.daaw.avee.comp.Visualizer.Meter;
import com.daaw.avee.headless.VideoFrameDecoder;
import mdesl.graphics.glutils.FrameBuffer;

public class VideoElement extends Element {
    public static final String typeName = "Video";

    private String customVideoPath = "";
    private boolean loop = true;
    private boolean syncToTrack = true;
    private float playbackSpeed = 1.0f;
    private int startOffsetMs = 0;
    private boolean keepAspectRatio = true;
    private boolean cropToFit = false;

    private float[] hslaColorFrom = {1.0f, 1.0f, 1.0f, 1.0f};
    private float[] hslaColorTo = {1.0f, 1.0f, 1.0f, 1.0f};
    private MeasuredVar measureColor = MeasuredVar.AsNothing();
    private MVariableFloat saturationVar = MVariableFloat.CreateConstantFloat(1.0f);

    private ElementImageLoader maskImageLoader;
    private int maskMode = 0;

    private final float[] colorHslaTmp = new float[4];
    private final VMatrix vpMatTmp = new VMatrix();

    private VideoFrameDecoder videoDecoder;
    private String loadedDecoderPath = null;
    private float previewElapsedSec = 0.0f;
    private boolean wasExporting = false;

    private final Action3<RenderState, VShaderProgram, RenderPassData> renderBlurShaderOnBindAction =
            new Action3<RenderState, VShaderProgram, RenderPassData>() {
                @Override
                public void onInvoke(RenderState renderState, VShaderProgram vShaderProgram, RenderPassData renderPassData) {
                    vShaderProgram.setUniformMatrix("u_projView", false, VideoElement.this.vpMatTmp.getObj());
                    vShaderProgram.setUniformf("Color2", 0.0f, 0.0f, 0.0f, 1.0f);
                    vShaderProgram.setUniformf("saturation",
                            VideoElement.this.saturationVar.getValueAsFloat(renderState.res.meter));
                    if (VideoElement.this.maskMode == 1) {
                        vShaderProgram.setUniformf("maskadd", 0.0f);
                        vShaderProgram.setUniformf("maskmul", 1.0f);
                        vShaderProgram.setUniformf("mask_l_add", 0.0f);
                        vShaderProgram.setUniformf("mask_l_mul", 1.0f);
                    } else if (VideoElement.this.maskMode == 2) {
                        vShaderProgram.setUniformf("maskadd", 0.0f);
                        vShaderProgram.setUniformf("maskmul", 1.0f);
                        vShaderProgram.setUniformf("mask_l_add", 1.0f);
                        vShaderProgram.setUniformf("mask_l_mul", -1.0f);
                    } else if (VideoElement.this.maskMode == 3) {
                        vShaderProgram.setUniformf("maskadd", 1.0f);
                        vShaderProgram.setUniformf("maskmul", -1.0f);
                        vShaderProgram.setUniformf("mask_l_add", 1.0f);
                        vShaderProgram.setUniformf("mask_l_mul", 0.0f);
                    } else {
                        vShaderProgram.setUniformf("maskadd", 0.0f);
                        vShaderProgram.setUniformf("maskmul", 1.0f);
                        vShaderProgram.setUniformf("mask_l_add", 1.0f);
                        vShaderProgram.setUniformf("mask_l_mul", 0.0f);
                    }
                    if (renderPassData.getTextureIsFlipped(0) == renderPassData.getTextureIsFlipped(1)) {
                        vShaderProgram.setUniformf("tex2_y_add", 0.0f);
                        vShaderProgram.setUniformf("tex2_y_mul", 1.0f);
                    } else {
                        vShaderProgram.setUniformf("tex2_y_add", 1.0f);
                        vShaderProgram.setUniformf("tex2_y_mul", -1.0f);
                    }
                }
            };

    public VideoElement() {
        super(0, 1.0f, 1.0f);
        setScale(1.0f, 1.0f);
        this.maskImageLoader = new ElementImageLoader(new ElementImageLoader.IOnNeedReCreateGLResources() {
            @Override
            public void markNeedReCreateGLResources() {
                VideoElement.this.markNeedReCreateGLResourcesDontOverride();
            }
        }, null, null, null);
    }

    @Override
    public String getElementTypeName() {
        return typeName;
    }

    public void setCustomVideo(String path) {
        String normalized = path != null ? path : "";
        if (!normalized.equals(this.customVideoPath)) {
            this.customVideoPath = normalized;
            releaseDecoder();
            this.previewElapsedSec = 0.0f;
            markNeedReCreateGLResources();
        }
    }

    public String getCustomVideo() {
        return this.customVideoPath;
    }

    private void releaseDecoder() {
        if (this.videoDecoder != null) {
            this.videoDecoder.release();
            this.videoDecoder = null;
        }
        this.loadedDecoderPath = null;
    }

    @Override
    protected void onApplyCustomization(CustomPropertiesList customPropertiesList) {
        super.onApplyCustomization(customPropertiesList);
        super.onApplyCustomizationAdditional(customPropertiesList);

        setCustomVideo(customPropertiesList.getPropertyString("customVideo", ""));
        this.loop = customPropertiesList.getPropertyBool("loop", true);
        this.syncToTrack = customPropertiesList.getPropertyBool("syncToTrack", true);
        this.playbackSpeed = Math.max(0.05f, customPropertiesList.getPropertyFloat("playbackSpeed", 1.0f));
        this.startOffsetMs = customPropertiesList.getPropertyInt("startOffsetMs", 0);
        this.keepAspectRatio = customPropertiesList.getPropertyBool("keepAspectRatio", true);
        this.cropToFit = customPropertiesList.getPropertyBool("cropToFit", false);

        this.hslaColorFrom = customPropertiesList.getPropertyArray4f("Color", new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        this.hslaColorTo = customPropertiesList.getPropertyArray4f("ColorTo", new float[]{1.0f, 1.0f, 1.0f, 1.0f});
        this.measureColor.onApplyCustomization(customPropertiesList.getChild("MeasureColorBlend"), "Nothing", 0.5f, 0.5f);
        this.saturationVar = customPropertiesList.getPropertyMVariableFloat("saturation", MVariableFloat.CreateConstantFloat(1.0f));

        this.maskImageLoader.setCustomImage(customPropertiesList.getPropertyString("MaskImage", null));
        this.maskMode = customPropertiesList.getPropertySelectStringIndex("maskMode", ImageMask.maskModes, 0);
    }

    @Override
    protected void onReadCustomization(CustomPropertiesList customPropertiesList, IDependencyResourceCounter iDependencyResourceCounter) {
        super.onReadCustomization(customPropertiesList, iDependencyResourceCounter);
        super.onReadCustomizationAdditional(customPropertiesList);
        customPropertiesList.setCustomizationName(typeName);

        if (iDependencyResourceCounter != null) {
            iDependencyResourceCounter.PutDependencyResourceName(this.customVideoPath);
            iDependencyResourceCounter.PutDependencyResourceName(this.maskImageLoader.getCustomImagePath());
        }

        customPropertiesList.putPropertyStringAsImage("customVideo", this.customVideoPath, "1_video", new String[0]);
        customPropertiesList.putPropertyBool("loop", this.loop, "1_video");
        customPropertiesList.putPropertyBool("syncToTrack", this.syncToTrack, "1_video");
        customPropertiesList.putPropertyFloatAsRange("playbackSpeed", this.playbackSpeed, "1_video", 0.1f, 4.0f);
        customPropertiesList.putPropertyIntAsRange("startOffsetMs", this.startOffsetMs, "1_video", 0, 300000);
        customPropertiesList.putPropertyBool("keepAspectRatio", this.keepAspectRatio, "1_video");
        customPropertiesList.putPropertyBool("cropToFit", this.cropToFit, "1_video");

        customPropertiesList.putPropertyStringAsImage("MaskImage", this.maskImageLoader.getCustomImagePath(), "1_video", ElementImageLoader.internalMaskImages);
        customPropertiesList.putSelectString("maskMode", Utils.IndexToString(ImageMask.maskModes, this.maskMode, ImageMask.maskModes[0]), "1_video", ImageMask.maskModes);

        customPropertiesList.putPropertyArray4fAsCHSLA("Color", this.hslaColorFrom, "2_color");
        customPropertiesList.putPropertyArray4fAsCHSLA("ColorTo", this.hslaColorTo, "2_color");
        this.measureColor.onReadCustomization1D(customPropertiesList.putEmptyChild("MeasureColorBlend", "", "2_color", new String[0]));
        customPropertiesList.putPropertyMVariableFloat("saturation", this.saturationVar, "2_color", 0.0f, 1.0f);
    }

    @Override
    protected void onDestroyGLResources(RenderState renderState) {
        super.onDestroyGLResources(renderState);
        releaseDecoder();
        if (this.maskImageLoader != null) {
            this.maskImageLoader.onDestroyGLResources(renderState);
        }
    }

    @Override
    protected void markNeedReCreateGLResources() {
        super.markNeedReCreateGLResources();
        if (this.maskImageLoader != null) {
            this.maskImageLoader.markNeedReCreateGLResources();
        }
    }

    @Override
    protected boolean onCreateGLResources(RenderState renderState) {
        super.onCreateGLResources(renderState);
        this.maskImageLoader.onCreateGLResources(renderState, measureDrawRectMaxScaleValues(renderState.res.meter), 0);
        return false;
    }

    @Override
    protected void onCreateGradualGLResources(RenderState renderState, int i) {
        super.onCreateGradualGLResources(renderState, i);
        this.maskImageLoader.onCreateGradualGLResources(renderState, i);
    }

    @Override
    public void onEarlyUpdate(IRenderState iRenderState, FrameBuffer frameBuffer, ICompositionDependencies iCompositionDependencies) {
        super.onEarlyUpdate(iRenderState, frameBuffer, iCompositionDependencies);
        this.maskImageLoader.onEarlyUpdate(iRenderState, frameBuffer, iCompositionDependencies);
    }

    private int getCurrentColorInt(Meter meter) {
        float t = Math.max(0.0f, Math.min(1.0f, this.measureColor.measure(meter, 0.0f)));
        Utils.lerpHsla(this.hslaColorFrom, this.hslaColorTo, this.colorHslaTmp, t);
        return GraphicsUtils.hlsa1ColorToInt(this.colorHslaTmp);
    }

    @Override
    public void onRender(RenderState renderState, FrameBuffer frameBuffer) {
        super.onRender(renderState, frameBuffer);
        this.maskImageLoader.onRender(renderState, frameBuffer);

        if (this.customVideoPath == null || this.customVideoPath.trim().isEmpty()) {
            return;
        }

        if (this.videoDecoder == null || !this.customVideoPath.equals(this.loadedDecoderPath)) {
            releaseDecoder();
            this.videoDecoder = new VideoFrameDecoder(this.customVideoPath);
            this.loadedDecoderPath = this.customVideoPath;
        }

        boolean exporting = VisualizerExporterDesign.isExporting();
        if (exporting != this.wasExporting) {
            this.wasExporting = exporting;
            this.previewElapsedSec = 0.0f;
        }

        double baseMs;
        if (this.syncToTrack) {
            baseMs = Math.max(0L, AppDesign.getTrackPositionForRendering());
        } else {
            this.previewElapsedSec += Math.max(0.0f, renderState.getFrameTimeF());
            baseMs = this.previewElapsedSec * 1000.0;
        }
        long targetUs = Math.max(0L, Math.round(baseMs * this.playbackSpeed * 1000.0) + (this.startOffsetMs * 1000L));

        VFrameBuffer videoFbo = this.videoDecoder.getFrameAtTime(renderState, targetUs, this.loop);
        renderState.bindFrameBuffer(frameBuffer);
        renderState.setBlendModeForce(getBlendMode());

        if (videoFbo == null || videoFbo.getTexture() == null) {
            return;
        }

        IAtlasTexture videoTexture = new AtlasTexture(videoFbo.getTexture(), false);
        RectF measureDrawRect = measureDrawRect(renderState.res.meter);
        float measureDrawRot = measureDrawRot(renderState.res.meter);
        int currentColorInt = getCurrentColorInt(renderState.res.meter);
        createRotationAndVpMat(renderState, this.vpMatTmp, measureDrawRect.centerX(), measureDrawRect.centerY(), measureDrawRot);

        VShaderBinder shaderBinder = renderState.res.getBufferRenderer_atlasBufferFxLight();
        RenderPassData renderPassData = new RenderPassData(
                getBlendMode(),
                new IAtlasTexture[]{videoTexture, this.maskImageLoader.getTexture(renderState)},
                shaderBinder,
                this.renderBlurShaderOnBindAction,
                1);

        float drawW = measureDrawRect.width();
        float drawH = measureDrawRect.height();
        float drawX = measureDrawRect.left;
        float drawY = measureDrawRect.top;
        Vec2f uv0 = Vec2f.zero();
        Vec2f uv1 = Vec2f.one();

        float rectRatio = drawW / Math.max(1.0f, drawH);
        float vidRatio = (float) this.videoDecoder.getWidth() / (float) Math.max(1, this.videoDecoder.getHeight());

        if (this.cropToFit && drawW > 0.0f && drawH > 0.0f) {
            if (vidRatio > rectRatio) {
                float keepU = rectRatio / vidRatio;
                float padU = (1.0f - keepU) * 0.5f;
                uv0 = new Vec2f(padU, 0.0f);
                uv1 = new Vec2f(1.0f - padU, 1.0f);
            } else if (vidRatio < rectRatio) {
                float keepV = vidRatio / rectRatio;
                float padV = (1.0f - keepV) * 0.5f;
                uv0 = new Vec2f(0.0f, padV);
                uv1 = new Vec2f(1.0f, 1.0f - padV);
            }
        } else if (this.keepAspectRatio && drawW > 0.0f && drawH > 0.0f) {
            if (vidRatio > rectRatio) {
                drawH = drawW / vidRatio;
            } else {
                drawW = drawH * vidRatio;
            }
            drawX = measureDrawRect.centerX() - (drawW * 0.5f);
            drawY = measureDrawRect.centerY() - (drawH * 0.5f);
        }

        renderState.res.getBufferRenderer().drawRectangleWHBottom(
                renderState,
                drawX,
                drawY,
                0.0f,
                drawW,
                drawH,
                currentColorInt,
                uv0,
                uv1,
                renderPassData,
                false);
    }
}

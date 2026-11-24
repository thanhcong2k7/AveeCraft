package com.daaw.avee.comp.Visualizer.Elements;

import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Elements.Base.Element;
import com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentDataProvider;
import com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAudioSpectrumData;
import com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentDataProviderFactory;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
import com.daaw.avee.comp.Visualizer.Graphic.RenderState;
import com.daaw.avee.comp.Visualizer.ICompositionDependencies;
import com.daaw.avee.comp.Visualizer.IDependencyResourceCounter;
import mdesl.graphics.glutils.FrameBuffer;
/* loaded from: classes.dex */
public class AudioDataProviderElement extends Element {
    public static final String typeName = "AudioProvider";
    protected ISegmentDataProvider segmentDataProvider;

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public String getElementTypeName() {
        return typeName;
    }

    protected String getDefaultProviderTypeName() {
        return SegmentAudioSpectrumData.typeName;
    }

    protected String[] getProviderTypeNames() {
        return SegmentDataProviderFactory.typeNames;
    }

    protected String getDefaultCustomizationName() {
        return "Audio Provider";
    }

    public AudioDataProviderElement() {
        super(0, 0.5f, 0.5f);
        this.segmentDataProvider = null;
        setSegmentDataProvider(null);
    }

    public void setSegmentDataProvider(ISegmentDataProvider iSegmentDataProvider) {
        this.segmentDataProvider = iSegmentDataProvider;
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onApplyCustomization(CustomPropertiesList customPropertiesList) {
        ISegmentDataProvider create = SegmentDataProviderFactory.create(customPropertiesList.getChild("sampleProvider").getTypeName(getDefaultProviderTypeName()), this.segmentDataProvider);
        this.segmentDataProvider = create;
        if (create != null) {
            create.onApplyCustomization(customPropertiesList);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onReadCustomization(CustomPropertiesList customPropertiesList, IDependencyResourceCounter iDependencyResourceCounter) {
        customPropertiesList.setCustomizationName(getDefaultCustomizationName());
        customPropertiesList.putEmptyChild("sampleProvider", SegmentDataProviderFactory.getTypeName(this.segmentDataProvider, getDefaultProviderTypeName()), "0_general", getProviderTypeNames());
        ISegmentDataProvider iSegmentDataProvider = this.segmentDataProvider;
        if (iSegmentDataProvider != null) {
            iSegmentDataProvider.onReadCustomization(customPropertiesList);
        }
    }

    /* JADX INFO: Access modifiers changed from: protected */
    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onDestroyGLResources(RenderState renderState) {
        super.onDestroyGLResources(renderState);
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public boolean onCreateGLResources(RenderState renderState) {
        return super.onCreateGLResources(renderState);
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onRender(RenderState renderState, FrameBuffer frameBuffer) {
        super.onRender(renderState, frameBuffer);
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.Element
    public void onEarlyUpdate(IRenderState iRenderState, FrameBuffer frameBuffer, ICompositionDependencies iCompositionDependencies) {
        super.onEarlyUpdate(iRenderState, frameBuffer, iCompositionDependencies);
        ISegmentDataProvider iSegmentDataProvider = this.segmentDataProvider;
        if (iSegmentDataProvider != null) {
            iSegmentDataProvider.process(iRenderState, iRenderState.getRes().getVisualizationData());
            iRenderState.getRes().getMeter().setFrameDataRmsValue(this.segmentDataProvider.getRms());
            iRenderState.getRes().getMeter().setFramePeakBarIndex(this.segmentDataProvider.getPeakBarIndex());
        }
        iRenderState.getRes().getMeter().addAudioDataProviderToFrame(this.segmentDataProvider);
    }
}

package com.daaw.avee.comp.Visualizer.Elements;

import com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentAeSpectrumData;
import com.daaw.avee.comp.Visualizer.Elements.Segment.SegmentDataProviderFactory;

/**
 * Second AudioProvider layer using EffectCraft's AE Audio Spectrum / Waveform engine by default.
 */
public class AeAudioDataProviderElement extends AudioDataProviderElement {
    public static final String typeName = "AudioProviderAE";

    @Override
    public String getElementTypeName() {
        return typeName;
    }

    @Override
    protected String getDefaultProviderTypeName() {
        return SegmentAeSpectrumData.typeName;
    }

    @Override
    protected String[] getProviderTypeNames() {
        return SegmentDataProviderFactory.aeTypeNames;
    }

    @Override
    protected String getDefaultCustomizationName() {
        return "Audio Provider (AE)";
    }
}

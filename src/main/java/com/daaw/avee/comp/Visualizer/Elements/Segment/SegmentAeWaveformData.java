package com.daaw.avee.comp.Visualizer.Elements.Segment;

import com.daaw.avee.Common.ISimpleListFloat;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Elements.Base.IFrameDataProvider;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
import com.daaw.avee.comp.Visualizer.InternalVisualizationDataProvider;

/**
 * After-Effects-style Audio Waveform data provider based on EffectCraft audio.rs
 * with familiar Avee Waveform properties.
 */
public class SegmentAeWaveformData implements ISegmentDataProvider, IFrameDataProvider {
    public static final String typeName = "AEWaveform";
    static final String[] WAVEFORM_MODES = {"EnvelopePeak", "EnvelopeSpread", "SignedSample"};

    private final SegmentAeAudioCommon capture = new SegmentAeAudioCommon();
    private final SegmentDataProviderFactory.PauseFreezeState pauseFreeze = new SegmentDataProviderFactory.PauseFreezeState();

    private int sampleInCountPower = 11;
    private int sampleOutCountPower = 7;
    private String waveformMode = WAVEFORM_MODES[0];
    private float[] barVals = new float[128];
    private float beat = 0.0f;

    private final ISimpleListFloat barValsList = new ISimpleListFloat() {
        @Override
        public int size() {
            return SegmentAeWaveformData.this.barVals.length;
        }

        @Override
        public float get(int i) {
            return SegmentAeWaveformData.this.barVals[i];
        }
    };

    public SegmentAeWaveformData setAudioDurationMs(int ms) {
        this.capture.audioDurationMs = Math.max(10, Math.min(300, ms));
        return this;
    }

    public SegmentAeWaveformData setSampleOutCountPower(int p) {
        this.sampleOutCountPower = Math.max(3, Math.min(9, p));
        return this;
    }

    public SegmentAeWaveformData setWaveformMode(String mode) {
        for (String m : WAVEFORM_MODES) {
            if (m.equals(mode)) {
                this.waveformMode = m;
                return this;
            }
        }
        return this;
    }

    @Override
    public float getPeakBarIndex() {
        return 0.0f;
    }

    @Override
    public float getRms() {
        return this.beat;
    }

    @Override
    public void process(IRenderState renderState, InternalVisualizationDataProvider dataProvider) {
        if (this.pauseFreeze.shouldFreeze(dataProvider)) {
            return;
        }
        this.pauseFreeze.markProcessed(dataProvider);

        int fallback = Math.min(16384, Math.max(32, 1 << this.sampleInCountPower));
        int m = this.capture.captureNormalizedSamples(dataProvider, fallback);
        int outCount = Math.min(512, Math.max(8, 1 << this.sampleOutCountPower));
        if (this.barVals.length != outCount) {
            this.barVals = new float[outCount];
        }

        float dt = renderState != null ? Math.max(0.008f, renderState.getFrameTimeF()) : 0.016f;
        boolean instant = this.capture.smoothFactor >= 0.999f;
        float alpha = instant ? 1.0f : Math.min(1.0f, 20.0f * dt * this.capture.smoothFactor);
        float scale = SegmentAeAudioCommon.AVEE_SCALE * this.capture.outputMultiplier;
        int modeIdx = WAVEFORM_MODES[1].equals(this.waveformMode) ? 1 : (WAVEFORM_MODES[2].equals(this.waveformMode) ? 2 : 0);

        float peakBeat = 0.0f;
        for (int i = 0; i < outCount; i++) {
            double u0 = (double) i / (double) Math.max(1, outCount - 1);
            int a = Math.min(m - 1, (int) (u0 * (m - 1)));
            int b = Math.max(a, Math.min(m - 1, (int) (((double) (i + 1) / (double) outCount) * (m - 1))));
            double lo = 0.0d;
            double hi = 0.0d;
            for (int k = a; k <= b; k++) {
                double s = this.capture.samplesTmp[k];
                if (s < lo) lo = s;
                if (s > hi) hi = s;
            }
            float raw;
            if (modeIdx == 2) {
                raw = (float) this.capture.samplesTmp[a] * scale;
            } else if (modeIdx == 1) {
                raw = (float) ((hi - lo) * 0.5d) * scale;
            } else {
                raw = (float) Math.max(-lo, hi) * scale;
            }
            peakBeat = Math.max(peakBeat, Math.abs(raw) / SegmentAeAudioCommon.AVEE_SCALE);
            this.barVals[i] = this.barVals[i] + ((raw - this.barVals[i]) * alpha);
        }
        this.beat = Math.min(1.0f, peakBeat);
    }

    @Override
    public float[] getFrameValues(int i, int i2) {
        return this.barVals;
    }

    @Override
    public ISimpleListFloat createFrameValuesAccessorList(int i, int i2, int i3, ISimpleListFloat existing) {
        return this.barValsList;
    }

    @Override
    public void onApplyCustomization(CustomPropertiesList props) {
        this.sampleInCountPower = Math.max(5, Math.min(13, props.getPropertyInt("inputSize", 11)));
        this.capture.applyCaptureProperties(props);
        this.sampleOutCountPower = Math.max(3, Math.min(9, props.getPropertyInt("sampleOutCountPower", 7)));
        setWaveformMode(props.getPropertySelectString("waveformMode", WAVEFORM_MODES[0]));
    }

    @Override
    public void onReadCustomization(CustomPropertiesList props) {
        props.putPropertyIntAsRange("inputSize", this.sampleInCountPower, "Performance", 5, 13);
        this.capture.readCaptureProperties(props);
        props.putPropertyIntAsRange("sampleOutCountPower", this.sampleOutCountPower, "Waveform", 3, 9);
        props.putSelectString("waveformMode", this.waveformMode, "Waveform", WAVEFORM_MODES);
        props.putPropertyFloatAsRange("smooth", this.capture.smoothFactor, "Waveform", 0.1f, 1.0f);
        props.putPropertyFloatAsRange("outputMultiplier", this.capture.outputMultiplier, "Waveform", 0.1f, 5.0f);
    }
}

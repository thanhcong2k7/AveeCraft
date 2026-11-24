package com.daaw.avee.comp.Visualizer.Elements.Segment;

import com.daaw.avee.Common.ISimpleListFloat;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Dsp.DspCurves;
import com.daaw.avee.comp.Visualizer.Dsp.RangeBox;
import com.daaw.avee.comp.Visualizer.Dsp.SignalFilter1d;
import com.daaw.avee.comp.Visualizer.Elements.Base.FrameValuesAccumulator;
import com.daaw.avee.comp.Visualizer.Elements.Base.IFrameDataProvider;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
import com.daaw.avee.comp.Visualizer.InternalVisualizationDataProvider;

/**
 * After-Effects-style Audio Spectrum data provider based on EffectCraft audio.rs
 * with familiar Avee AudioProvider properties.
 */
public class SegmentAeSpectrumData implements ISegmentDataProvider, IFrameDataProvider {
    public static final String typeName = "AESpectrum";

    private final SegmentAeAudioCommon capture = new SegmentAeAudioCommon();
    private final FrameValuesAccumulator frameValuesAccumulator = new FrameValuesAccumulator();
    private final SegmentDataProviderFactory.PauseFreezeState pauseFreeze = new SegmentDataProviderFactory.PauseFreezeState();

    private int fftSizePower = 11;
    private int barsCount = 64;
    private float loFreq = 20.0f;
    private float hiFreq = 2000.0f;
    private float freqLinFactor = 1.0f;
    private float freqShift = 0.0f;
    private boolean mirrorSampleValues = false;
    private int repeatSamples = 1;
    private int starAndEndGap = 0;
    private float preSmoothFactor = 1.0f;
    private float aweightFft = 0.0f;

    private float beatSmoothFactor = 0.4f;
    private float beatBarValueSmooth = 0.0f;
    private float peakBarIndex01Smooth = 0.0f;
    private final RangeBox beatRange = new RangeBox(0.5f);
    private final SignalFilter1d fftFilter = new SignalFilter1d().createHighPass(0, 1.0f);

    private double[] fftRe = new double[0];
    private double[] fftIm = new double[0];
    private double[] fftMag = new double[0];
    private double[] fftFiltered = new double[0];
    private float[] barValues = new float[64];
    private float[] barSmoothValues = new float[64];

    public SegmentAeSpectrumData() {
        this.beatRange.setCorners(0.0f, 0.2f, 0.7f, 35.0f);
    }

    public synchronized SegmentAeSpectrumData setSampleOutCount(int count) {
        this.barsCount = Math.max(1, Math.min(512, count));
        return this;
    }

    public synchronized SegmentAeSpectrumData setFreqRange(float lowerHz, float higherHz) {
        this.loFreq = Math.max(0.0f, lowerHz);
        this.hiFreq = Math.max(this.loFreq + 1.0f, higherHz);
        return this;
    }

    public synchronized SegmentAeSpectrumData setAudioDurationMs(int ms) {
        this.capture.audioDurationMs = Math.max(10, Math.min(300, ms));
        return this;
    }

    public synchronized SegmentAeSpectrumData setAudioOffsetMs(int ms) {
        this.capture.audioOffsetMs = Math.max(-200, Math.min(200, ms));
        return this;
    }

    public synchronized SegmentAeSpectrumData setChannelMode(String mode) {
        this.capture.channelMode = mode;
        return this;
    }

    @Override
    public synchronized void process(IRenderState renderState, InternalVisualizationDataProvider dataProvider) {
        if (this.pauseFreeze.shouldFreeze(dataProvider)) {
            return;
        }
        this.pauseFreeze.markProcessed(dataProvider);

        int m = this.capture.captureNormalizedSamples(dataProvider, 64);
        int minFft = 1 << Math.max(7, Math.min(13, this.fftSizePower));
        int n = Math.max(SegmentAeAudioCommon.nextPowerOfTwo(m), minFft);

        if (this.fftRe.length != n) {
            this.fftRe = new double[n];
            this.fftIm = new double[n];
        } else {
            java.util.Arrays.fill(this.fftRe, 0.0d);
            java.util.Arrays.fill(this.fftIm, 0.0d);
        }

        double denom = Math.max(2, m) - 1;
        for (int i = 0; i < m; i++) {
            double hann = 0.5d - (0.5d * Math.cos((2.0d * Math.PI * i) / denom));
            this.fftRe[i] = this.capture.samplesTmp[i] * hann;
        }
        SegmentAeAudioCommon.fft(this.fftRe, this.fftIm);

        int numBins = (n / 2) + 1;
        double scale = 4.0d / Math.max(1, m);
        double hzPerBin = (double) this.capture.lastSampleRate / (double) n;
        if (this.fftMag.length != numBins) {
            this.fftMag = new double[numBins];
            this.fftFiltered = new double[numBins];
        }
        for (int k = 0; k < numBins; k++) {
            double r = this.fftRe[k];
            double im = this.fftIm[k];
            double mag = Math.sqrt((r * r) + (im * im)) * scale;
            if (this.aweightFft > 0.0f) {
                float w = (float) DspCurves.myAWeight1000(DspCurves.freqd(k, numBins, this.capture.lastSampleRate));
                float aw = this.aweightFft;
                mag *= (1.0f - aw) + (aw * w * w * w);
            }
            this.fftFiltered[k] = mag;
        }

        int rad = this.fftFilter.getRadius();
        double str = this.fftFilter.getStrength();
        for (int k = 0; k < numBins; k++) {
            double val = this.fftFiltered[k];
            if (rad > 0) {
                int lo = Math.max(0, k - rad);
                int hi = Math.min(numBins - 1, k + rad);
                double avg = 0.0d;
                for (int j = lo; j <= hi; j++) {
                    avg += this.fftFiltered[j];
                }
                avg /= (hi - lo + 1);
                val = Math.max(0.0d, val - (avg * str * 0.5d));
            }
            double prev = this.fftMag[k];
            double ps = this.preSmoothFactor;
            this.fftMag[k] = (prev * (1.0d - ps)) + (val * ps);
        }

        if (this.barValues.length != this.barsCount) {
            this.barValues = new float[this.barsCount];
        }
        if (this.barSmoothValues.length != this.barsCount) {
            this.barSmoothValues = new float[this.barsCount];
        }

        fillBands(hzPerBin);

        float dt = renderState != null ? Math.max(0.008f, renderState.getFrameTimeF()) : 0.016f;
        boolean instant = this.capture.smoothFactor >= 0.999f;
        float alpha = instant ? 1.0f : Math.min(1.0f, 20.0f * dt * this.capture.smoothFactor);

        this.beatRange.reset(this.barsCount);
        float peakVal = -1.0f;
        int peakIdx = 0;
        for (int i = 0; i < this.barsCount; i++) {
            if (this.barValues[i] > peakVal) {
                peakVal = this.barValues[i];
                peakIdx = i;
            }
            float cur = this.barSmoothValues[i];
            float next = cur + ((this.barValues[i] - cur) * alpha);
            this.barSmoothValues[i] = Math.max(0.0f, next);
            this.beatRange.addValue(i, this.barSmoothValues[i]);
        }

        float beatRaw = this.beatRange.getValueNormal();
        this.beatBarValueSmooth = (this.beatBarValueSmooth * (1.0f - this.beatSmoothFactor)) + (beatRaw * this.beatSmoothFactor);
        float peak01 = this.barsCount > 0 ? ((float) peakIdx / (float) this.barsCount) : 0.0f;
        this.peakBarIndex01Smooth = (this.peakBarIndex01Smooth * 0.5f) + (peak01 * 0.5f);

        this.frameValuesAccumulator.addFrame(this.barSmoothValues);
    }

    private void fillBands(double hzPerBin) {
        int n = this.barsCount;
        java.util.Arrays.fill(this.barValues, 0.0f);
        boolean mirror = this.mirrorSampleValues && n > 1;
        int activeTotal = mirror ? (n / 2) : n;
        int gap = Math.max(0, Math.min(activeTotal / 3, this.starAndEndGap));
        int span = Math.max(1, activeTotal - (2 * gap));
        int cycle = Math.max(1, span / Math.max(1, this.repeatSamples));
        int shift = Math.round(span * this.freqShift);
        int maxBin = Math.max(0, this.fftMag.length - 1);

        double f0 = Math.max(0.0d, this.loFreq);
        double f1 = Math.max(f0 + 1.0d, this.hiFreq);
        double f0Log = Math.max(1.0d, f0);
        double totalGain = SegmentAeAudioCommon.AVEE_SCALE * this.capture.outputMultiplier;

        for (int i = 0; i < span; i++) {
            int local = (((i - shift) % cycle) + cycle) % cycle;
            double u = cycle > 1 ? ((double) local / (double) (cycle - 1)) : 0.5d;
            double fcLin = f0 + ((f1 - f0) * u);
            double fcLog = f0Log * Math.pow(f1 / f0Log, u);
            double fc = (fcLog * (1.0d - this.freqLinFactor)) + (fcLin * this.freqLinFactor);

            double v = 0.0d;
            if (maxBin > 0 && hzPerBin > 0.0d) {
                double kFloat = Math.max(0.0d, Math.min(maxBin, fc / hzPerBin));
                int k0 = Math.min(maxBin - 1, (int) Math.floor(kFloat));
                int k1 = Math.min(maxBin, k0 + 1);
                double frac = Math.max(0.0d, Math.min(1.0d, kFloat - k0));
                v = (this.fftMag[k0] * (1.0d - frac)) + (this.fftMag[k1] * frac);
            }
            this.barValues[gap + i] = (!Double.isNaN(v) && !Double.isInfinite(v)) ? Math.max(0.0f, (float) (v * totalGain)) : 0.0f;
        }

        if (mirror) {
            int srcIdx = 0;
            for (int k = n - 1; k >= activeTotal; k--) {
                this.barValues[k] = this.barValues[Math.min(srcIdx, n - 1)];
                srcIdx++;
            }
        }
    }

    @Override
    public float[] getFrameValues(int i, int i2) {
        return this.frameValuesAccumulator.getFrameValues(i, i2);
    }

    @Override
    public ISimpleListFloat createFrameValuesAccessorList(int i, int i2, int i3, ISimpleListFloat existing) {
        return this.frameValuesAccumulator.createFrameValuesAccessorList(i, i2, i3, existing);
    }

    @Override
    public synchronized float getRms() {
        return this.beatBarValueSmooth;
    }

    @Override
    public synchronized float getPeakBarIndex() {
        return this.peakBarIndex01Smooth;
    }

    @Override
    public synchronized void onApplyCustomization(CustomPropertiesList props) {
        this.fftSizePower = Math.max(7, Math.min(13, props.getPropertyInt("fftSize", 11)));
        this.capture.applyCaptureProperties(props);
        this.barsCount = Math.max(1, Math.min(512, props.getPropertyInt("sampleOutCount", 64)));
        setFreqRange(props.getPropertyFloat("lowerHz", 20.0f), props.getPropertyFloat("higherHz", 2000.0f));
        this.freqLinFactor = Math.max(0.0f, Math.min(1.0f, props.getPropertyFloat("hzLinearFactor", 1.0f)));
        this.freqShift = Math.max(0.0f, Math.min(1.0f, props.getPropertyFloat("freqShift", 0.0f)));
        this.mirrorSampleValues = props.getPropertyBool("mirrorSamples", false);
        this.repeatSamples = Math.max(1, Math.min(6, props.getPropertyInt("repeatSamples", 1)));
        this.starAndEndGap = Math.max(0, Math.min(30, props.getPropertyInt("starAndEndGap", 0)));
        this.preSmoothFactor = Math.max(0.1f, Math.min(1.0f, props.getPropertyFloat("preSmooth", 1.0f)));
        this.fftFilter.createHighPass(Math.max(0, Math.min(20, props.getPropertyInt("filterRadius", 0))),
                Math.max(0.1f, Math.min(2.0f, props.getPropertyFloat("filterStrength", 1.0f))));
        this.aweightFft = Math.max(0.0f, Math.min(1.0f, props.getPropertyFloat("aWeight", 0.0f)));
        this.beatSmoothFactor = Math.max(0.1f, Math.min(1.0f, props.getPropertyFloat("beatSmooth", 0.4f)));
        this.beatRange.setCorners(
                props.getPropertyFloat("beatRangeBarFirst", 0.0f),
                props.getPropertyFloat("beatRangeBarLast", 0.2f),
                props.getPropertyFloat("beatRangeValueLower", 0.7f),
                props.getPropertyFloat("beatRangeValueHigher", 35.0f));
    }

    @Override
    public synchronized void onReadCustomization(CustomPropertiesList props) {
        props.putPropertyIntAsRange("fftSize", this.fftSizePower, "Performance", 7, 13);
        this.capture.readCaptureProperties(props);
        props.putPropertyIntAsRange("sampleOutCount", this.barsCount, "1_spectrum", 1, 512);
        props.putPropertyFloatAsRange("lowerHz", this.loFreq, "1_spectrumHz", 0.0f, 500.0f);
        props.putPropertyFloatAsRange("higherHz", this.hiFreq, "1_spectrumHz", 100.0f, 20000.0f);
        props.putPropertyFloatAsRange("hzLinearFactor", this.freqLinFactor, "1_spectrumHz", 0.0f, 1.0f);
        props.putPropertyFloatAsRange("freqShift", this.freqShift, "1_spectrumHz", 0.0f, 1.0f);
        props.putPropertyBool("mirrorSamples", this.mirrorSampleValues, "1_spectrum");
        props.putPropertyIntAsRange("repeatSamples", this.repeatSamples, "1_spectrum", 1, 6);
        props.putPropertyIntAsRange("starAndEndGap", this.starAndEndGap, "1_spectrum", 0, 30);
        props.putPropertyFloatAsRange("smooth", this.capture.smoothFactor, "1_spectrum", 0.1f, 1.0f);
        props.putPropertyFloatAsRange("preSmooth", this.preSmoothFactor, "1_spectrum", 0.1f, 1.0f);
        props.putPropertyIntAsRange("filterRadius", this.fftFilter.getRadius(), "1_spectrum", 0, 20);
        props.putPropertyFloatAsRange("filterStrength", this.fftFilter.getStrength(), "1_spectrum", 0.1f, 2.0f);
        props.putPropertyFloatAsRange("aWeight", this.aweightFft, "1_spectrum", 0.0f, 1.0f);
        props.putPropertyFloatAsRange("outputMultiplier", this.capture.outputMultiplier, "1_spectrum", 0.1f, 5.0f);
        props.putPropertyFloatAsRange("beatSmooth", this.beatSmoothFactor, "beat", 0.1f, 1.0f);
        props.putPropertyFloatAsRange("beatRangeBarFirst", this.beatRange.getX0(), "beat", 0.0f, 1.0f);
        props.putPropertyFloatAsRange("beatRangeBarLast", this.beatRange.getX1(), "beat", 0.0f, 1.0f);
        props.putPropertyFloatAsRange("beatRangeValueLower", this.beatRange.getY0(), "beat", 0.0f, 50.0f);
        props.putPropertyFloatAsRange("beatRangeValueHigher", this.beatRange.getY1(), "beat", 0.0f, 100.0f);
    }
}

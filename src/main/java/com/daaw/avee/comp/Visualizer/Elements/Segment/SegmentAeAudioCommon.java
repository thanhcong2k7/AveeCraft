package com.daaw.avee.comp.Visualizer.Elements.Segment;

import com.daaw.avee.Common.ISimpleListShort;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.InternalVisualizationDataProvider;
import com.daaw.avee.comp.playback.AudioFrameData;

/**
 * Shared EffectCraft AE audio windowing, stereo channel selection, optional AGC, and radix-2 FFT.
 */
final class SegmentAeAudioCommon {
    static final float AVEE_SCALE = 35.0f;
    static final String[] CHANNEL_MODES = {"Left & Right", "Left", "Right"};

    int audioDurationMs = 40;
    int audioOffsetMs = 0;
    String channelMode = CHANNEL_MODES[0];
    boolean agcNormalized = false;
    float outputMultiplier = 1.0f;
    float smoothFactor = 1.0f;

    private float rangeHiSmooth = 1000.0f;
    private float rangeLoSmooth = 0.0f;
    private float rangeSmoothMul = 1.0f;

    int lastSampleRate = 44100;
    float lastRms = 0.0f;
    double[] samplesTmp = new double[0];

    void applyCaptureProperties(CustomPropertiesList props) {
        this.audioDurationMs = Math.max(10, Math.min(300, props.getPropertyInt("audioDurationMs", 40)));
        this.audioOffsetMs = Math.max(-200, Math.min(200, props.getPropertyInt("audioOffsetMs", 0)));
        String ch = props.getPropertySelectString("channelMode", CHANNEL_MODES[0]);
        this.channelMode = isValidChannel(ch) ? ch : CHANNEL_MODES[0];
        this.agcNormalized = props.getPropertyBool("agcNormalized", false);
        this.outputMultiplier = Math.max(0.05f, props.getPropertyFloat("outputMultiplier", 1.0f));
        this.smoothFactor = Math.max(0.05f, Math.min(1.0f, props.getPropertyFloat("smooth", 1.0f)));
    }

    void readCaptureProperties(CustomPropertiesList props) {
        props.putPropertyIntAsRange("audioDurationMs", this.audioDurationMs, "1_AudioCapture", 10, 300);
        props.putPropertyIntAsRange("audioOffsetMs", this.audioOffsetMs, "1_AudioCapture", -200, 200);
        props.putSelectString("channelMode", this.channelMode, "1_AudioCapture", CHANNEL_MODES);
        props.putPropertyBool("agcNormalized", this.agcNormalized, "1_AudioCapture");
    }

    private static boolean isValidChannel(String s) {
        for (String m : CHANNEL_MODES) {
            if (m.equals(s)) return true;
        }
        return false;
    }

    int captureNormalizedSamples(InternalVisualizationDataProvider dataProvider, int minFallbackSamples) {
        int durSamples = Math.max(16, Math.round(this.lastSampleRate * (this.audioDurationMs / 1000.0f)));
        int wanted = Math.max(minFallbackSamples, durSamples);
        int overrideCount = dataProvider != null ? dataProvider.onRequestOverrideAudioFrameSamplesInCount() : 0;
        if (overrideCount > 0) {
            wanted = overrideCount;
        }
        wanted = Math.max(16, Math.min(16384, wanted));

        AudioFrameData fd = dataProvider != null
                ? dataProvider.onRequestSoundVisualizationData(wanted, this.audioOffsetMs)
                : null;
        if (fd == null) {
            this.lastRms = 0.0f;
            if (this.samplesTmp.length != wanted) {
                this.samplesTmp = new double[wanted];
            } else {
                java.util.Arrays.fill(this.samplesTmp, 0.0d);
            }
            return wanted;
        }

        this.lastSampleRate = fd.sampleRate > 0 ? fd.sampleRate : 44100;
        this.lastRms = fd.rms;
        updateRange(0.0f, fd.maxPcmValue);

        int requestedFrames = Math.max(16, Math.min(16384, Math.round(this.lastSampleRate * (this.audioDurationMs / 1000.0f))));
        if (overrideCount > 0) {
            requestedFrames = Math.min(16384, overrideCount);
        }

        ISimpleListShort stereo = fd.pcmAsDesiredChannelCount(2, fd.getBufferSizeForFftResult());
        int availStereo = stereo != null ? (stereo.size() / 2) : 0;
        int count = Math.max(16, Math.min(requestedFrames, availStereo > 0 ? availStereo : requestedFrames));
        if (this.samplesTmp.length != count) {
            this.samplesTmp = new double[count];
        }

        if (availStereo <= 0 || stereo == null) {
            java.util.Arrays.fill(this.samplesTmp, 0.0d);
            return count;
        }

        int startFrame = Math.max(0, (availStereo - count) / 2);
        int chMode = CHANNEL_MODES[1].equals(this.channelMode) ? 1 : (CHANNEL_MODES[2].equals(this.channelMode) ? 2 : 0);
        double agcScale = this.agcNormalized ? (this.rangeSmoothMul * (32768.0d / 25600.0d)) : 1.0d;
        double invNorm = agcScale / 32768.0d;

        for (int i = 0; i < count; i++) {
            int fIdx = Math.min(availStereo - 1, startFrame + i);
            double l = stereo.get(fIdx * 2);
            double r = stereo.get((fIdx * 2) + 1);
            double s = chMode == 1 ? l : (chMode == 2 ? r : ((l + r) * 0.5d));
            this.samplesTmp[i] = s * invNorm;
        }
        return count;
    }

    private void updateRange(float lo, float hi) {
        this.rangeLoSmooth = (this.rangeLoSmooth * 0.100000024f) + (lo * 0.9f);
        if (hi > this.rangeHiSmooth) {
            this.rangeHiSmooth = (this.rangeHiSmooth * 0.100000024f) + (hi * 0.9f);
        } else {
            this.rangeHiSmooth = (this.rangeHiSmooth * 0.99f) + (hi * 0.01f);
        }
        float diff = this.rangeHiSmooth - this.rangeLoSmooth;
        this.rangeSmoothMul = 300.0f / (diff < 1.0f ? 1.0f : diff);
    }

    static int nextPowerOfTwo(int v) {
        int n = 64;
        while (n < v && n < (1 << 16)) {
            n <<= 1;
        }
        return n;
    }

    /**
     * EffectCraft audio.rs radix-2 complex FFT (re, im lengths must be power of two).
     */
    static void fft(double[] re, double[] im) {
        int n = re.length;
        if (n < 2) return;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j |= bit;
            if (i < j) {
                double tr = re[i];
                double ti = im[i];
                re[i] = re[j];
                im[i] = im[j];
                re[j] = tr;
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = (-2.0d * Math.PI) / len;
            double ws = Math.sin(ang);
            double wc = Math.cos(ang);
            int half = len >> 1;
            for (int start = 0; start < n; start += len) {
                double cr = 1.0d;
                double ci = 0.0d;
                for (int k = 0; k < half; k++) {
                    int a = start + k;
                    int b = a + half;
                    double tr = (re[b] * cr) - (im[b] * ci);
                    double ti = (re[b] * ci) + (im[b] * cr);
                    re[b] = re[a] - tr;
                    im[b] = im[a] - ti;
                    re[a] += tr;
                    im[a] += ti;
                    double ncr = (cr * wc) - (ci * ws);
                    ci = (cr * ws) + (ci * wc);
                    cr = ncr;
                }
            }
        }
    }
}

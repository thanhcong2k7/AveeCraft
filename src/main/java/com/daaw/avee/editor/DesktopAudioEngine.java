package com.daaw.avee.editor;

import com.daaw.avee.Common.tlog;
import com.daaw.avee.Design.AppDesign;
import com.daaw.avee.comp.AlbumArt.AlbumArtCore;
import com.daaw.avee.comp.AlbumArt.AlbumArtRequest;
import com.daaw.avee.comp.AlbumArt.AlbumArtRequest2;
import com.daaw.avee.comp.AlbumArt.ImageLoadedListener;
import com.daaw.avee.comp.Common.MeasureDefs;
import com.daaw.avee.comp.Common.MeterOutputData;
import com.daaw.avee.comp.Common.TrackDurationInfo;
import com.daaw.avee.comp.Visualizer.FrameStats;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
import com.daaw.avee.comp.Visualizer.InternalVisualizationDataProvider;
import com.daaw.avee.comp.playback.AudioFrameData;
import com.daaw.avee.Common.Interpolate;
import com.daaw.avee.Common.Vec2f;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import java.util.Random;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import mdesl.graphics.Texture;

public class DesktopAudioEngine implements InternalVisualizationDataProvider {
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    private volatile short[] pcmSamples = new short[0];
    private volatile long totalFrames = 0L;
    private volatile long durationMs = 0L;
    private volatile long playheadFrame = 0L;
    private volatile boolean playing = false;
    private volatile boolean muted = false;
    private volatile float volume = 1.0f;
    private volatile boolean syntheticBeat = false;
    private volatile boolean highQualityBlur = true;
    private volatile long seekRevision = 0L;
    private volatile long completedCount = 0L;
    private volatile String currentTitle = "No Audio Loaded";
    private volatile String currentArtist = "Avee Desktop Editor";
    private volatile File audioFile;
    private volatile String embeddedArtPath = null;

    private final AudioFrameData frameData = new AudioFrameData();
    private final Object playLock = new Object();
    private Thread audioThread;
    private volatile boolean shutdown = false;

    private final Random random = new Random();
    private final Vec2f shakePoint = new Vec2f(0f, 0f);
    private final Vec2f shakePointSmooth = new Vec2f(0f, 0f);
    private final com.daaw.avee.Design.HandheldMotion handheldMotionSmooth =
            new com.daaw.avee.Design.HandheldMotion(com.daaw.avee.Design.HandheldMotion.Jarles_Presets_MoreMovement_Smoothest);
    private final com.daaw.avee.Design.HandheldMotion handheldMotionLotOfShake =
            new com.daaw.avee.Design.HandheldMotion(com.daaw.avee.Design.HandheldMotion.Jarles_Presets_LotsOshake);
    private float totalTimeWhenPlaying = 0f;
    private float totalTimeSec = 0f;
    private volatile int latestFrameTimeMs = 16;
    private FrameStats latestStats;

    public DesktopAudioEngine() {
        this(true);
    }

    public DesktopAudioEngine(boolean startPlaybackThread) {
        if (startPlaybackThread) {
            startPlaybackThread();
        }
    }

    public void setLatestStats(FrameStats stats) {
        this.latestStats = stats;
    }

    public boolean loadAudioFile(File file) {
        if (file == null || !file.exists()) return false;
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    FfmpegLocator.ffmpeg(), "-v", "error", "-i", file.getAbsolutePath(),
                    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2", "-ar", String.valueOf(SAMPLE_RATE),
                    "pipe:1");
            Process proc = pb.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (InputStream is = proc.getInputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
            }
            int rc = proc.waitFor();
            byte[] raw = baos.toByteArray();
            if (rc != 0 || raw.length < 4) {
                tlog.w("ffmpeg decode failed for " + file.getName() + " exit=" + rc);
                return false;
            }
            int sampleCount = raw.length / 2;
            short[] shorts = new short[sampleCount];
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(shorts);

            synchronized (playLock) {
                this.pcmSamples = shorts;
                this.totalFrames = sampleCount / CHANNELS;
                this.durationMs = (this.totalFrames * 1000L) / SAMPLE_RATE;
                this.playheadFrame = 0L;
                this.audioFile = file;
                this.embeddedArtPath = extractEmbeddedAlbumArt(file);
                String n = file.getName();
                int dot = n.lastIndexOf('.');
                this.currentTitle = dot > 0 ? n.substring(0, dot) : n;
                this.currentArtist = "Local Audio";
                this.syntheticBeat = false;
                this.playing = true;
                this.seekRevision++;
                AppDesign.setTrackPositionMs(0L);
            }
            return true;
        } catch (Throwable t) {
            tlog.w("Audio load failed: " + t.getMessage());
            return false;
        }
    }

    private void startPlaybackThread() {
        audioThread = new Thread(() -> {
            SourceDataLine line = null;
            byte[] chunk = new byte[2048 * CHANNELS * 2];
            try {
                AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                if (AudioSystem.isLineSupported(info)) {
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(fmt, chunk.length * 4);
                    line.start();
                }
            } catch (Throwable t) {
                tlog.w("Audio output unavailable (visual-only clock active): " + t.getMessage());
                line = null;
            }

            long lastTickNs = System.nanoTime();
            while (!shutdown) {
                long nowNs = System.nanoTime();
                double dtSec = Math.min(0.1, (nowNs - lastTickNs) / 1_000_000_000.0);
                lastTickNs = nowNs;

                short[] samples;
                long curFrame;
                long totFrames;
                long iterSeekRev;
                boolean isPlay;
                boolean isMute;
                synchronized (playLock) {
                    samples = this.pcmSamples;
                    curFrame = this.playheadFrame;
                    totFrames = this.totalFrames;
                    iterSeekRev = this.seekRevision;
                    isPlay = this.playing;
                    isMute = this.muted;
                }

                if (isPlay && totFrames > 0) {
                    int framesThisStep = Math.min(1024, (int) Math.max(0, totFrames - curFrame));
                    if (framesThisStep <= 0) {
                        synchronized (playLock) {
                            if (this.seekRevision == iterSeekRev) {
                                this.completedCount++;
                                this.playheadFrame = 0L;
                                AppDesign.setTrackPositionMs(0L);
                            }
                        }
                        continue;
                    }
                    if (line != null) {
                        int bytes = framesThisStep * CHANNELS * 2;
                        float gain = isMute ? 0.0f : this.volume;
                        for (int i = 0; i < framesThisStep * CHANNELS; i++) {
                            int raw = samples[(int) (curFrame * CHANNELS + i)];
                            int scaled = gain <= 0.0f ? 0 : Math.max(-32768, Math.min(32767, Math.round(raw * gain)));
                            short s = (short) scaled;
                            chunk[i * 2] = (byte) (s & 0xFF);
                            chunk[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
                        }
                        line.write(chunk, 0, bytes);
                    } else {
                        try { Thread.sleep(16); } catch (InterruptedException ignored) {}
                        framesThisStep = (int) (dtSec * SAMPLE_RATE);
                    }
                    synchronized (playLock) {
                        if (this.seekRevision == iterSeekRev) {
                            this.playheadFrame = Math.min(totFrames, curFrame + framesThisStep);
                            AppDesign.setTrackPositionMs((this.playheadFrame * 1000L) / SAMPLE_RATE);
                        }
                    }
                } else {
                    try { Thread.sleep(16); } catch (InterruptedException ignored) {}
                }
            }
            if (line != null) {
                try { line.stop(); line.close(); } catch (Throwable ignored) {}
            }
        }, "AveeDesktopAudio");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void shutdown() {
        shutdown = true;
        if (audioThread != null) audioThread.interrupt();
    }

    public boolean isPlaying() { return playing || syntheticBeat; }
    public boolean isAudioPlaying() { return playing; }
    public void setPlaying(boolean p) { this.playing = p; }
    public boolean isMuted() { return muted; }
    public void setMuted(boolean m) { this.muted = m; }
    public float getVolume() { return volume; }
    public void setVolume(float v) { this.volume = Math.max(0.0f, Math.min(1.0f, v)); }
    public boolean isSyntheticBeat() { return syntheticBeat; }
    public void setSyntheticBeat(boolean s) { this.syntheticBeat = s; }
    @Override public boolean isAudioPausedForFreeze() { return !isPlaying(); }
    @Override public long getAudioSeekGeneration() { return seekRevision; }
    public long getCompletedCount() { return completedCount; }
    public boolean isHighQualityBlur() { return highQualityBlur; }
    public void setHighQualityBlur(boolean h) { this.highQualityBlur = h; }
    public boolean hasAudioLoaded() { return totalFrames > 0; }
    public String getTitle() { return currentTitle; }
    public long getDurationMs() { return durationMs; }
    public long getPositionMs() {
        return totalFrames > 0 ? (playheadFrame * 1000L) / SAMPLE_RATE : 0L;
    }

    public File getAudioFile() { return audioFile; }
    public String getEmbeddedArtPath() { return embeddedArtPath; }

    public void stepOfflineFrame(long posMs, float dtSec) {
        this.playing = true;
        synchronized (playLock) {
            if (totalFrames > 0) {
                long f = (posMs * SAMPLE_RATE) / 1000L;
                this.playheadFrame = Math.max(0L, Math.min(totalFrames - 1, f));
                this.seekRevision++;
            }
        }
        AppDesign.setTrackPositionMs(posMs);
        tickFrame(dtSec);
    }

    public void seekMs(long ms) {
        synchronized (playLock) {
            if (totalFrames > 0) {
                long f = (ms * SAMPLE_RATE) / 1000L;
                this.playheadFrame = Math.max(0L, Math.min(totalFrames - 1, f));
                this.seekRevision++;
                AppDesign.setTrackPositionMs((this.playheadFrame * 1000L) / SAMPLE_RATE);
            }
        }
    }

    public void tickFrame(float dtSec) {
        latestFrameTimeMs = Math.max(1, Math.round(dtSec * 1000f));
        totalTimeSec += dtSec;
        if (isPlaying()) {
            totalTimeWhenPlaying += dtSec;
            if (totalFrames == 0) {
                AppDesign.setTrackPositionMs((long) (totalTimeWhenPlaying * 1000.0));
            }
            if (shakePointSmooth.distance(shakePoint) < 0.1f) {
                shakePoint.x = random.nextFloat() * 2.0f - 1.0f;
                shakePoint.y = random.nextFloat() * 2.0f - 1.0f;
                shakePoint.normalize();
            }
            Interpolate.Lerp(shakePointSmooth, shakePointSmooth, shakePoint, 0.9);
        }
    }

    @Override
    public AudioFrameData onRequestSoundVisualizationData(int outCount) {
        return onRequestSoundVisualizationData(outCount, 0);
    }

    @Override
    public AudioFrameData onRequestSoundVisualizationData(int outCount, int offsetMs) {
        int count = Math.max(256, outCount);
        frameData.init(SAMPLE_RATE, CHANNELS, count, count, true);
        short[] dst = frameData.pcmBuffer;
        short[] src = this.pcmSamples;
        long frames = this.totalFrames;
        long offsetFrames = (offsetMs * (long) SAMPLE_RATE) / 1000L;
        long posFrame = frames > 0 ? Math.max(0L, Math.min(frames - 1, this.playheadFrame + offsetFrames)) : 0L;

        double rmsAccum = 0.0;
        short minS = Short.MAX_VALUE;
        short maxS = 0;

        if (frames > 0 && src.length >= frames * CHANNELS && !syntheticBeat) {
            for (int i = 0; i < count; i++) {
                long fIdx = Math.max(0, Math.min(frames - 1, posFrame - (count / 2) + i));
                short l = src[(int) (fIdx * 2)];
                short r = src[(int) (fIdx * 2 + 1)];
                dst[i * 2] = l;
                dst[i * 2 + 1] = r;
                float mono = (l + r) * 0.5f;
                rmsAccum += mono * mono;
                short abs = (short) Math.min(Short.MAX_VALUE, Math.abs(l));
                if (abs < minS) minS = abs;
                if (abs > maxS) maxS = abs;
            }
        } else if (syntheticBeat) {
            double t = totalTimeSec + (offsetMs / 1000.0);
            double beatEnv = Math.pow(Math.max(0.0, Math.sin(t * Math.PI * 2.2)), 6.0) * 0.75 + 0.25;
            for (int i = 0; i < count; i++) {
                double phase = (double) i / SAMPLE_RATE + t;
                double wave = 0.55 * Math.sin(2 * Math.PI * 62.0 * phase) * beatEnv
                            + 0.25 * Math.sin(2 * Math.PI * 220.0 * phase + t * 3.0)
                            + 0.15 * Math.sin(2 * Math.PI * 1400.0 * phase * (1.0 + 0.3 * Math.sin(t * 1.7)));
                short s = (short) Math.max(-32767, Math.min(32767, wave * 18000.0));
                dst[i * 2] = s;
                dst[i * 2 + 1] = s;
                rmsAccum += (double) s * s;
                short abs = (short) Math.abs(s);
                if (abs < minS) minS = abs;
                if (abs > maxS) maxS = abs;
            }
        } else {
            minS = 0;
        }

        frameData.minPcmValue = minS;
        frameData.maxPcmValue = maxS;
        frameData.rms = (float) (Math.sqrt(rmsAccum / count) / 32768.0);
        frameData.capturedDataPositionUs = getPositionMs() * 1000L;
        frameData.newestAvailableBufferPositionUs = frameData.capturedDataPositionUs;
        frameData.valid = true;
        return frameData;
    }

    @Override
    public String onRequestsMeasureText(String str) {
        if (str == null) return "";
        int len = str.length();
        StringBuilder out = new StringBuilder(Math.max(32, len));
        int braceStart = -1;
        for (int i = 0; i < len; i++) {
            char ch = str.charAt(i);
            if (ch == '{') {
                if (braceStart >= 0) {
                    out.append(str, braceStart, i);
                }
                braceStart = i;
            } else if (ch == '}' && braceStart >= 0) {
                out.append(getMeasurePlaceholderValue(str.substring(braceStart, i + 1)));
                braceStart = -1;
            } else if (braceStart < 0) {
                out.append(ch);
            }
        }
        if (braceStart >= 0) {
            out.append(str, braceStart, len);
        }
        return out.toString();
    }

    private String getMeasurePlaceholderValue(String token) {
        if (MeasureDefs.textTitle.equals(token)) return currentTitle;
        if (MeasureDefs.textArtist.equals(token)) return currentArtist;
        if (MeasureDefs.textArtistOrTitle.equals(token)) {
            return (currentArtist != null && !currentArtist.isEmpty() && !"Local Audio".equals(currentArtist))
                    ? currentArtist : currentTitle;
        }
        if (MeasureDefs.textArtistAndTitle.equals(token)) {
            return (currentArtist != null && !currentArtist.isEmpty())
                    ? (currentArtist + "\n" + currentTitle) : ("\n" + currentTitle);
        }
        if (MeasureDefs.textMarkedArtistAndTitle.equals(token)) {
            return (currentArtist != null && !currentArtist.isEmpty())
                    ? ("AveeCraft\n" + currentArtist + " - " + currentTitle) : ("AveeCraft\n" + currentTitle);
        }
        if (MeasureDefs.textAlbum.equals(token)) return "AveeCraft";
        if (MeasureDefs.textTimeCurrent.equals(token)) return formatTime(getPositionMs());
        if (MeasureDefs.textTimeLength.equals(token)) return formatTime(durationMs);
        if (MeasureDefs.textTimeRemaining.equals(token)) return formatTime(Math.max(0L, durationMs - getPositionMs()));
        if (MeasureDefs.textFps.equals(token)) return latestStats != null ? String.valueOf(latestStats.getFps()) : "0";
        if (MeasureDefs.textFrameTime.equals(token)) return String.valueOf(latestFrameTimeMs);
        if (MeasureDefs.textStatFlush.equals(token)) return latestStats != null ? String.valueOf(latestStats.getStatFlushCount()) : "0";
        if (MeasureDefs.textWaitResLoad.equals(token)) return latestStats != null ? String.valueOf(latestStats.getFrameResourcesLoadingCount()) : "0";
        if (MeasureDefs.textResLoad.equals(token)) return latestStats != null ? String.valueOf(latestStats.getStatResourceLoadCount()) : "0";
        if (MeasureDefs.textGradualResLoad.equals(token)) return latestStats != null ? String.valueOf(latestStats.getStatGradualResourceLoadCount()) : "0";
        if (MeasureDefs.textRTSwitch.equals(token)) return latestStats != null ? String.valueOf(latestStats.getStatRTSwitchCount()) : "0";
        return token;
    }

    private static String formatTime(long ms) {
        long s = Math.max(0L, ms / 1000L);
        return String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    @Override
    public Vec2f onRequestMeasureVec2f(String str, Vec2f arg, Vec2f last, MeterOutputData meterOut, IRenderState rs) {
        if (str == null || str.isEmpty()) return new Vec2f(arg.x, arg.y);
        Vec2f prev = last != null ? last : new Vec2f(0f, 0f);
        float frameTimeF = rs != null ? rs.getFrameTimeF() : 0.016f;
        float totalFrameTimeF = rs != null ? rs.getTotalFrameTimeF() : totalTimeSec;
        float rms = meterOut != null ? meterOut.frameDataRmsValue : 0f;

        if (MeasureDefs.Nothing.equals(str)) return new Vec2f(0f, 0f);
        if (MeasureDefs.Constant.equals(str)) return new Vec2f(arg.x, arg.y);
        if (MeasureDefs.isPlaying.equals(str)) return isPlaying() ? new Vec2f(1f, 1f) : new Vec2f(0f, 0f);
        if (MeasureDefs.beat.equals(str)) return new Vec2f(rms * arg.x, rms * arg.y);
        if (MeasureDefs.totalTime.equals(str)) return new Vec2f((arg.x * totalFrameTimeF) % 1.0f, (arg.y * totalFrameTimeF) % 1.0f);
        if (MeasureDefs.totalTimeBackward.equals(str)) return new Vec2f(1f - ((arg.x * totalFrameTimeF) % 1.0f), 1f - ((arg.y * totalFrameTimeF) % 1.0f));
        if (MeasureDefs.totalTimeWhenPlaying.equals(str)) return new Vec2f((totalTimeWhenPlaying * arg.x) % 1.0f, (totalTimeWhenPlaying * arg.y) % 1.0f);
        if (MeasureDefs.totalTimeAndBeat.equals(str)) {
            float v = prev.x;
            if (isPlaying()) v = v + (frameTimeF * arg.x) + (rms * arg.y * 0.1f);
            return new Vec2f(v, v);
        }
        if (MeasureDefs.trackPosition.equals(str)) {
            float p = durationMs > 0 ? Math.max(0f, Math.min(1f, (float) getPositionMs() / (float) durationMs)) : 0f;
            return new Vec2f(arg.x * p, arg.y * p);
        }
        if (MeasureDefs.beatRandomShake.equals(str)) {
            if (!isPlaying() && last != null) return new Vec2f(last.x, last.y);
            float m = Math.max(0f, Math.min(1f, rms * 2.5f));
            float m3 = m * m * m;
            return new Vec2f(shakePointSmooth.x * m3 * arg.x * 0.025f, shakePointSmooth.y * m3 * arg.x * 0.025f);
        }
        if (MeasureDefs.beatCamShakeMore.equals(str)) {
            if (!isPlaying() && last != null) return new Vec2f(last.x, last.y);
            float max = Math.max(Math.min(rms * 2.5f, 1.0f), 0.0f);
            float f3 = max * max;
            Vec2f pos = handheldMotionLotOfShake.getPos((((totalFrameTimeF * 0.5f) * max) * arg.y) % 1.0f);
            pos.x = (pos.x - 0.5f) * arg.x * f3 * 0.06f;
            pos.y = (pos.y - 0.5f) * arg.x * f3 * 0.06f;
            Interpolate.Lerp(pos, prev, pos, 0.800000011920929d);
            return pos;
        }
        if (MeasureDefs.beatCamShakeLess.equals(str)) {
            if (!isPlaying() && last != null) return new Vec2f(last.x, last.y);
            float max = Math.max(Math.min(rms * 2.5f, 1.0f), 0.0f);
            float f3 = max * max;
            Vec2f pos = handheldMotionSmooth.getPos((((totalFrameTimeF * 0.5f) * max) * arg.y) % 1.0f);
            pos.x = (pos.x - 0.5f) * arg.x * f3 * 0.06f;
            pos.y = (pos.y - 0.5f) * arg.x * f3 * 0.06f;
            Interpolate.Lerp(pos, prev, pos, 0.800000011920929d);
            return pos;
        }
        if (MeasureDefs.constantCamShakeMore.equals(str)) {
            Vec2f pos = handheldMotionLotOfShake.getPos(((totalFrameTimeF * 0.5f) * arg.y) % 1.0f);
            pos.x = (pos.x - 0.5f) * arg.x * 0.06f;
            pos.y = (pos.y - 0.5f) * arg.x * 0.06f;
            Interpolate.Lerp(pos, prev, pos, 0.10000000149011612d);
            return pos;
        }
        if (MeasureDefs.constantCamShakeLess.equals(str)) {
            Vec2f pos = handheldMotionSmooth.getPos(((totalFrameTimeF * 0.5f) * arg.y) % 1.0f);
            pos.x = (pos.x - 0.5f) * arg.x * 0.06f;
            pos.y = (pos.y - 0.5f) * arg.x * 0.06f;
            Interpolate.Lerp(pos, prev, pos, 0.10000000149011612d);
            return pos;
        }
        if (MeasureDefs.constantCamShakeRotMore.equals(str)) {
            double rot = handheldMotionLotOfShake.getRot(((totalFrameTimeF * 0.5f) * arg.y) % 1.0f);
            float lerp = Interpolate.Lerp(prev.x, ((float) (rot / 6.283185307179586d)) * arg.x * 0.5f, 0.10000000149011612d);
            return new Vec2f(lerp, lerp);
        }
        if (MeasureDefs.constantCamShakeRotLess.equals(str)) {
            double rot = handheldMotionSmooth.getRot(((totalFrameTimeF * 0.5f) * arg.y) % 1.0f);
            float lerp = Interpolate.Lerp(prev.x, ((float) (rot / 6.283185307179586d)) * arg.x * 0.5f, 0.10000000149011612d);
            return new Vec2f(lerp, lerp);
        }
        if (MeasureDefs.beatCamShakeRotMore.equals(str)) {
            if (!isPlaying() && last != null) return new Vec2f(last.x, last.y);
            float max = Math.max(Math.min(rms * 2.5f, 1.0f), 0.0f);
            double rot = handheldMotionLotOfShake.getRot(((totalFrameTimeF * 0.5f) * arg.y) % 1.0f);
            float lerp = Interpolate.Lerp(prev.x, ((float) (rot / 6.283185307179586d)) * arg.x * 0.5f * max * max, 0.800000011920929d);
            return new Vec2f(lerp, lerp);
        }
        if (MeasureDefs.beatCamShakeRotLess.equals(str)) {
            if (!isPlaying() && last != null) return new Vec2f(last.x, last.y);
            float max = Math.max(Math.min(rms * 2.5f, 1.0f), 0.0f);
            double rot = handheldMotionSmooth.getRot(((totalFrameTimeF * 0.5f) * arg.y) % 1.0f);
            float lerp = Interpolate.Lerp(prev.x, ((float) (rot / 6.283185307179586d)) * arg.x * 0.5f * max * max, 0.800000011920929d);
            return new Vec2f(lerp, lerp);
        }
        if (MeasureDefs.peakBarIndex.equals(str) && meterOut != null) {
            float v = Interpolate.Lerp(meterOut.peakBarIndex, prev.x, arg.y);
            return new Vec2f(arg.x * v, arg.x * v);
        }
        if (MeasureDefs.beatTriggerAnim.equals(str)) {
            if (isPlaying() && rms > arg.x && prev.y >= 1.9999999f) {
                prev.x = 0f; prev.y = 0f;
            }
            float f7 = prev.x + (arg.y * frameTimeF);
            float f2 = 0.5f, f = 0f;
            if (f7 >= 0.4999999f) f = prev.y + (frameTimeF * arg.y);
            else f2 = f7;
            return new Vec2f(f2, f);
        }
        return prev;
    }

    @Override
    public AlbumArtRequest onRequestsAlbumArtPath() {
        String art = (embeddedArtPath != null && !embeddedArtPath.isEmpty())
                ? embeddedArtPath
                : "";
        return new AlbumArtRequest(android.net.Uri.EMPTY, art, "", currentTitle);
    }

    private static String extractEmbeddedAlbumArt(File songFile) {
        try {
            File tmp = File.createTempFile("avee_albumart_", ".png");
            tmp.deleteOnExit();
            Process p = new ProcessBuilder(
                    FfmpegLocator.ffmpeg(), "-y", "-v", "error", "-i", songFile.getAbsolutePath(),
                    "-an", "-vframes", "1", "-f", "image2", "-vcodec", "png",
                    tmp.getAbsolutePath())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            p.waitFor();
            if (tmp.exists() && tmp.length() > 64) {
                return tmp.getAbsolutePath();
            }
            tmp.delete();
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Override
    public void onRequestAlbumArtPathAndBitmap(ImageLoadedListener listener, AlbumArtRequest2 req) {
        if (req == null) return;
        AlbumArtCore.getInstance().loadAlbumArtLarge(
                false, req.videoThumbDataSource, req.path0, req.path1, req.genStr,
                req.fitCenter, listener, req.targetBoundsWidth, req.targetBoundsHeight,
                req.genBoundsWidth, req.genBoundsHeight,
                req.genHint, req.genHint2, true, req.imageColorKeyParams, null);
    }

    @Override public int onRequestAntiAliasingValue() { return 1; }
    @Override public int onRequestAudioProcessMultiThreadValue() { return 0; }
    @Override public int onRequestConvertDpToPx(int dp) { return dp; }
    @Override public int onRequestOverrideAudioFrameSamplesInCount() { return 0; }
    @Override public TrackDurationInfo onRequestTrackDurationInfo() {
        TrackDurationInfo info = new TrackDurationInfo();
        info.durationMs = (float) durationMs;
        info.trackPositionMs = (float) getPositionMs();
        info.progress = durationMs > 0 ? (float) getPositionMs() / (float) durationMs : 0f;
        return info;
    }
    @Override public boolean onRequestUseHighQualityBlur() { return highQualityBlur; }
    @Override public void onOffScreenContentRendered(Texture texture, boolean z, String str) {}
    @Override public void onThemeChanged() {}
}

package com.daaw.avee.editor;

import com.daaw.avee.Common.Interpolate;
import com.daaw.avee.Common.Vec2f;
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
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import mdesl.graphics.Texture;

public class DesktopAudioEngine implements InternalVisualizationDataProvider {
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;
    public static final int RING_FRAMES = 131072; // ~2.97s stereo PCM = 512 KB constant RAM
    private static final int STREAM_CHUNK_FRAMES = 4096;
    private static final int LIVE_AHEAD_TARGET_FRAMES = 24576;
    private static final int LIVE_AHEAD_LOW_FRAMES = 16384;
    private static final Pattern DURATION_PATTERN = Pattern.compile("Duration:\\s*(\\d+):(\\d+):(\\d+(?:\\.\\d+)?)");

    // Fixed circular PCM buffer; never allocates per-song full-track arrays
    private final short[] ringBuffer = new short[RING_FRAMES * CHANNELS];
    private long ringStartFrame = 0L;
    private long ringEndFrame = 0L;
    private long decoderFramePos = 0L;
    private boolean decoderEof = false;

    private volatile long totalFrames = 0L;
    private volatile long durationMs = 0L;
    private volatile long playheadFrame = 0L;
    private volatile boolean playing = false;
    private volatile boolean loading = false;
    private volatile boolean muted = false;
    private volatile float volume = 1.0f;
    private volatile boolean syntheticBeat = false;
    private volatile boolean highQualityBlur = true;
    private volatile long seekRevision = 0L;
    private volatile boolean seekRequested = false;
    private volatile boolean flushLineRequested = false;
    private volatile long completedCount = 0L;
    private volatile String currentTitle = "No Audio Loaded";
    private volatile String currentArtist = "Avee Desktop Editor";
    private volatile File audioFile;
    private volatile String embeddedArtPath = null;

    private final AudioFrameData frameData = new AudioFrameData();
    private final Object playLock = new Object();
    private final Object decoderLock = new Object();
    private Process decoderProc = null;
    private InputStream decoderIn = null;
    private final byte[] ioScratch = new byte[16384];
    private final byte[] partialFrameBuf = new byte[4];
    private int partialFrameBytes = 0;

    private final boolean hasPlaybackThread;
    private Thread audioThread;
    private final AtomicLong loadGeneration = new AtomicLong(0L);
    private final ExecutorService loadExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "AveeAudioLoader");
        t.setDaemon(true);
        return t;
    });
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
        this.hasPlaybackThread = startPlaybackThread;
        if (startPlaybackThread) {
            startPlaybackThread();
        }
    }

    public void setLatestStats(FrameStats stats) {
        this.latestStats = stats;
    }

    public boolean isLoading() {
        return loading;
    }

    public long getEstimatedAudioBufferBytes() {
        return (long) ringBuffer.length * 2L;
    }

    public void unloadCurrentTrack() {
        loadGeneration.incrementAndGet();
        synchronized (decoderLock) {
            closeDecoderLocked();
        }
        String oldArt;
        synchronized (playLock) {
            Arrays.fill(ringBuffer, (short) 0);
            ringStartFrame = 0L;
            ringEndFrame = 0L;
            decoderFramePos = 0L;
            decoderEof = false;
            playheadFrame = 0L;
            totalFrames = 0L;
            durationMs = 0L;
            playing = false;
            loading = false;
            audioFile = null;
            oldArt = embeddedArtPath;
            embeddedArtPath = null;
            currentTitle = "No Audio Loaded";
            seekRevision++;
            seekRequested = false;
            flushLineRequested = true;
            AppDesign.setTrackPositionMs(0L);
        }
        deleteTempFile(oldArt);
    }

    public boolean loadAudioFile(File file) {
        long gen = loadGeneration.incrementAndGet();
        return loadAudioFileInternal(file, gen, true);
    }

    public void loadAudioFileAsync(File file, Consumer<Boolean> onComplete) {
        if (file == null || !file.exists()) {
            if (onComplete != null) onComplete.accept(Boolean.FALSE);
            return;
        }
        long gen = loadGeneration.incrementAndGet();
        // Clear old track buffers & stop prior decoder immediately without blocking UI
        synchronized (decoderLock) {
            closeDecoderLocked();
        }
        String oldArt;
        synchronized (playLock) {
            Arrays.fill(ringBuffer, (short) 0);
            ringStartFrame = 0L;
            ringEndFrame = 0L;
            decoderFramePos = 0L;
            decoderEof = false;
            playheadFrame = 0L;
            totalFrames = 0L;
            durationMs = 0L;
            playing = false;
            loading = true;
            audioFile = file;
            oldArt = embeddedArtPath;
            embeddedArtPath = null;
            currentTitle = extractBaseTitle(file);
            currentArtist = "Local Audio";
            seekRevision++;
            seekRequested = false;
            flushLineRequested = true;
            AppDesign.setTrackPositionMs(0L);
        }
        deleteTempFile(oldArt);

        loadExecutor.submit(() -> {
            boolean ok = false;
            try {
                ok = loadAudioFileInternal(file, gen, false);
            } catch (Throwable t) {
                tlog.w("Async audio load failed: " + t.getMessage());
                ok = false;
            }
            if (onComplete != null && !shutdown && gen == loadGeneration.get()) {
                onComplete.accept(Boolean.valueOf(ok));
            }
        });
    }

    private boolean loadAudioFileInternal(File file, long gen, boolean syncArtBeforeReturn) {
        if (file == null || !file.exists() || !file.isFile()) {
            if (gen == loadGeneration.get()) loading = false;
            return false;
        }
        try {
            long probedMs = probeDurationMs(file);
            if (gen != loadGeneration.get() || shutdown) {
                return false;
            }

            synchronized (decoderLock) {
                if (gen != loadGeneration.get() || shutdown) {
                    return false;
                }
                openDecoderAtFrameLocked(file, 0L);
                // Read a small initial prebuffer (~185ms) so audio starts instantly in ~8KB I/O
                int prebufTarget = 8192;
                int prebufTotal = 0;
                while (prebufTotal < prebufTarget && gen == loadGeneration.get() && !shutdown) {
                    int n = readDecodedFramesLocked(Math.min(STREAM_CHUNK_FRAMES, prebufTarget - prebufTotal));
                    if (n <= 0) break;
                    prebufTotal += n;
                }
                if (gen != loadGeneration.get() || shutdown) {
                    closeDecoderLocked();
                    return false;
                }
                long framesAvailable;
                synchronized (playLock) {
                    framesAvailable = ringEndFrame - ringStartFrame;
                }
                if (framesAvailable <= 0L && probedMs <= 0L) {
                    closeDecoderLocked();
                    synchronized (playLock) {
                        loading = false;
                        audioFile = null;
                        currentTitle = "No Audio Loaded";
                    }
                    return false;
                }

                long estTotalFrames = probedMs > 0L ? (probedMs * SAMPLE_RATE) / 1000L : Math.max(framesAvailable, 1L);
                long effDurMs = probedMs > 0L ? probedMs : ((estTotalFrames * 1000L) / SAMPLE_RATE);

                String oldArt;
                synchronized (playLock) {
                    if (gen != loadGeneration.get() || shutdown) {
                        closeDecoderLocked();
                        return false;
                    }
                    this.audioFile = file;
                    this.totalFrames = estTotalFrames;
                    this.durationMs = effDurMs;
                    this.playheadFrame = 0L;
                    oldArt = this.embeddedArtPath;
                    this.embeddedArtPath = null;
                    this.currentTitle = extractBaseTitle(file);
                    this.currentArtist = "Local Audio";
                    this.syntheticBeat = false;
                    this.playing = true;
                    this.loading = false;
                    this.seekRevision++;
                    this.seekRequested = false;
                    this.flushLineRequested = true;
                    AppDesign.setTrackPositionMs(0L);
                }
                deleteTempFile(oldArt);
            }

            // Extract embedded cover art off the playback path so playback starts without waiting
            if (syncArtBeforeReturn) {
                String art = extractEmbeddedAlbumArt(file);
                if (gen == loadGeneration.get() && !shutdown) {
                    String prev = this.embeddedArtPath;
                    this.embeddedArtPath = art;
                    if (prev != null && !prev.equals(art)) deleteTempFile(prev);
                } else {
                    deleteTempFile(art);
                }
            } else {
                String art = extractEmbeddedAlbumArt(file);
                if (gen == loadGeneration.get() && !shutdown) {
                    String prev = this.embeddedArtPath;
                    this.embeddedArtPath = art;
                    if (prev != null && !prev.equals(art)) deleteTempFile(prev);
                } else {
                    deleteTempFile(art);
                }
            }
            return true;
        } catch (Throwable t) {
            tlog.w("Audio load failed: " + t.getMessage());
            synchronized (playLock) {
                if (gen == loadGeneration.get()) {
                    loading = false;
                }
            }
            return false;
        }
    }

    private static String extractBaseTitle(File file) {
        String n = file.getName();
        int dot = n.lastIndexOf('.');
        return dot > 0 ? n.substring(0, dot) : n;
    }

    private static void deleteTempFile(String path) {
        if (path != null && !path.isEmpty()) {
            try {
                new File(path).delete();
            } catch (Throwable ignored) {}
        }
    }

    private void closeDecoderLocked() {
        partialFrameBytes = 0;
        if (decoderIn != null) {
            try { decoderIn.close(); } catch (Throwable ignored) {}
            decoderIn = null;
        }
        if (decoderProc != null) {
            try { decoderProc.destroyForcibly(); } catch (Throwable ignored) {}
            decoderProc = null;
        }
    }

    private void openDecoderAtFrameLocked(File file, long startFrame) throws Exception {
        closeDecoderLocked();
        if (file == null || !file.isFile()) return;
        long clampedStart = Math.max(0L, startFrame);
        ProcessBuilder pb;
        if (clampedStart > 0L) {
            String startSec = String.format(Locale.US, "%.6f", clampedStart / (double) SAMPLE_RATE);
            pb = new ProcessBuilder(
                    FfmpegLocator.ffmpeg(), "-v", "error", "-ss", startSec,
                    "-i", file.getAbsolutePath(),
                    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2", "-ar", String.valueOf(SAMPLE_RATE),
                    "pipe:1");
        } else {
            pb = new ProcessBuilder(
                    FfmpegLocator.ffmpeg(), "-v", "error",
                    "-i", file.getAbsolutePath(),
                    "-f", "s16le", "-acodec", "pcm_s16le", "-ac", "2", "-ar", String.valueOf(SAMPLE_RATE),
                    "pipe:1");
        }
        pb.redirectError(ProcessBuilder.Redirect.DISCARD);
        decoderProc = pb.start();
        decoderIn = decoderProc.getInputStream();
        synchronized (playLock) {
            Arrays.fill(ringBuffer, (short) 0);
            ringStartFrame = clampedStart;
            ringEndFrame = clampedStart;
            decoderFramePos = clampedStart;
            decoderEof = false;
        }
    }

    /**
     * Reads up to maxFrames stereo PCM frames from the active ffmpeg pipe and pushes into ringBuffer.
     * Caller MUST hold decoderLock and NOT hold playLock.
     */
    private int readDecodedFramesLocked(int maxFrames) {
        InputStream in = decoderIn;
        if (in == null || maxFrames <= 0) return 0;

        int capBytes = Math.min(ioScratch.length - partialFrameBytes, maxFrames * 4);
        if (capBytes < 4) capBytes = Math.min(ioScratch.length - partialFrameBytes, 4096);
        int nRead;
        try {
            if (partialFrameBytes > 0) {
                System.arraycopy(partialFrameBuf, 0, ioScratch, 0, partialFrameBytes);
            }
            nRead = in.read(ioScratch, partialFrameBytes, capBytes);
        } catch (Throwable t) {
            nRead = -1;
        }

        if (nRead < 0) {
            closeDecoderLocked();
            synchronized (playLock) {
                decoderEof = true;
                if (decoderFramePos > 0L && (totalFrames == 0L || decoderFramePos < totalFrames)) {
                    totalFrames = decoderFramePos;
                    durationMs = (totalFrames * 1000L) / SAMPLE_RATE;
                }
            }
            return -1;
        }
        if (nRead == 0) {
            return 0;
        }

        int totalBytes = partialFrameBytes + nRead;
        int frames = totalBytes / 4;
        int consumedBytes = frames * 4;
        int leftover = totalBytes - consumedBytes;
        if (leftover > 0) {
            System.arraycopy(ioScratch, consumedBytes, partialFrameBuf, 0, leftover);
        }
        partialFrameBytes = leftover;
        if (frames <= 0) {
            return 0;
        }

        synchronized (playLock) {
            ByteBuffer bb = ByteBuffer.wrap(ioScratch, 0, consumedBytes).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < frames; i++) {
                short l = bb.getShort();
                short r = bb.getShort();
                int slot = (int) (decoderFramePos % RING_FRAMES);
                ringBuffer[slot * 2] = l;
                ringBuffer[slot * 2 + 1] = r;
                decoderFramePos++;
            }
            ringEndFrame = decoderFramePos;
            if (ringEndFrame - ringStartFrame > RING_FRAMES) {
                ringStartFrame = ringEndFrame - RING_FRAMES;
            }
        }
        return frames;
    }

    private long probeDurationMs(File file) {
        try {
            Process p = new ProcessBuilder(
                    FfmpegLocator.ffprobe(), "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    file.getAbsolutePath())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            int rc = p.waitFor();
            if (rc == 0 && !out.isEmpty() && !"N/A".equalsIgnoreCase(out)) {
                double sec = Double.parseDouble(out.split("\\s+")[0]);
                if (!Double.isNaN(sec) && !Double.isInfinite(sec) && sec > 0.0) {
                    return Math.round(sec * 1000.0);
                }
            }
        } catch (Throwable ignored) {}

        // Fallback to parsing ffmpeg -i stderr Duration: HH:MM:SS.xx
        try {
            Process p = new ProcessBuilder(FfmpegLocator.ffmpeg(), "-i", file.getAbsolutePath())
                    .redirectErrorStream(true)
                    .start();
            byte[] bytes = p.getInputStream().readAllBytes();
            p.waitFor();
            String text = new String(bytes, StandardCharsets.UTF_8);
            Matcher m = DURATION_PATTERN.matcher(text);
            if (m.find()) {
                long h = Long.parseLong(m.group(1));
                long min = Long.parseLong(m.group(2));
                double s = Double.parseDouble(m.group(3));
                double totalSec = h * 3600.0 + min * 60.0 + s;
                if (totalSec > 0.0) {
                    return Math.round(totalSec * 1000.0);
                }
            }
        } catch (Throwable ignored) {}
        return 0L;
    }

    private void ensureOfflineWindowBuffered(long centerFrame, int halfWindowFrames) {
        File curFile;
        long totFrames;
        long rStart;
        long rEnd;
        boolean eof;
        synchronized (playLock) {
            curFile = this.audioFile;
            totFrames = this.totalFrames;
            rStart = this.ringStartFrame;
            rEnd = this.ringEndFrame;
            eof = this.decoderEof;
        }
        if (curFile == null || totFrames <= 0L) return;

        long needStart = Math.max(0L, centerFrame - halfWindowFrames);
        long needEnd = Math.min(totFrames, centerFrame + halfWindowFrames);
        if (needEnd <= needStart) needEnd = Math.min(totFrames, needStart + 1L);

        if (needStart >= rStart && (needEnd <= rEnd || eof)) {
            return;
        }

        synchronized (decoderLock) {
            synchronized (playLock) {
                curFile = this.audioFile;
                totFrames = this.totalFrames;
                rStart = this.ringStartFrame;
                rEnd = this.ringEndFrame;
                eof = this.decoderEof;
            }
            if (curFile == null) return;
            needEnd = Math.min(Math.max(totFrames, centerFrame + halfWindowFrames), centerFrame + halfWindowFrames);
            if (needStart >= rStart && (needEnd <= rEnd || eof)) {
                return;
            }

            boolean canAdvance = (decoderIn != null) && !eof
                    && needStart >= rStart
                    && (needEnd - rEnd) <= (long) SAMPLE_RATE * 6L;
            if (!canAdvance) {
                try {
                    openDecoderAtFrameLocked(curFile, needStart);
                } catch (Throwable t) {
                    tlog.w("Offline decoder seek failed: " + t.getMessage());
                    return;
                }
            }
            while (!shutdown) {
                synchronized (playLock) {
                    if (this.ringEndFrame >= needEnd || this.decoderEof) break;
                }
                int n = readDecodedFramesLocked(STREAM_CHUNK_FRAMES);
                if (n < 0) break;
            }
        }
    }

    private void startPlaybackThread() {
        final byte[] chunk = new byte[1024 * CHANNELS * 2];
        audioThread = new Thread(() -> {
            SourceDataLine line = null;
            try {
                AudioFormat fmt = new AudioFormat(SAMPLE_RATE, 16, CHANNELS, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, fmt);
                if (AudioSystem.isLineSupported(info)) {
                    line = (SourceDataLine) AudioSystem.getLine(info);
                    line.open(fmt, 16384);
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

                if (flushLineRequested) {
                    flushLineRequested = false;
                    if (line != null) line.flush();
                }

                // Handle seek / loop-restart asynchronously off the UI thread
                if (seekRequested) {
                    File targetFile;
                    long targetFrame;
                    long targetSeekRev;
                    synchronized (playLock) {
                        targetFile = this.audioFile;
                        targetFrame = this.playheadFrame;
                        targetSeekRev = this.seekRevision;
                        seekRequested = false;
                    }
                    if (targetFile != null) {
                        boolean needReopen;
                        synchronized (playLock) {
                            needReopen = targetFrame < this.ringStartFrame || targetFrame >= this.ringEndFrame;
                        }
                        if (needReopen) {
                            synchronized (decoderLock) {
                                long origin = Math.max(0L, targetFrame - 4096L);
                                try {
                                    openDecoderAtFrameLocked(targetFile, origin);
                                    long fillTarget = targetFrame + 8192L;
                                    while (!shutdown && this.seekRevision == targetSeekRev) {
                                        synchronized (playLock) {
                                            if (this.ringEndFrame >= fillTarget || this.decoderEof) break;
                                        }
                                        if (readDecodedFramesLocked(STREAM_CHUNK_FRAMES) < 0) break;
                                    }
                                } catch (Throwable t) {
                                    tlog.w("Stream seek failed: " + t.getMessage());
                                }
                            }
                        }
                        if (line != null) line.flush();
                    }
                    continue;
                }

                File curFile;
                long curFrame;
                long totFrames;
                long iterSeekRev;
                long rStart;
                long rEnd;
                boolean eof;
                boolean isPlay;
                boolean isMute;
                boolean isLoad;
                synchronized (playLock) {
                    curFile = this.audioFile;
                    curFrame = this.playheadFrame;
                    totFrames = this.totalFrames;
                    iterSeekRev = this.seekRevision;
                    rStart = this.ringStartFrame;
                    rEnd = this.ringEndFrame;
                    eof = this.decoderEof;
                    isPlay = this.playing;
                    isMute = this.muted;
                    isLoad = this.loading;
                }

                // Top up streaming ring buffer ahead of playhead without blocking on lock
                if (isPlay && !isLoad && curFile != null && !eof && (rEnd - curFrame) < LIVE_AHEAD_LOW_FRAMES) {
                    synchronized (decoderLock) {
                        while (!shutdown && this.seekRevision == iterSeekRev && !seekRequested) {
                            long endNow;
                            boolean eofNow;
                            synchronized (playLock) {
                                endNow = this.ringEndFrame;
                                eofNow = this.decoderEof;
                            }
                            if (eofNow || (endNow - curFrame) >= LIVE_AHEAD_TARGET_FRAMES) break;
                            if (readDecodedFramesLocked(STREAM_CHUNK_FRAMES) <= 0) break;
                        }
                    }
                    continue;
                }

                if (isPlay && !isLoad && totFrames > 0) {
                    long endBoundary = eof ? rEnd : totFrames;
                    int framesAvailable = (int) Math.max(0L, Math.min(rEnd, endBoundary) - curFrame);
                    if (curFrame >= endBoundary) {
                        synchronized (playLock) {
                            if (this.seekRevision == iterSeekRev) {
                                this.completedCount++;
                                this.playheadFrame = 0L;
                                this.seekRevision++;
                                this.seekRequested = true;
                                AppDesign.setTrackPositionMs(0L);
                            }
                        }
                        continue;
                    }
                    if (framesAvailable <= 0 && !eof) {
                        try { Thread.sleep(4); } catch (InterruptedException ignored) {}
                        continue;
                    }

                    int framesThisStep = Math.min(1024, Math.max(1, framesAvailable));
                    if (line != null) {
                        int bytes = framesThisStep * CHANNELS * 2;
                        float gain = isMute ? 0.0f : this.volume;
                        synchronized (playLock) {
                            if (this.seekRevision != iterSeekRev) continue;
                            for (int i = 0; i < framesThisStep; i++) {
                                long fPos = curFrame + i;
                                short l = 0, r = 0;
                                if (fPos >= this.ringStartFrame && fPos < this.ringEndFrame) {
                                    int slot = (int) (fPos % RING_FRAMES);
                                    l = ringBuffer[slot * 2];
                                    r = ringBuffer[slot * 2 + 1];
                                }
                                int sl = gain <= 0.0f ? 0 : Math.max(-32768, Math.min(32767, Math.round(l * gain)));
                                int sr = gain <= 0.0f ? 0 : Math.max(-32768, Math.min(32767, Math.round(r * gain)));
                                int bi = i * 4;
                                chunk[bi] = (byte) (sl & 0xFF);
                                chunk[bi + 1] = (byte) ((sl >> 8) & 0xFF);
                                chunk[bi + 2] = (byte) (sr & 0xFF);
                                chunk[bi + 3] = (byte) ((sr >> 8) & 0xFF);
                            }
                        }
                        line.write(chunk, 0, bytes);
                    } else {
                        try { Thread.sleep(16); } catch (InterruptedException ignored) {}
                        framesThisStep = Math.min(framesAvailable, Math.max(1, (int) (dtSec * SAMPLE_RATE)));
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
        loadExecutor.shutdownNow();
        unloadCurrentTrack();
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
        long targetFrame;
        synchronized (playLock) {
            if (totalFrames > 0) {
                long f = (posMs * SAMPLE_RATE) / 1000L;
                targetFrame = Math.max(0L, Math.min(totalFrames - 1, f));
                this.playheadFrame = targetFrame;
                this.seekRevision++;
            } else {
                targetFrame = 0L;
            }
        }
        AppDesign.setTrackPositionMs(posMs);
        if (!hasPlaybackThread && totalFrames > 0) {
            ensureOfflineWindowBuffered(targetFrame, 8192);
        }
        tickFrame(dtSec);
    }

    public void seekMs(long ms) {
        long targetFrame = -1L;
        synchronized (playLock) {
            if (totalFrames > 0) {
                long f = (ms * SAMPLE_RATE) / 1000L;
                targetFrame = Math.max(0L, Math.min(totalFrames - 1, f));
                this.playheadFrame = targetFrame;
                this.seekRevision++;
                if (hasPlaybackThread) {
                    this.seekRequested = true;
                }
                AppDesign.setTrackPositionMs((this.playheadFrame * 1000L) / SAMPLE_RATE);
            }
        }
        if (!hasPlaybackThread && targetFrame >= 0L) {
            ensureOfflineWindowBuffered(targetFrame, 8192);
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

        long curTotFrames = this.totalFrames;
        long offsetFrames = (offsetMs * (long) SAMPLE_RATE) / 1000L;
        long posFrame = curTotFrames > 0 ? Math.max(0L, Math.min(curTotFrames - 1, this.playheadFrame + offsetFrames)) : 0L;

        if (!hasPlaybackThread && curTotFrames > 0 && !syntheticBeat) {
            ensureOfflineWindowBuffered(posFrame, count / 2 + 1024);
        }

        double rmsAccum = 0.0;
        short minS = Short.MAX_VALUE;
        short maxS = 0;

        if (curTotFrames > 0 && !syntheticBeat) {
            synchronized (playLock) {
                long rStart = this.ringStartFrame;
                long rEnd = this.ringEndFrame;
                for (int i = 0; i < count; i++) {
                    long fIdx = Math.max(0L, Math.min(curTotFrames - 1, posFrame - (count / 2) + i));
                    short l = 0;
                    short r = 0;
                    if (fIdx >= rStart && fIdx < rEnd) {
                        int slot = (int) (fIdx % RING_FRAMES);
                        l = ringBuffer[slot * 2];
                        r = ringBuffer[slot * 2 + 1];
                    }
                    dst[i * 2] = l;
                    dst[i * 2 + 1] = r;
                    float mono = (l + r) * 0.5f;
                    rmsAccum += mono * mono;
                    short abs = (short) Math.min(Short.MAX_VALUE, Math.abs(l));
                    if (abs < minS) minS = abs;
                    if (abs > maxS) maxS = abs;
                }
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
        } catch (Throwable ignored) {}
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

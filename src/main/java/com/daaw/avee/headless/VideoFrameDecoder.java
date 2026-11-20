package com.daaw.avee.headless;

import android.opengl.GLES20;
import com.daaw.avee.Common.tlog;
import com.daaw.avee.comp.AlbumArt.AlbumArtCore;
import com.daaw.avee.comp.Visualizer.Graphic.RenderState;
import com.daaw.avee.comp.Visualizer.Graphic.VFrameBuffer;
import com.daaw.avee.comp.Visualizer.Graphic.VTexture;
import com.daaw.avee.editor.FfmpegLocator;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;
import mdesl.graphics.ITexture;
import mdesl.graphics.Texture;

public class VideoFrameDecoder {
    private final String rawPath;
    private File sourceFile;
    private int width = 640;
    private int height = 360;
    private long durationUs = 0L;
    private boolean probed = false;
    private boolean failed = false;

    private VFrameBuffer videoFbo;
    private Texture frameTex;
    private long lastDecodedTimeUs = Long.MIN_VALUE;
    private ByteBuffer rgbaUploadBuf;

    public VideoFrameDecoder(String path) {
        this.rawPath = path != null ? path : "";
    }

    public static boolean isVideoPath(String path) {
        if (path == null) return false;
        String lower = path.trim().toLowerCase(Locale.ROOT);
        return lower.endsWith(".mp4") || lower.endsWith(".mkv") || lower.endsWith(".webm")
                || lower.endsWith(".mov") || lower.endsWith(".m4v") || lower.endsWith(".avi")
                || lower.endsWith(".flv") || lower.endsWith(".3gp") || lower.endsWith(".mpeg")
                || lower.endsWith(".mpg") || lower.endsWith(".gif");
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }

    private boolean ensureProbed() {
        if (probed) return !failed;
        probed = true;
        sourceFile = AlbumArtCore.resolveLocalOrFile(rawPath);
        if (sourceFile == null || !sourceFile.exists() || !sourceFile.isFile()) {
            failed = true;
            return false;
        }
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    FfmpegLocator.ffprobe(), "-v", "error", "-select_streams", "v:0",
                    "-show_entries", "stream=width,height,duration",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    sourceFile.getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            String[] lines = out.split("\\R");
            if (lines.length >= 2) {
                width = Math.max(2, Integer.parseInt(lines[0].trim()));
                height = Math.max(2, Integer.parseInt(lines[1].trim()));
            }
            for (int li = 2; li < lines.length; li++) {
                String val = lines[li].trim();
                if (!val.isEmpty() && !"N/A".equalsIgnoreCase(val)) {
                    try {
                        double sec = Double.parseDouble(val);
                        if (sec > 0.0) {
                            durationUs = (long) (sec * 1_000_000.0);
                            break;
                        }
                    } catch (NumberFormatException ignored) {}
                }
            }
            // Clamp huge 4K videos for responsive desktop preview
            if (width > 1280 || height > 1280) {
                float s = Math.min(1280f / width, 1280f / height);
                width = Math.max(2, Math.round(width * s) & ~1);
                height = Math.max(2, Math.round(height * s) & ~1);
            }
            return true;
        } catch (Throwable t) {
            tlog.w("ffprobe failed for " + rawPath + ": " + t.getMessage());
            failed = true;
            return false;
        }
    }

    public VFrameBuffer getFrameAtTime(RenderState renderState, long targetTimeUs, boolean loop) {
        if (!ensureProbed()) return null;

        long effectiveUs = Math.max(0L, targetTimeUs);
        if (durationUs > 0L) {
            if (loop) effectiveUs = effectiveUs % durationUs;
            else if (effectiveUs > durationUs) effectiveUs = durationUs;
            effectiveUs = Math.min(effectiveUs, Math.max(0L, durationUs - 20000L));
        }

        if (videoFbo != null && Math.abs(effectiveUs - lastDecodedTimeUs) < 30000L) {
            return videoFbo;
        }

        byte[] rgba = decodeSingleFrameRgba(effectiveUs);
        if (rgba == null || rgba.length < width * height * 4) {
            return videoFbo;
        }

        if (videoFbo == null) {
            videoFbo = VFrameBuffer.createSafe(width, height, Texture.LINEAR, Texture.CLAMP_TO_EDGE, false);
            if (videoFbo == null) return null;
        }
        if (rgbaUploadBuf == null || rgbaUploadBuf.capacity() < rgba.length) {
            rgbaUploadBuf = ByteBuffer.allocateDirect(rgba.length).order(ByteOrder.nativeOrder());
        }
        rgbaUploadBuf.clear();
        rgbaUploadBuf.put(rgba).flip();

        Texture tex = videoFbo.getTexture();
        tex.bind();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexSubImage2D(tex.getTarget(), 0, 0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, rgbaUploadBuf);

        lastDecodedTimeUs = effectiveUs;
        return videoFbo;
    }

    private byte[] decodeSingleFrameRgba(long timeUs) {
        try {
            double sec = timeUs / 1_000_000.0;
            ProcessBuilder pb = new ProcessBuilder(
                    FfmpegLocator.ffmpeg(), "-v", "error",
                    "-ss", String.format(Locale.US, "%.3f", sec),
                    "-i", sourceFile.getAbsolutePath(),
                    "-frames:v", "1",
                    "-vf", "vflip,scale=" + width + ":" + height,
                    "-f", "rawvideo",
                    "-pix_fmt", "rgba",
                    "pipe:1");
            Process p = pb.start();
            ByteArrayOutputStream baos = new ByteArrayOutputStream(width * height * 4);
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[65536];
                int n;
                while ((n = is.read(buf)) > 0) {
                    baos.write(buf, 0, n);
                }
            }
            p.waitFor();
            return baos.toByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    public void release() {
        if (videoFbo != null) {
            videoFbo.dispose();
            videoFbo = null;
        }
        lastDecodedTimeUs = Long.MIN_VALUE;
    }
}

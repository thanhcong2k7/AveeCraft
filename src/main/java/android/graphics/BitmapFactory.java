package android.graphics;

import android.content.res.Resources;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.InputStream;
import javax.imageio.ImageIO;

public class BitmapFactory {
    public static class Options {
        public boolean inJustDecodeBounds = false;
        public int outWidth = 0;
        public int outHeight = 0;
        public int inSampleSize = 1;
        public Bitmap.Config inPreferredConfig = Bitmap.Config.ARGB_8888;
    }

    private static Bitmap wrapArgb(BufferedImage src) {
        if (src == null) return null;
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) {
            return new Bitmap(src);
        }
        BufferedImage argb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return new Bitmap(argb);
    }

    public static Bitmap decodeResource(Resources res, int id) {
        if (res == null || id == 0) return null;
        try (InputStream is = res.openRawResource(id)) {
            if (is == null) return null;
            return wrapArgb(ImageIO.read(is));
        } catch (Exception e) {
            return null;
        }
    }

    public static Bitmap decodeFile(String pathName) {
        return decodeFile(pathName, null);
    }

    public static Bitmap decodeFile(String pathName, Options opts) {
        if (pathName == null) return null;
        try {
            File f = new File(pathName);
            BufferedImage img = ImageIO.read(f);
            if (img == null && f.exists() && f.isFile()) {
                img = decodeViaFfmpeg(f);
            }
            if (img == null) return null;
            if (opts != null) {
                opts.outWidth = img.getWidth();
                opts.outHeight = img.getHeight();
                if (opts.inJustDecodeBounds) return null;
            }
            return wrapArgb(img);
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage decodeViaFfmpeg(File f) {
        try {
            Process p = new ProcessBuilder(
                    "ffmpeg", "-v", "error", "-i", f.getAbsolutePath(),
                    "-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1")
                    .start();
            byte[] png = p.getInputStream().readAllBytes();
            p.waitFor();
            if (png.length > 0) {
                return ImageIO.read(new java.io.ByteArrayInputStream(png));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    public static Bitmap decodeStream(InputStream is) {
        return decodeStream(is, null, null);
    }

    public static Bitmap decodeStream(InputStream is, Rect outPadding, Options opts) {
        if (is == null) return null;
        try {
            BufferedImage img = ImageIO.read(is);
            if (img == null) return null;
            if (opts != null) {
                opts.outWidth = img.getWidth();
                opts.outHeight = img.getHeight();
                if (opts.inJustDecodeBounds) return null;
            }
            return wrapArgb(img);
        } catch (Exception e) {
            return null;
        }
    }
}

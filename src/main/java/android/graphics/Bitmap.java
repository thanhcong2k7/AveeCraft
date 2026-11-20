package android.graphics;

import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.OutputStream;
import javax.imageio.ImageIO;

public class Bitmap {
    public enum Config {
        ARGB_8888,
        RGB_565,
        ALPHA_8,
        ARGB_4444
    }

    public enum CompressFormat {
        JPEG,
        PNG,
        WEBP
    }

    private final BufferedImage image;
    private boolean recycled = false;

    public Bitmap(BufferedImage image) {
        this.image = image;
    }

    public BufferedImage getBufferedImage() {
        return image;
    }

    public static Bitmap createBitmap(int width, int height, Config config) {
        return new Bitmap(new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB));
    }

    public static Bitmap createBitmap(int[] colors, int width, int height, Config config) {
        return createBitmap(colors, 0, width, width, height, config);
    }

    public static Bitmap createBitmap(int[] colors, int offset, int stride, int width, int height, Config config) {
        BufferedImage img = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        if (colors != null && width > 0 && height > 0) {
            img.setRGB(0, 0, width, height, colors, offset, stride);
        }
        return new Bitmap(img);
    }

    public static Bitmap createBitmap(Bitmap src, int x, int y, int width, int height) {
        BufferedImage img = new BufferedImage(Math.max(1, width), Math.max(1, height), BufferedImage.TYPE_INT_ARGB);
        if (src != null) {
            Graphics2D g = img.createGraphics();
            g.setComposite(AlphaComposite.Src);
            g.drawImage(src.image, 0, 0, width, height, x, y, x + width, y + height, null);
            g.dispose();
        }
        return new Bitmap(img);
    }

    public static Bitmap createScaledBitmap(Bitmap src, int dstWidth, int dstHeight, boolean filter) {
        if (src == null) return null;
        int w = Math.max(1, dstWidth);
        int h = Math.max(1, dstHeight);
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = dst.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                filter ? java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR
                       : java.awt.RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.drawImage(src.image.getScaledInstance(w, h, filter ? Image.SCALE_SMOOTH : Image.SCALE_FAST), 0, 0, null);
        g.dispose();
        return new Bitmap(dst);
    }

    public int getWidth() { return image.getWidth(); }
    public int getHeight() { return image.getHeight(); }
    public Config getConfig() { return Config.ARGB_8888; }
    public boolean isRecycled() { return recycled; }
    public void recycle() { recycled = true; }

    public void eraseColor(int c) {
        Graphics2D g = image.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.setColor(new java.awt.Color(c, true));
        g.fillRect(0, 0, image.getWidth(), image.getHeight());
        g.dispose();
    }

    public Bitmap copy(Config config, boolean isMutable) {
        return createBitmap(this, 0, 0, getWidth(), getHeight());
    }

    public int getPixel(int x, int y) { return image.getRGB(x, y); }
    public void setPixel(int x, int y, int color) { image.setRGB(x, y, color); }

    public void getPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        image.getRGB(x, y, width, height, pixels, offset, stride);
    }

    public void setPixels(int[] pixels, int offset, int stride, int x, int y, int width, int height) {
        image.setRGB(x, y, width, height, pixels, offset, stride);
    }

    public boolean compress(CompressFormat format, int quality, OutputStream stream) {
        try {
            String fmt = (format == CompressFormat.JPEG) ? "jpg" : "png";
            return ImageIO.write(image, fmt, stream);
        } catch (Exception e) {
            return false;
        }
    }
}

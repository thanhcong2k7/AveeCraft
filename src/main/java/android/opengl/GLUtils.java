package android.opengl;

import android.graphics.Bitmap;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class GLUtils {
    public static void texImage2D(int target, int level, Bitmap bitmap, int border) {
        if (bitmap == null) return;
        BufferedImage img = bitmap.getBufferedImage();
        int w = img.getWidth();
        int h = img.getHeight();
        int[] argb = img.getRGB(0, 0, w, h, null, 0, w);
        ByteBuffer buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder());
        for (int px : argb) {
            int a = (px >>> 24) & 0xFF;
            int r = (((px >> 16) & 0xFF) * a + 127) / 255;
            int g = (((px >> 8) & 0xFF) * a + 127) / 255;
            int b = ((px & 0xFF) * a + 127) / 255;
            buf.put((byte) r); // R (premultiplied to match Android Bitmap + GLUtils.texImage2D)
            buf.put((byte) g); // G
            buf.put((byte) b); // B
            buf.put((byte) a); // A
        }
        buf.flip();
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        GLES20.glTexImage2D(target, level, GLES20.GL_RGBA, w, h, border, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf);
    }
}

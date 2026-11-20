package com.daaw.avee.comp.AlbumArt;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import com.daaw.avee.Common.Func.Func;
import com.daaw.avee.Common.tlog;
import com.daaw.avee.PlayerCore;
import com.daaw.avee.comp.AppPreferences.AppPreferences;
import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageInputStream;
import org.w3c.dom.NodeList;

public class AlbumArtCore implements IAlbumArtCore {
    private static volatile AlbumArtCore instance;

    public static IAlbumArtCore createInstance(Context context) {
        if (instance == null) {
            instance = new AlbumArtCore();
        }
        return instance;
    }

    public static IAlbumArtCore getInstance() {
        return createInstance(PlayerCore.s().getAppContext());
    }

    @Override
    public void cancelRequest(android.widget.ImageView imageView) {
    }

    @Override
    public void loadAlbumArt(Uri uri, String path0, String path1, String genHint,
                             android.widget.ImageView imageView, boolean z, boolean z2, boolean z3) {
    }

    @Override
    public void loadAlbumArtLarge(boolean fast, Uri uri, String path0, String path1, String genHint,
                                  boolean preferVideo, ImageLoadedListener listener,
                                  int targetW, int targetH, int maxW, int maxH,
                                  int hintType, int bgColor, boolean generateAlbumArtIfNone,
                                  ImageColorKeyParams colorKeyParams, Object tag) {
        loadInternal(uri, path0, path1, genHint, listener, targetW, targetH, hintType, bgColor, generateAlbumArtIfNone, colorKeyParams);
    }

    private void loadInternal(Uri uri, String path0, String path1, String genHint,
                              ImageLoadedListener listener,
                              int targetW, int targetH, int hintType, int bgColor,
                              boolean generateAlbumArtIfNone, ImageColorKeyParams colorKeyParams) {
        if (listener == null) return;
        String urlStr = (uri != null && uri != Uri.EMPTY) ? uri.toString() : "";
        boolean forceGen = (hintType & 4) != 0;
        String chosen = forceGen ? "" : firstNonEmpty(path0, path1, urlStr);
        if (chosen != null && !chosen.isEmpty()) {
            // 1. Check internalres:<name>
            int resId = InternalBitmapLoader.getResIdFromPath(chosen);
            if (resId > 0) {
                String rPath = com.daaw.avee.R.resolveResourcePath(resId);
                if (rPath != null && rPath.toLowerCase().endsWith(".gif")) {
                    ImageResult gifRes = tryLoadGifResource(rPath);
                    if (gifRes != null) {
                        listener.onBitmapLoaded(gifRes, urlStr, path0, path1);
                        return;
                    }
                }
                Bitmap bmp = BitmapFactory.decodeResource(PlayerCore.s().getAppContext().getResources(), resId);
                if (bmp != null && colorKeyParams != null) {
                    bmp = ColorKey.processBitmapColorKey(bmp, colorKeyParams, new com.daaw.avee.Common.Ref<>(null), new com.daaw.avee.Common.Ref<>(null));
                }
                if (bmp != null) {
                    listener.onBitmapLoaded(new ImageResult(bmp), urlStr, path0, path1);
                    return;
                }
            }

            // 2. Check local:<name> inside active .viz workspace folder
            File file = resolveLocalOrFile(chosen);
            if (file != null && file.exists() && file.isFile()) {
                if (file.getName().toLowerCase().endsWith(".gif") || hasGifHeader(file)) {
                    ImageResult gifRes = tryLoadGifFile(file);
                    if (gifRes != null) {
                        listener.onBitmapLoaded(gifRes, urlStr, path0, path1);
                        return;
                    }
                }
                Bitmap bmp = BitmapFactory.decodeFile(file.getAbsolutePath());
                if (bmp != null && colorKeyParams != null) {
                    bmp = ColorKey.processBitmapColorKey(bmp, colorKeyParams, new com.daaw.avee.Common.Ref<>(null), new com.daaw.avee.Common.Ref<>(null));
                }
                if (bmp != null) {
                    listener.onBitmapLoaded(new ImageResult(bmp), urlStr, path0, path1);
                    return;
                }
            }
            tlog.w("AlbumArtCore could not resolve image: " + chosen);
        }

        // 3. Generate gradient/text album art matching Android AlbumArtCore.loadVideoThumbOrGenerate
        if ((generateAlbumArtIfNone || forceGen) && genHint != null && !genHint.trim().isEmpty()) {
            try {
                int w = Math.max(256, Math.min(1024, targetW > 0 ? targetW : 512));
                int h = Math.max(256, Math.min(1024, targetH > 0 ? targetH : 512));
                boolean shiftHue = (hintType & 1) != 0;
                boolean noText = (hintType & 2) != 0;
                float[] hsl = new float[3];
                androidx.core.graphics.ColorUtils.colorToHSL(bgColor, hsl);
                if (hsl[2] > 0.99f) {
                    hsl[1] = 1.0f;
                }
                int alpha = android.graphics.Color.alpha(bgColor);
                float hue = (SimpleTextAlbumArtCreator.valueInAlphabet(genHint.trim().charAt(0)) * 360.0f) + (shiftHue ? 15.0f : 0.0f);
                int txtColor = androidx.core.graphics.ColorUtils.setAlphaComponent(
                        androidx.core.graphics.ColorUtils.HSLToColor(new float[]{hue, 0.2f, 1.0f}),
                        noText ? 0 : 255);
                int c0 = androidx.core.graphics.ColorUtils.setAlphaComponent(
                        androidx.core.graphics.ColorUtils.HSLToColor(new float[]{hue, 0.9f, hsl[2] * 0.6f}),
                        alpha);
                int c1 = androidx.core.graphics.ColorUtils.setAlphaComponent(
                        androidx.core.graphics.ColorUtils.HSLToColor(new float[]{(((int) hue) + 30) % 360, 0.9f, hsl[2] * 0.4f}),
                        alpha);
                Bitmap genBmp = SimpleTextAlbumArtCreator.textAsBitmap(w, h, genHint.trim(), txtColor, c0, c1, (android.graphics.drawable.Drawable) null);
                if (genBmp != null && colorKeyParams != null) {
                    genBmp = ColorKey.processBitmapColorKey(genBmp, colorKeyParams, new com.daaw.avee.Common.Ref<>(null), new com.daaw.avee.Common.Ref<>(null));
                }
                listener.onBitmapLoaded(genBmp != null ? new ImageResult(genBmp) : null, urlStr, path0, path1);
                return;
            } catch (Throwable t) {
                tlog.w("AlbumArtCore generate text art failed: " + t.getMessage());
            }
        }

        listener.onBitmapLoaded(null, urlStr, path0, path1);
    }

    public static File resolveLocalOrFile(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        String t = path.trim();
        if (t.startsWith("local:")) {
            String name = t.substring("local:".length());
            File ws = AppPreferences.getActiveWorkspaceFolder();
            return new File(ws, name);
        }
        if (t.startsWith("file://")) {
            return new File(Uri.parse(t).getPath());
        }
        File direct = new File(t);
        if (direct.exists()) return direct;
        File wsFile = new File(AppPreferences.getActiveWorkspaceFolder(), t);
        if (wsFile.exists()) return wsFile;
        return direct;
    }

    private static ImageResult tryLoadGifResource(String resourcePath) {
        try (InputStream is = AlbumArtCore.class.getResourceAsStream(resourcePath)) {
            if (is == null) return null;
            return decodeGifStream(ImageIO.createImageInputStream(is));
        } catch (Exception e) {
            return null;
        }
    }

    private static ImageResult tryLoadGifFile(File f) {
        try (ImageInputStream iis = ImageIO.createImageInputStream(f)) {
            return decodeGifStream(iis);
        } catch (Exception e) {
            return null;
        }
    }

    private static boolean hasGifHeader(File f) {
        try (FileInputStream fis = new FileInputStream(f)) {
            byte[] h = new byte[4];
            return fis.read(h) >= 4 && h[0] == 'G' && h[1] == 'I' && h[2] == 'F' && h[3] == '8';
        } catch (Exception e) {
            return false;
        }
    }

    private static ImageResult decodeGifStream(ImageInputStream iis) {
        if (iis == null) return null;
        try {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("gif");
            if (!readers.hasNext()) return null;
            ImageReader reader = readers.next();
            reader.setInput(iis);
            int count = reader.getNumImages(true);
            if (count <= 1) {
                BufferedImage img = reader.read(0);
                reader.dispose();
                return img != null ? new ImageResult(toArgbBitmap(img)) : null;
            }

            int canvasW = -1, canvasH = -1;
            try {
                IIOMetadata sm = reader.getStreamMetadata();
                if (sm != null) {
                    IIOMetadataNode root = (IIOMetadataNode) sm.getAsTree("javax_imageio_gif_stream_1.0");
                    NodeList lsd = root.getElementsByTagName("LogicalScreenDescriptor");
                    if (lsd.getLength() > 0) {
                        IIOMetadataNode node = (IIOMetadataNode) lsd.item(0);
                        canvasW = Integer.parseInt(node.getAttribute("logicalScreenWidth"));
                        canvasH = Integer.parseInt(node.getAttribute("logicalScreenHeight"));
                    }
                }
            } catch (Exception ignored) {}

            BufferedImage firstImg = reader.read(0);
            if (canvasW <= 0 || canvasH <= 0) {
                canvasW = firstImg.getWidth();
                canvasH = firstImg.getHeight();
            }

            BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
            BufferedImage prevCanvas = null;
            Graphics2D cg = canvas.createGraphics();
            int stride = Math.max(1, (int) Math.ceil(count / 72.0));
            final List<Bitmap> frames = new ArrayList<>(Math.min(count, 72));

            for (int i = 0; i < count; i++) {
                BufferedImage raw = (i == 0) ? firstImg : reader.read(i);
                int fx = 0, fy = 0;
                String disposal = "none";
                try {
                    IIOMetadata im = reader.getImageMetadata(i);
                    IIOMetadataNode root = (IIOMetadataNode) im.getAsTree("javax_imageio_gif_image_1.0");
                    NodeList desc = root.getElementsByTagName("ImageDescriptor");
                    if (desc.getLength() > 0) {
                        IIOMetadataNode dn = (IIOMetadataNode) desc.item(0);
                        fx = Integer.parseInt(dn.getAttribute("imageLeftPosition"));
                        fy = Integer.parseInt(dn.getAttribute("imageTopPosition"));
                    }
                    NodeList gce = root.getElementsByTagName("GraphicControlExtension");
                    if (gce.getLength() > 0) {
                        disposal = ((IIOMetadataNode) gce.item(0)).getAttribute("disposalMethod");
                    }
                } catch (Exception ignored) {}

                if ("restoreToPrevious".equals(disposal)) {
                    prevCanvas = copyBuffered(canvas);
                }
                cg.setComposite(AlphaComposite.SrcOver);
                cg.drawImage(raw, fx, fy, null);

                if (i % stride == 0) {
                    frames.add(new Bitmap(copyBuffered(canvas)));
                }

                if ("restoreToBackgroundColor".equals(disposal)) {
                    cg.setComposite(AlphaComposite.Clear);
                    cg.fillRect(fx, fy, raw.getWidth(), raw.getHeight());
                } else if ("restoreToPrevious".equals(disposal) && prevCanvas != null) {
                    cg.setComposite(AlphaComposite.Src);
                    cg.drawImage(prevCanvas, 0, 0, null);
                }
            }
            cg.dispose();
            reader.dispose();
            if (frames.isEmpty()) return null;
            if (frames.size() == 1) return new ImageResult(frames.get(0));

            final int[] idx = new int[]{0};
            return new ImageSequenceResult(
                    frames.get(0),
                    new Func<Integer>() { @Override public Integer onInvoke() { return frames.size(); } },
                    () -> idx[0] = 0,
                    () -> idx[0] = (idx[0] + 1) % frames.size(),
                    new Func<Bitmap>() { @Override public Bitmap onInvoke() { return frames.get(idx[0]); } },
                    null
            );
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage copyBuffered(BufferedImage src) {
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = copy.createGraphics();
        g.setComposite(AlphaComposite.Src);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return copy;
    }

    private static Bitmap toArgbBitmap(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_ARGB) return new Bitmap(src);
        BufferedImage argb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = argb.createGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return new Bitmap(argb);
    }

    private static String firstNonEmpty(String... vals) {
        for (String v : vals) {
            if (v != null && !v.trim().isEmpty()) return v.trim();
        }
        return null;
    }
}

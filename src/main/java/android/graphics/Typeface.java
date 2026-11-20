package android.graphics;

import android.content.res.AssetManager;
import java.awt.Font;
import java.io.File;
import java.io.InputStream;

public class Typeface {
    public static final int NORMAL = 0;
    public static final int BOLD = 1;
    public static final int ITALIC = 2;
    public static final int BOLD_ITALIC = 3;

    public static final Typeface DEFAULT = new Typeface(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
    public static final Typeface DEFAULT_BOLD = new Typeface(new Font(Font.SANS_SERIF, Font.BOLD, 12));

    private final Font awtFont;

    public Typeface(Font awtFont) {
        this.awtFont = awtFont;
    }

    public Font getAwtFont() {
        return awtFont;
    }

    public static Typeface createFromAsset(AssetManager mgr, String path) {
        if (mgr != null && path != null) {
            try (InputStream is = mgr.open(path)) {
                if (is != null) {
                    Font f = Font.createFont(Font.TRUETYPE_FONT, is);
                    return new Typeface(f);
                }
            } catch (Exception ignored) {
            }
        }
        return DEFAULT;
    }

    public static Typeface createFromFile(File file) {
        if (file != null && file.isFile()) {
            try {
                return new Typeface(Font.createFont(Font.TRUETYPE_FONT, file));
            } catch (Exception ignored) {
            }
        }
        return DEFAULT;
    }

    public static Typeface defaultFromStyle(int style) {
        return (style & BOLD) != 0 ? DEFAULT_BOLD : DEFAULT;
    }
}

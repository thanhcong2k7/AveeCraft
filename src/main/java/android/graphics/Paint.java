package android.graphics;

import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;

public class Paint {
    public enum Style { FILL, STROKE, FILL_AND_STROKE }
    public enum Align { LEFT, CENTER, RIGHT }
    public enum Cap { BUTT, ROUND, SQUARE }
    public enum Join { MITER, ROUND, BEVEL }

    public static final int ANTI_ALIAS_FLAG = 1;
    public static final int FILTER_BITMAP_FLAG = 2;

    public static class FontMetricsInt {
        public int top;
        public int ascent;
        public int descent;
        public int bottom;
        public int leading;
    }

    private static final BufferedImage DUMMY_IMG = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);

    private int color = 0xFFFFFFFF;
    private float textSize = 12f;
    private float strokeWidth = 1f;
    private boolean antiAlias = true;
    private boolean filterBitmap = true;
    private Style style = Style.FILL;
    private Align textAlign = Align.LEFT;
    private Cap strokeCap = Cap.BUTT;
    private Join strokeJoin = Join.MITER;
    private Typeface typeface = Typeface.DEFAULT;

    public Paint() {}
    public Paint(int flags) {
        this.antiAlias = (flags & ANTI_ALIAS_FLAG) != 0;
        this.filterBitmap = (flags & FILTER_BITMAP_FLAG) != 0;
    }
    public Paint(Paint src) {
        if (src != null) {
            color = src.color;
            textSize = src.textSize;
            strokeWidth = src.strokeWidth;
            antiAlias = src.antiAlias;
            filterBitmap = src.filterBitmap;
            style = src.style;
            textAlign = src.textAlign;
            strokeCap = src.strokeCap;
            strokeJoin = src.strokeJoin;
            typeface = src.typeface;
        }
    }

    public int getColor() { return color; }
    public void setColor(int color) { this.color = color; }
    public void setAlpha(int a) { this.color = ((a & 0xFF) << 24) | (this.color & 0x00FFFFFF); }
    public float getTextSize() { return textSize; }
    public void setTextSize(float textSize) { this.textSize = textSize; }
    public float getStrokeWidth() { return strokeWidth; }
    public void setStrokeWidth(float width) { this.strokeWidth = width; }
    public boolean isAntiAlias() { return antiAlias; }
    public void setAntiAlias(boolean aa) { this.antiAlias = aa; }
    public void setFilterBitmap(boolean filter) { this.filterBitmap = filter; }
    public void setFlags(int flags) {
        this.antiAlias = (flags & ANTI_ALIAS_FLAG) != 0;
        this.filterBitmap = (flags & FILTER_BITMAP_FLAG) != 0;
    }
    public Style getStyle() { return style; }
    public void setStyle(Style style) { this.style = style; }
    public Align getTextAlign() { return textAlign; }
    public void setTextAlign(Align align) { this.textAlign = align; }
    public void setStrokeCap(Cap cap) { this.strokeCap = cap; }
    public void setStrokeJoin(Join join) { this.strokeJoin = join; }
    public Typeface getTypeface() { return typeface; }
    public Typeface setTypeface(Typeface tf) {
        this.typeface = (tf != null) ? tf : Typeface.DEFAULT;
        return this.typeface;
    }
    public void setShader(Object shader) {}
    public void setColorFilter(Object filter) {}
    public void setPathEffect(Object effect) {}

    public int getAwtCap() {
        if (strokeCap == Cap.ROUND) return BasicStroke.CAP_ROUND;
        if (strokeCap == Cap.SQUARE) return BasicStroke.CAP_SQUARE;
        return BasicStroke.CAP_BUTT;
    }

    public int getAwtJoin() {
        if (strokeJoin == Join.ROUND) return BasicStroke.JOIN_ROUND;
        if (strokeJoin == Join.BEVEL) return BasicStroke.JOIN_BEVEL;
        return BasicStroke.JOIN_MITER;
    }

    private Font resolveFont() {
        Typeface tf = (typeface != null) ? typeface : Typeface.DEFAULT;
        return tf.getAwtFont().deriveFont(textSize);
    }

    public static class FontMetrics {
        public float top;
        public float ascent;
        public float descent;
        public float bottom;
        public float leading;
    }

    public FontMetrics getFontMetrics() {
        FontMetricsInt i = getFontMetricsInt();
        FontMetrics m = new FontMetrics();
        m.top = i.top; m.ascent = i.ascent; m.descent = i.descent; m.bottom = i.bottom; m.leading = i.leading;
        return m;
    }

    public float ascent() { return getFontMetricsInt().ascent; }
    public float descent() { return getFontMetricsInt().descent; }

    public FontMetricsInt getFontMetricsInt() {
        FontMetricsInt fm = new FontMetricsInt();
        Graphics2D g = DUMMY_IMG.createGraphics();
        try {
            java.awt.FontMetrics m = g.getFontMetrics(resolveFont());
            fm.ascent = -m.getAscent();
            fm.top = -m.getMaxAscent();
            fm.descent = m.getDescent();
            fm.bottom = m.getMaxDescent();
            fm.leading = m.getLeading();
        } finally {
            g.dispose();
        }
        return fm;
    }

    public float measureText(String text) {
        if (text == null || text.isEmpty()) return 0f;
        Graphics2D g = DUMMY_IMG.createGraphics();
        try {
            java.awt.FontMetrics m = g.getFontMetrics(resolveFont());
            return (float) m.stringWidth(text);
        } finally {
            g.dispose();
        }
    }

    public int getTextWidths(char[] text, int index, int count, float[] widths) {
        if (text == null || widths == null || count <= 0) return 0;
        Graphics2D g = DUMMY_IMG.createGraphics();
        try {
            java.awt.FontMetrics m = g.getFontMetrics(resolveFont());
            for (int i = 0; i < count; i++) {
                widths[i] = (float) m.charWidth(text[index + i]);
            }
            return count;
        } finally {
            g.dispose();
        }
    }

    public void getTextBounds(String text, int start, int end, Rect bounds) {
        getTextBounds(text.substring(start, end).toCharArray(), 0, end - start, bounds);
    }

    public void getTextBounds(char[] text, int index, int count, Rect bounds) {
        if (bounds == null) return;
        if (text == null || count <= 0) {
            bounds.set(0, 0, 0, 0);
            return;
        }
        String s = new String(text, index, count);
        Graphics2D g = DUMMY_IMG.createGraphics();
        try {
            FontRenderContext frc = g.getFontRenderContext();
            GlyphVector gv = resolveFont().createGlyphVector(frc, s);
            Rectangle2D r = gv.getVisualBounds();
            if (r.isEmpty()) {
                java.awt.FontMetrics m = g.getFontMetrics(resolveFont());
                bounds.set(0, -m.getAscent(), m.stringWidth(s), m.getDescent());
            } else {
                bounds.set((int) Math.floor(r.getMinX()), (int) Math.floor(r.getMinY()),
                           (int) Math.ceil(r.getMaxX()), (int) Math.ceil(r.getMaxY()));
            }
        } finally {
            g.dispose();
        }
    }
}

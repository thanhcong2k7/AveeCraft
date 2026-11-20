package android.graphics;

import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;

public class Canvas {
    private Graphics2D g;
    private Bitmap bitmap;

    public Canvas() {}

    public Canvas(Bitmap bitmap) {
        setBitmap(bitmap);
    }

    public void setBitmap(Bitmap bitmap) {
        if (g != null) {
            g.dispose();
            g = null;
        }
        this.bitmap = bitmap;
        if (bitmap != null) {
            this.g = bitmap.getBufferedImage().createGraphics();
        }
    }

    public void drawColor(int color) {
        drawColor(color, PorterDuff.Mode.SRC_OVER);
    }

    public void drawColor(int color, PorterDuff.Mode mode) {
        if (g == null || bitmap == null) return;
        java.awt.Composite prev = g.getComposite();
        if (mode == PorterDuff.Mode.CLEAR) {
            g.setComposite(AlphaComposite.Clear);
            g.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        } else {
            g.setComposite(AlphaComposite.Src);
            g.setColor(new java.awt.Color(color, true));
            g.fillRect(0, 0, bitmap.getWidth(), bitmap.getHeight());
        }
        g.setComposite(prev);
    }

    public void drawLine(float startX, float startY, float stopX, float stopY, Paint paint) {
        if (g == null) return;
        applyPaint(paint, true);
        g.draw(new Line2D.Float(startX, startY, stopX, stopY));
    }

    public void drawCircle(float cx, float cy, float radius, Paint paint) {
        if (g == null) return;
        applyPaint(paint, false);
        Ellipse2D.Float el = new Ellipse2D.Float(cx - radius, cy - radius, radius * 2f, radius * 2f);
        if (paint != null && paint.getStyle() == Paint.Style.STROKE) {
            g.draw(el);
        } else {
            g.fill(el);
        }
    }

    public void drawRect(float left, float top, float right, float bottom, Paint paint) {
        if (g == null) return;
        applyPaint(paint, false);
        java.awt.geom.Rectangle2D.Float r = new java.awt.geom.Rectangle2D.Float(left, top, right - left, bottom - top);
        if (paint != null && paint.getStyle() == Paint.Style.STROKE) {
            g.draw(r);
        } else {
            g.fill(r);
        }
    }

    public void drawRect(RectF r, Paint paint) {
        if (r != null) drawRect(r.left, r.top, r.right, r.bottom, paint);
    }

    public void drawText(String text, float x, float y, Paint paint) {
        if (g == null || text == null) return;
        applyPaint(paint, false);
        float drawX = x;
        if (paint != null) {
            float w = paint.measureText(text);
            if (paint.getTextAlign() == Paint.Align.CENTER) drawX -= w * 0.5f;
            else if (paint.getTextAlign() == Paint.Align.RIGHT) drawX -= w;
        }
        g.drawString(text, drawX, y);
    }

    public void drawText(char[] text, int index, int count, float x, float y, Paint paint) {
        if (text == null || count <= 0) return;
        drawText(new String(text, index, count), x, y, paint);
    }

    public void drawBitmap(Bitmap bmp, Rect src, Rect dst, Paint paint) {
        if (g == null || bmp == null) return;
        applyPaint(paint, false);
        java.awt.Composite prevComp = g.getComposite();
        if (paint != null) {
            int alpha = (paint.getColor() >>> 24) & 0xFF;
            if (alpha < 255) {
                g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255.0f));
            }
        }
        if (src == null) {
            g.drawImage(bmp.getBufferedImage(), dst.left, dst.top, dst.right, dst.bottom,
                    0, 0, bmp.getWidth(), bmp.getHeight(), null);
        } else {
            g.drawImage(bmp.getBufferedImage(), dst.left, dst.top, dst.right, dst.bottom,
                    src.left, src.top, src.right, src.bottom, null);
        }
        g.setComposite(prevComp);
    }

    public void drawBitmap(Bitmap bmp, Rect src, RectF dst, Paint paint) {
        if (dst == null) return;
        drawBitmap(bmp, src, new Rect((int) dst.left, (int) dst.top, (int) dst.right, (int) dst.bottom), paint);
    }

    public void drawOval(RectF oval, Paint paint) {
        if (g == null || oval == null) return;
        applyPaint(paint, false);
        java.awt.geom.Ellipse2D.Float el = new java.awt.geom.Ellipse2D.Float(oval.left, oval.top, oval.width(), oval.height());
        if (paint != null && paint.getStyle() == Paint.Style.STROKE) g.draw(el); else g.fill(el);
    }

    public void drawRoundRect(RectF rect, float rx, float ry, Paint paint) {
        if (g == null || rect == null) return;
        applyPaint(paint, false);
        java.awt.geom.RoundRectangle2D.Float rr = new java.awt.geom.RoundRectangle2D.Float(rect.left, rect.top, rect.width(), rect.height(), rx * 2f, ry * 2f);
        if (paint != null && paint.getStyle() == Paint.Style.STROKE) g.draw(rr); else g.fill(rr);
    }

    public int save() { return 0; }
    public void restore() {}
    public void restoreToCount(int saveCount) {}
    public void translate(float dx, float dy) { if (g != null) g.translate(dx, dy); }
    public void scale(float sx, float sy) { if (g != null) g.scale(sx, sy); }
    public void rotate(float deg) { if (g != null) g.rotate(Math.toRadians(deg)); }
    public void concat(Matrix m) { if (g != null && m != null) g.transform(m.awtTransform); }
    public void setMatrix(Matrix m) { if (g != null && m != null) g.setTransform(m.awtTransform); }
    public boolean clipRect(float l, float t, float r, float b) { return true; }
    public boolean clipRect(RectF rect) { return true; }
    public void drawPicture(Picture picture, Rect dst) { if (picture != null) picture.draw(this); }

    public void drawBitmap(Bitmap bmp, float left, float top, Paint paint) {
        if (g == null || bmp == null) return;
        applyPaint(paint, false);
        g.drawImage(bmp.getBufferedImage(), (int) left, (int) top, null);
    }

    public void drawPath(Path path, Paint paint) {
        if (g == null || path == null) return;
        applyPaint(paint, false);
        if (paint != null && paint.getStyle() == Paint.Style.STROKE) {
            g.draw(path.getAwtPath());
        } else {
            g.fill(path.getAwtPath());
        }
    }

    private void applyPaint(Paint p, boolean forceStroke) {
        if (p == null) return;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                p.isAntiAlias() ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                p.isAntiAlias() ? RenderingHints.VALUE_TEXT_ANTIALIAS_ON : RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setColor(new java.awt.Color(p.getColor(), true));
        Font f = (p.getTypeface() != null ? p.getTypeface().getAwtFont() : Typeface.DEFAULT.getAwtFont())
                .deriveFont(p.getTextSize());
        g.setFont(f);
        if (forceStroke || p.getStyle() == Paint.Style.STROKE || p.getStyle() == Paint.Style.FILL_AND_STROKE) {
            g.setStroke(new BasicStroke(Math.max(1f, p.getStrokeWidth()), p.getAwtCap(), p.getAwtJoin()));
        }
    }
}

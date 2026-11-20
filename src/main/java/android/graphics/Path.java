package android.graphics;

import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

public class Path {
    private final Path2D.Float awtPath = new Path2D.Float();
    private float curX = 0f, curY = 0f;

    public Path2D.Float getAwtPath() { return awtPath; }

    public void reset() {
        awtPath.reset();
        curX = 0f;
        curY = 0f;
    }

    public void moveTo(float x, float y) {
        awtPath.moveTo(x, y);
        curX = x; curY = y;
    }

    public void rMoveTo(float dx, float dy) {
        curX += dx; curY += dy;
        awtPath.moveTo(curX, curY);
    }

    public void lineTo(float x, float y) {
        awtPath.lineTo(x, y);
        curX = x; curY = y;
    }

    public void rLineTo(float dx, float dy) {
        curX += dx; curY += dy;
        awtPath.lineTo(curX, curY);
    }

    public void cubicTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        awtPath.curveTo(x1, y1, x2, y2, x3, y3);
        curX = x3; curY = y3;
    }

    public void rCubicTo(float dx1, float dy1, float dx2, float dy2, float dx3, float dy3) {
        awtPath.curveTo(curX + dx1, curY + dy1, curX + dx2, curY + dy2, curX + dx3, curY + dy3);
        curX += dx3; curY += dy3;
    }

    public void quadTo(float x1, float y1, float x2, float y2) {
        awtPath.quadTo(x1, y1, x2, y2);
        curX = x2; curY = y2;
    }

    public void close() {
        awtPath.closePath();
    }

    public void arcTo(RectF oval, float startAngle, float sweepAngle) {
        if (oval == null) return;
        java.awt.geom.Arc2D.Float arc = new java.awt.geom.Arc2D.Float(
                oval.left, oval.top, oval.width(), oval.height(),
                -startAngle, -sweepAngle, java.awt.geom.Arc2D.OPEN);
        awtPath.append(arc, true);
    }

    public void transform(Matrix matrix) {
        if (matrix != null) {
            awtPath.transform(matrix.awtTransform);
        }
    }

    public void computeBounds(RectF bounds, boolean exact) {
        Rectangle2D r = awtPath.getBounds2D();
        bounds.set((float) r.getMinX(), (float) r.getMinY(), (float) r.getMaxX(), (float) r.getMaxY());
    }
}

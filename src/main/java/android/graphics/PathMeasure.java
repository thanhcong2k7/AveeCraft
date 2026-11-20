package android.graphics;

import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

public class PathMeasure {
    private static class Seg {
        float x0, y0, x1, y1, cumStart, len, dx, dy;
    }

    private final List<List<Seg>> contours = new ArrayList<>();
    private final List<Float> contourLengths = new ArrayList<>();
    private int currentContour = 0;

    public PathMeasure() {}
    public PathMeasure(Path path, boolean forceClosed) {
        setPath(path, forceClosed);
    }

    public void setPath(Path path, boolean forceClosed) {
        contours.clear();
        contourLengths.clear();
        currentContour = 0;
        if (path == null) return;

        PathIterator it = path.getAwtPath().getPathIterator(null, 0.5);
        float[] coords = new float[6];
        List<Seg> cur = null;
        float startX = 0f, startY = 0f, prevX = 0f, prevY = 0f, cum = 0f;

        while (!it.isDone()) {
            int type = it.currentSegment(coords);
            if (type == PathIterator.SEG_MOVETO) {
                if (cur != null && !cur.isEmpty()) {
                    contours.add(cur);
                    contourLengths.add(cum);
                }
                cur = new ArrayList<>();
                startX = prevX = coords[0];
                startY = prevY = coords[1];
                cum = 0f;
            } else if (type == PathIterator.SEG_LINETO) {
                if (cur != null) {
                    cum += addSeg(cur, prevX, prevY, coords[0], coords[1], cum);
                    prevX = coords[0];
                    prevY = coords[1];
                }
            } else if (type == PathIterator.SEG_CLOSE) {
                if (cur != null) {
                    cum += addSeg(cur, prevX, prevY, startX, startY, cum);
                    prevX = startX;
                    prevY = startY;
                }
            }
            it.next();
        }
        if (cur != null && !cur.isEmpty()) {
            if (forceClosed && (prevX != startX || prevY != startY)) {
                cum += addSeg(cur, prevX, prevY, startX, startY, cum);
            }
            contours.add(cur);
            contourLengths.add(cum);
        }
    }

    private static float addSeg(List<Seg> list, float x0, float y0, float x1, float y1, float cumStart) {
        float vx = x1 - x0;
        float vy = y1 - y0;
        float len = (float) Math.hypot(vx, vy);
        if (len <= 1e-6f) return 0f;
        Seg s = new Seg();
        s.x0 = x0; s.y0 = y0; s.x1 = x1; s.y1 = y1;
        s.cumStart = cumStart; s.len = len;
        s.dx = vx / len; s.dy = vy / len;
        list.add(s);
        return len;
    }

    public boolean nextContour() {
        if (currentContour + 1 < contours.size()) {
            currentContour++;
            return true;
        }
        return false;
    }

    public float getLength() {
        if (currentContour >= contourLengths.size()) return 0f;
        return contourLengths.get(currentContour);
    }

    public boolean getPosTan(float distance, float[] pos, float[] tan) {
        if (currentContour >= contours.size()) return false;
        List<Seg> segs = contours.get(currentContour);
        if (segs.isEmpty()) return false;
        float total = contourLengths.get(currentContour);
        float d = Math.max(0f, Math.min(total, distance));
        for (Seg s : segs) {
            if (d <= s.cumStart + s.len || s == segs.get(segs.size() - 1)) {
                float t = Math.max(0f, Math.min(1f, (d - s.cumStart) / s.len));
                if (pos != null) {
                    pos[0] = s.x0 + (s.x1 - s.x0) * t;
                    pos[1] = s.y0 + (s.y1 - s.y0) * t;
                }
                if (tan != null) {
                    tan[0] = s.dx;
                    tan[1] = s.dy;
                }
                return true;
            }
        }
        return false;
    }
}

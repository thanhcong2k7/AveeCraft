package android.graphics;

public class RectF {
    public float left, top, right, bottom;

    public RectF() {}
    public RectF(float left, float top, float right, float bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public RectF(RectF r) {
        if (r != null) { left = r.left; top = r.top; right = r.right; bottom = r.bottom; }
    }
    public void set(float left, float top, float right, float bottom) {
        this.left = left; this.top = top; this.right = right; this.bottom = bottom;
    }
    public float width() { return right - left; }
    public float height() { return bottom - top; }
    public float centerX() { return (left + right) * 0.5f; }
    public float centerY() { return (top + bottom) * 0.5f; }
    public boolean contains(float x, float y) {
        return left < right && top < bottom && x >= left && x < right && y >= top && y < bottom;
    }
}

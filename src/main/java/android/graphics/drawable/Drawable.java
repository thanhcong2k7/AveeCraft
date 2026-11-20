package android.graphics.drawable;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Rect;
public class Drawable {
    public static abstract class ConstantState {
        public abstract Drawable newDrawable();
        public int getChangingConfigurations() { return 0; }
    }
    public void draw(Canvas canvas) {}
    public void setAlpha(int alpha) {}
    public void setColorFilter(ColorFilter cf) {}
    public int getOpacity() { return 0; }
    public int getIntrinsicWidth() { return -1; }
    public int getIntrinsicHeight() { return -1; }
    public Rect getBounds() { return new Rect(); }
    protected void onBoundsChange(Rect bounds) {}
    public int getChangingConfigurations() { return 0; }
    public ConstantState getConstantState() { return null; }
}

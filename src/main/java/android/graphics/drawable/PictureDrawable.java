package android.graphics.drawable;
import android.graphics.Canvas;
import android.graphics.Picture;
public class PictureDrawable extends Drawable {
    private final Picture picture;
    public PictureDrawable(Picture picture) { this.picture = picture; }
    public Picture getPicture() { return picture; }
    @Override public int getIntrinsicWidth() { return picture != null ? picture.getWidth() : -1; }
    @Override public int getIntrinsicHeight() { return picture != null ? picture.getHeight() : -1; }
    @Override public void draw(Canvas canvas) { if (picture != null) picture.draw(canvas); }
}

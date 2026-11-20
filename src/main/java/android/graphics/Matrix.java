package android.graphics;

import java.awt.geom.AffineTransform;

public class Matrix {
    private final float[] values = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    public final AffineTransform awtTransform = new AffineTransform();

    public Matrix() {}
    public Matrix(Matrix src) { set(src); }

    public void reset() { awtTransform.setToIdentity(); }
    public void set(Matrix src) {
        if (src != null) awtTransform.setTransform(src.awtTransform);
        else reset();
    }
    public void postTranslate(float dx, float dy) { awtTransform.preConcatenate(AffineTransform.getTranslateInstance(dx, dy)); }
    public void preTranslate(float dx, float dy) { awtTransform.translate(dx, dy); }
    public void postScale(float sx, float sy) { awtTransform.preConcatenate(AffineTransform.getScaleInstance(sx, sy)); }
    public void preScale(float sx, float sy) { awtTransform.scale(sx, sy); }
    public void postRotate(float deg) { awtTransform.preConcatenate(AffineTransform.getRotateInstance(Math.toRadians(deg))); }
    public void preRotate(float deg) { awtTransform.rotate(Math.toRadians(deg)); }
    public void postConcat(Matrix m) { if (m != null) awtTransform.preConcatenate(m.awtTransform); }
    public void preConcat(Matrix m) { if (m != null) awtTransform.concatenate(m.awtTransform); }
    public void preSkew(float kx, float ky) { awtTransform.shear(kx, ky); }
    public boolean invert(Matrix inverse) {
        try {
            AffineTransform inv = awtTransform.createInverse();
            if (inverse != null) inverse.awtTransform.setTransform(inv);
            else awtTransform.setTransform(inv);
            return true;
        } catch (Exception e) {
            return false;
        }
    }
    public void getValues(float[] v) { System.arraycopy(values, 0, v, 0, 9); }
    public void setValues(float[] v) { System.arraycopy(v, 0, values, 0, 9); }
    public void mapRect(RectF dst, RectF src) { dst.set(src.left, src.top, src.right, src.bottom); }
}

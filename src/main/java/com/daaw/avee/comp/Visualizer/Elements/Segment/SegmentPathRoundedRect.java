package com.daaw.avee.comp.Visualizer.Elements.Segment;

import android.graphics.PointF;
import android.graphics.RectF;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;

/* loaded from: classes.dex */
public class SegmentPathRoundedRect implements ISegmentPath {
    public static final String typeName = "RoundedRect";
    private float radius = 1.0f;
    private float roundness = 0.25f;

    public SegmentPathRoundedRect() {
    }

    public SegmentPathRoundedRect setRadius(float f) {
        this.radius = f;
        return this;
    }

    public SegmentPathRoundedRect setRoundness(float f) {
        this.roundness = clamp01(f);
        return this;
    }

    private static float clamp01(float f) {
        return f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public void process(IRenderState iRenderState) {
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public int getPreferredPointCount(RectF rectF) {
        if (this.roundness <= 0.001f) {
            return 4;
        }
        int pts = Math.round(getPathLength(rectF, 0) / 14.0f);
        return Math.max(36, Math.min(128, pts));
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public float getPathLength(RectF rectF, int i) {
        float halfW = Math.max(1e-4f, rectF.width() * 0.5f * this.radius);
        float halfH = Math.max(1e-4f, rectF.height() * 0.5f * this.radius);
        float cr = clamp01(this.roundness) * Math.min(halfW, halfH);
        float sx = Math.max(0.0f, 2.0f * (halfW - cr));
        float sy = Math.max(0.0f, 2.0f * (halfH - cr));
        return (2.0f * sx) + (2.0f * sy) + (float) (2.0d * Math.PI * cr);
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public int getPointOnPath(boolean reverse, int i, int i2, RectF rectF, float f, PointF outPt, PointF outVec) {
        float halfW = Math.max(1e-4f, rectF.width() * 0.5f * this.radius);
        float halfH = Math.max(1e-4f, rectF.height() * 0.5f * this.radius);
        float cx = rectF.centerX();
        float cy = rectF.centerY();
        float cr = clamp01(this.roundness) * Math.min(halfW, halfH);
        int idx = reverse ? (i2 - i) : i;

        if (cr <= 1e-4f && i2 == 4) {
            int corner = ((idx % 4) + 4) % 4;
            float invSqrt2 = 0.70710677f;
            if (corner == 0) {
                outPt.x = cx - halfW;
                outPt.y = cy - halfH;
                outVec.x = invSqrt2;
                outVec.y = invSqrt2;
            } else if (corner == 1) {
                outPt.x = cx + halfW;
                outPt.y = cy - halfH;
                outVec.x = -invSqrt2;
                outVec.y = invSqrt2;
            } else if (corner == 2) {
                outPt.x = cx + halfW;
                outPt.y = cy + halfH;
                outVec.x = -invSqrt2;
                outVec.y = -invSqrt2;
            } else {
                outPt.x = cx - halfW;
                outPt.y = cy + halfH;
                outVec.x = invSqrt2;
                outVec.y = -invSqrt2;
            }
            return ((i + i2) - 1) % i2;
        }

        float sx = Math.max(0.0f, 2.0f * (halfW - cr));
        float sy = Math.max(0.0f, 2.0f * (halfH - cr));
        float arcLen = (float) (0.5d * Math.PI * cr);
        float totalLen = (2.0f * sx) + (2.0f * sy) + (4.0f * arcLen);
        float t = (((float) idx / (float) i2) + f) % 1.0f;
        if (t < 0.0f) t += 1.0f;
        float s = t * totalLen;

        if (s < sx) {
            float u = sx > 0.0f ? s / sx : 0.0f;
            outPt.x = (cx - halfW + cr) + (u * sx);
            outPt.y = cy - halfH;
            outVec.x = 0.0f;
            outVec.y = 1.0f;
        } else if ((s -= sx) < arcLen) {
            double ang = -Math.PI * 0.5d + ((arcLen > 0.0f ? s / arcLen : 0.0f) * Math.PI * 0.5d);
            float ox = (float) Math.cos(ang);
            float oy = (float) Math.sin(ang);
            outPt.x = (cx + halfW - cr) + (ox * cr);
            outPt.y = (cy - halfH + cr) + (oy * cr);
            outVec.x = -ox;
            outVec.y = -oy;
        } else if ((s -= arcLen) < sy) {
            float u = sy > 0.0f ? s / sy : 0.0f;
            outPt.x = cx + halfW;
            outPt.y = (cy - halfH + cr) + (u * sy);
            outVec.x = -1.0f;
            outVec.y = 0.0f;
        } else if ((s -= sy) < arcLen) {
            double ang = (arcLen > 0.0f ? s / arcLen : 0.0f) * Math.PI * 0.5d;
            float ox = (float) Math.cos(ang);
            float oy = (float) Math.sin(ang);
            outPt.x = (cx + halfW - cr) + (ox * cr);
            outPt.y = (cy + halfH - cr) + (oy * cr);
            outVec.x = -ox;
            outVec.y = -oy;
        } else if ((s -= arcLen) < sx) {
            float u = sx > 0.0f ? s / sx : 0.0f;
            outPt.x = (cx + halfW - cr) - (u * sx);
            outPt.y = cy + halfH;
            outVec.x = 0.0f;
            outVec.y = -1.0f;
        } else if ((s -= sx) < arcLen) {
            double ang = (Math.PI * 0.5d) + ((arcLen > 0.0f ? s / arcLen : 0.0f) * Math.PI * 0.5d);
            float ox = (float) Math.cos(ang);
            float oy = (float) Math.sin(ang);
            outPt.x = (cx - halfW + cr) + (ox * cr);
            outPt.y = (cy + halfH - cr) + (oy * cr);
            outVec.x = -ox;
            outVec.y = -oy;
        } else if ((s -= arcLen) < sy) {
            float u = sy > 0.0f ? s / sy : 0.0f;
            outPt.x = cx - halfW;
            outPt.y = (cy + halfH - cr) - (u * sy);
            outVec.x = 1.0f;
            outVec.y = 0.0f;
        } else {
            s -= sy;
            double ang = Math.PI + (Math.min(1.0f, arcLen > 0.0f ? s / arcLen : 0.0f) * Math.PI * 0.5d);
            float ox = (float) Math.cos(ang);
            float oy = (float) Math.sin(ang);
            outPt.x = (cx - halfW + cr) + (ox * cr);
            outPt.y = (cy - halfH + cr) + (oy * cr);
            outVec.x = -ox;
            outVec.y = -oy;
        }

        return ((i + i2) - 1) % i2;
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.ICustomizable
    public void onApplyCustomization(CustomPropertiesList customPropertiesList) {
        this.radius = customPropertiesList.getPropertyFloat("radius", 1.0f);
        this.roundness = clamp01(customPropertiesList.getPropertyFloat("roundness", 0.25f));
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.ICustomizable
    public void onReadCustomization(CustomPropertiesList customPropertiesList) {
        customPropertiesList.putPropertyFloatAsRange("radius", this.radius, "misc", 0.15f, 3.0f);
        customPropertiesList.putPropertyFloatAsRange("roundness", this.roundness, "misc", 0.0f, 1.0f);
    }
}

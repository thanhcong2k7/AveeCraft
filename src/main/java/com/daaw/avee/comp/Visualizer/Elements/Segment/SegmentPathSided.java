package com.daaw.avee.comp.Visualizer.Elements.Segment;

import android.graphics.PointF;
import android.graphics.RectF;
import com.daaw.avee.comp.Visualizer.CustomPropertiesList;
import com.daaw.avee.comp.Visualizer.Graphic.IRenderState;
/* loaded from: classes.dex */
public class SegmentPathSided implements ISegmentPath {
    public static final String typeName = "SidedPolygon";
    float sideInAngle;
    float radius = 1.0f;
    float roundness = 0.0f;
    int sides = 3;
    float angleAculumator = 0.0f;
    PointF point0 = new PointF();
    PointF point1 = new PointF();

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public void process(IRenderState iRenderState) {
    }

    public SegmentPathSided() {
        setSides(3);
    }

    public SegmentPathSided setRadius(float f) {
        this.radius = f;
        return this;
    }

    public SegmentPathSided setRoundness(float f) {
        this.roundness = f < 0.0f ? 0.0f : (f > 1.0f ? 1.0f : f);
        return this;
    }

    public SegmentPathSided setSides(int i) {
        this.sides = Math.max(i, 3);
        this.sideInAngle = 6.2831855f / this.sides;
        return this;
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public int getPointOnPath(boolean z, int i, int i2, RectF rectF, float f, PointF pointF, PointF pointF2) {
        float height;
        if (rectF.width() < rectF.height()) {
            height = rectF.width();
        } else {
            height = rectF.height();
        }
        float f6 = height * 0.5f * this.radius;

        float clampedRound = this.roundness < 0.0f ? 0.0f : (this.roundness > 1.0f ? 1.0f : this.roundness);
        if (clampedRound > 0.001f) {
            int n = this.sides;
            int idx = z ? (i2 - i) : i;
            double t = (((double) idx / (double) i2) + f) % 1.0d;
            if (t < 0.0d) t += 1.0d;
            double u = 0.5d * clampedRound;
            double hA = Math.PI / n;
            double sinHa = Math.sin(hA);
            double cosHa = Math.cos(hA);
            double sideLen = 2.0d * sinHa;
            double straightLen = Math.max(0.0d, (1.0d - 2.0d * u) * sideLen);
            double rFillet = 2.0d * u * cosHa;
            double arcLen = rFillet * this.sideInAngle;
            double sectorLen = arcLen + straightLen;

            double secPos = t * n;
            int k = Math.min(n - 1, (int) Math.floor(secPos));
            double localS = (secPos - k) * sectorLen;
            double thetaK = (k * (double) this.sideInAngle) + this.angleAculumator;
            double ux;
            double uy;
            if (localS < arcLen) {
                double frac = arcLen > 0.0d ? (localS / arcLen) : 0.0d;
                double ang = (thetaK - hA) + (frac * this.sideInAngle);
                float nx = (float) (-Math.sin(ang));
                float ny = (float) Math.cos(ang);
                pointF2.x = nx;
                pointF2.y = ny;
                double centerScale = 1.0d - 2.0d * u;
                double cxU = centerScale * (-Math.sin(thetaK));
                double cyU = centerScale * Math.cos(thetaK);
                ux = cxU + (rFillet * nx);
                uy = cyU + (rFillet * ny);
            } else {
                double frac = straightLen > 0.0d ? ((localS - arcLen) / straightLen) : 0.0d;
                double sideNormalAng = thetaK + hA;
                float nx = (float) (-Math.sin(sideNormalAng));
                float ny = (float) Math.cos(sideNormalAng);
                pointF2.x = nx;
                pointF2.y = ny;
                double centerScale = 1.0d - 2.0d * u;
                double thetaNext = thetaK + this.sideInAngle;
                double ax = centerScale * (-Math.sin(thetaK)) + (rFillet * nx);
                double ay = centerScale * Math.cos(thetaK) + (rFillet * ny);
                double bx = centerScale * (-Math.sin(thetaNext)) + (rFillet * nx);
                double by = centerScale * Math.cos(thetaNext) + (rFillet * ny);
                ux = ax + ((bx - ax) * frac);
                uy = ay + ((by - ay) * frac);
            }
            pointF.x = rectF.centerX() + ((float) (-ux) * f6);
            pointF.y = rectF.centerY() + ((float) (-uy) * f6);
            return ((i + i2) - 1) % i2;
        }

        float f2 = ((float) (z ? i2 - i : i)) / i2;
        int i3 = this.sides;
        float f3 = (int) (i3 * f2);
        float f4 = (f2 - (f3 / i3)) / (1.0f / i3);
        float f5 = this.sideInAngle;
        double d = (f3 * f5) + this.angleAculumator;
        double d2 = f5;
        Double.isNaN(d);
        Double.isNaN(d2);
        double d3 = d2 + d;
        Double.isNaN(d);
        double d4 = (d + d3) * 0.5d;
        this.point0.x = (float) ((-Math.sin(d)) * 1.0d);
        this.point0.y = (float) (Math.cos(d) * 1.0d);
        this.point1.x = (float) ((-Math.sin(d3)) * 1.0d);
        this.point1.y = (float) (Math.cos(d3) * 1.0d);
        pointF2.x = (float) ((-Math.sin(d4)) * 1.0d);
        pointF2.y = (float) (Math.cos(d4) * 1.0d);
        pointF.x = this.point0.x + ((this.point1.x - this.point0.x) * f4);
        pointF.y = this.point0.y + ((this.point1.y - this.point0.y) * f4);
        pointF.x = rectF.centerX() + ((-pointF.x) * f6);
        pointF.y = rectF.centerY() + ((-pointF.y) * f6);
        return ((i + i2) - 1) % i2;
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public float getPathLength(RectF rectF, int i) {
        float height;
        if (rectF.width() < rectF.height()) {
            height = rectF.width();
        } else {
            height = rectF.height();
        }
        float clampedRound = this.roundness < 0.0f ? 0.0f : (this.roundness > 1.0f ? 1.0f : this.roundness);
        if (clampedRound > 0.001f) {
            double u = 0.5d * clampedRound;
            double hA = Math.PI / this.sides;
            double straightLen = Math.max(0.0d, (1.0d - 2.0d * u) * 2.0d * Math.sin(hA));
            double rFillet = 2.0d * u * Math.cos(hA);
            double arcLen = rFillet * this.sideInAngle;
            return (float) (this.sides * (arcLen + straightLen) * this.radius * height * 0.5d);
        }
        return this.sideInAngle * this.sides * this.radius * height * 0.5f;
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Segment.ISegmentPath
    public int getPreferredPointCount(RectF rectF) {
        return this.roundness > 0.001f ? Math.max(this.sides * 10, 48) : this.sides;
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.ICustomizable
    public void onApplyCustomization(CustomPropertiesList customPropertiesList) {
        this.radius = customPropertiesList.getPropertyFloat("radius", 1.0f);
        setSides(customPropertiesList.getPropertyInt("sides", 3));
        setRoundness(customPropertiesList.getPropertyFloat("roundness", 0.0f));
    }

    @Override // com.daaw.avee.comp.Visualizer.Elements.Base.ICustomizable
    public void onReadCustomization(CustomPropertiesList customPropertiesList) {
        customPropertiesList.putPropertyFloatAsRange("radius", this.radius, "misc", 0.15f, 3.0f);
        customPropertiesList.putPropertyIntAsRange("sides", this.sides, "misc", 3, 18);
        customPropertiesList.putPropertyFloatAsRange("roundness", this.roundness, "misc", 0.0f, 1.0f);
    }
}

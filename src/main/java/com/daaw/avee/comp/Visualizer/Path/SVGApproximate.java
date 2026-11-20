package com.daaw.avee.comp.Visualizer.Path;

import android.util.Log;
import com.daaw.avee.Common.Vec4f;
import com.larvalabs.svgandroid.ParserHelper;
import java.util.ArrayList;
import java.util.List;
/* loaded from: classes.dex */
public class SVGApproximate {
    static final String TAG = "SVGApproximate";

    /* loaded from: classes.dex */
    public interface IPath {
        void close();

        void cubicTo(float f, float f2, float f3, float f4, float f5, float f6);

        void lineTo(float f, float f2);

        void moveTo(float f, float f2);

        void rLineTo(float f, float f2);

        void rMoveTo(float f, float f2);
    }

    private static void drawArc(IPath iPath, float f, float f2, float f3, float f4, float f5, float f6, float f7, int i, int i2) {
    }

    /* loaded from: classes.dex */
    public static class PathCreator implements IPath {
        List<SegmentContour> segments = new ArrayList();
        List<Vec4f> currentSegment = new ArrayList();
        boolean posset = false;
        float cx = 0.0f;
        float cy = 0.0f;

        @Override // com.daaw.avee.comp.Visualizer.Path.SVGApproximate.IPath
        public void cubicTo(float f, float f2, float f3, float f4, float f5, float f6) {
        }

        public List<SegmentContour> getSegments() {
            return this.segments;
        }

        @Override // com.daaw.avee.comp.Visualizer.Path.SVGApproximate.IPath
        public void rMoveTo(float f, float f2) {
            this.cx += f;
            this.cy += f2;
            this.posset = true;
        }

        @Override // com.daaw.avee.comp.Visualizer.Path.SVGApproximate.IPath
        public void moveTo(float f, float f2) {
            this.cx = f;
            this.cy = f2;
            this.posset = true;
        }

        @Override // com.daaw.avee.comp.Visualizer.Path.SVGApproximate.IPath
        public void close() {
            if (this.currentSegment.size() > 0) {
                this.segments.add(new SegmentContour(this.currentSegment));
            }
            this.currentSegment.clear();
            this.posset = false;
        }

        @Override // com.daaw.avee.comp.Visualizer.Path.SVGApproximate.IPath
        public void rLineTo(float f, float f2) {
            if (!this.posset) {
                this.cx = 0.0f;
                this.cy = 0.0f;
                this.posset = true;
            }
            List<Vec4f> list = this.currentSegment;
            float f3 = this.cx;
            float f4 = this.cy;
            list.add(new Vec4f(f3, f4, f3 + f, f4 + f2));
            this.cx += f;
            this.cy += f2;
        }

        @Override // com.daaw.avee.comp.Visualizer.Path.SVGApproximate.IPath
        public void lineTo(float f, float f2) {
            if (!this.posset) {
                this.cx = 0.0f;
                this.cy = 0.0f;
                this.posset = true;
            }
            this.currentSegment.add(new Vec4f(this.cx, this.cy, f, f2));
            this.cx = f;
            this.cy = f2;
        }
    }

    public static List<SegmentContour> parsePathApproximate(String str) {
        PathCreator pathCreator = new PathCreator();
        doPath(str, pathCreator);
        return pathCreator.getSegments();
    }

    private static IPath doPath(String str, IPath iPath) {
        char c;
        float nextFloat;
        float nextFloat2;
        boolean z;
        int length = str.length();
        ParserHelper parserHelper = new ParserHelper(str, 0);
        parserHelper.skipWhitespace();
        char c2 = 0;
        float f = 0.0f;
        float f2 = 0.0f;
        float f3 = 0.0f;
        float f4 = 0.0f;
        float f5 = 0.0f;
        float f6 = 0.0f;
        while (parserHelper.pos < length) {
            char charAt = str.charAt(parserHelper.pos);
            if (charAt == '+' || charAt == '-' || charAt == '.' || (charAt >= '0' && charAt <= '9')) {
                if (c2 == 'm' || c2 == 'M') {
                    c = c2;
                    c2 = (char) (c2 - 1);
                } else if ("lhvcsqta".indexOf(Character.toLowerCase(c2)) >= 0) {
                    c = c2;
                } else {
                    parserHelper.advance();
                    c2 = charAt;
                    c = c2;
                }
            } else {
                parserHelper.advance();
                c2 = charAt;
                c = c2;
            }
            z = true;
            switch (c2) {
                case 'A':
                case 'a':
                    float nextFloat3 = parserHelper.nextFloat();
                    float nextFloat4 = parserHelper.nextFloat();
                    float nextFloat5 = parserHelper.nextFloat();
                    int nextFlag = parserHelper.nextFlag();
                    int nextFlag2 = parserHelper.nextFlag();
                    float nextFloat6 = parserHelper.nextFloat();
                    float nextFloat7 = parserHelper.nextFloat();
                    if (c2 == 'a') {
                        nextFloat6 += f;
                        nextFloat7 += f2;
                    }
                    float f7 = nextFloat6;
                    float f8 = nextFloat7;
                    drawArc(iPath, f, f2, f7, f8, nextFloat3, nextFloat4, nextFloat5, nextFlag, nextFlag2);
                    f = f7;
                    f2 = f8;
                    z = false;
                    break;
                case 'C':
                case 'c':
                    float nextFloat8 = parserHelper.nextFloat();
                    float nextFloat9 = parserHelper.nextFloat();
                    float nextFloat10 = parserHelper.nextFloat();
                    float nextFloat11 = parserHelper.nextFloat();
                    float nextFloat12 = parserHelper.nextFloat();
                    float nextFloat13 = parserHelper.nextFloat();
                    if (c2 == 'c') {
                        nextFloat8 += f;
                        nextFloat10 += f;
                        nextFloat12 += f;
                        nextFloat9 += f2;
                        nextFloat11 += f2;
                        nextFloat13 += f2;
                    }
                    float f9 = nextFloat10;
                    float f10 = nextFloat11;
                    iPath.cubicTo(nextFloat8, nextFloat9, f9, f10, nextFloat12, nextFloat13);
                    f = nextFloat12;
                    f2 = nextFloat13;
                    f6 = f10;
                    f5 = f9;
                    break;
                case 'H':
                case 'h':
                    float nextFloat14 = parserHelper.nextFloat();
                    if (c2 == 'h') {
                        iPath.rLineTo(nextFloat14, 0.0f);
                        f += nextFloat14;
                    } else {
                        iPath.lineTo(nextFloat14, f2);
                        f = nextFloat14;
                    }
                    z = false;
                    break;
                case 'L':
                case 'T':
                case 'l':
                case 't':
                    nextFloat = parserHelper.nextFloat();
                    nextFloat2 = parserHelper.nextFloat();
                    if (c2 == 'l') {
                        iPath.rLineTo(nextFloat, nextFloat2);
                        f += nextFloat;
                        f2 += nextFloat2;
                    } else {
                        iPath.lineTo(nextFloat, nextFloat2);
                        f = nextFloat;
                        f2 = nextFloat2;
                    }
                    z = false;
                    break;
                case 'M':
                case 'm':
                    nextFloat = parserHelper.nextFloat();
                    nextFloat2 = parserHelper.nextFloat();
                    if (c2 == 'm') {
                        f4 += nextFloat;
                        f3 += nextFloat2;
                        iPath.rMoveTo(nextFloat, nextFloat2);
                        f += nextFloat;
                        f2 += nextFloat2;
                    } else {
                        iPath.moveTo(nextFloat, nextFloat2);
                        f = nextFloat;
                        f4 = f;
                        f2 = nextFloat2;
                        f3 = f2;
                    }
                    z = false;
                    break;
                case 'Q':
                case 'S':
                case 'q':
                case 's':
                    float nextFloat15 = parserHelper.nextFloat();
                    float nextFloat16 = parserHelper.nextFloat();
                    float nextFloat17 = parserHelper.nextFloat();
                    float nextFloat18 = parserHelper.nextFloat();
                    if (Character.isLowerCase(c2)) {
                        nextFloat15 += f;
                        nextFloat17 += f;
                        nextFloat16 += f2;
                        nextFloat18 += f2;
                    }
                    float f11 = nextFloat15;
                    float f12 = nextFloat16;
                    float f13 = nextFloat17;
                    float f14 = nextFloat18;
                    iPath.cubicTo((f * 2.0f) - f5, (f2 * 2.0f) - f6, f11, f12, f13, f14);
                    f5 = f11;
                    f6 = f12;
                    f = f13;
                    f2 = f14;
                    break;
                case 'V':
                case 'v':
                    float nextFloat19 = parserHelper.nextFloat();
                    if (c2 == 'v') {
                        iPath.rLineTo(0.0f, nextFloat19);
                        f2 += nextFloat19;
                    } else {
                        iPath.lineTo(f, nextFloat19);
                        f2 = nextFloat19;
                    }
                    z = false;
                    break;
                case 'Z':
                case 'z':
                    iPath.close();
                    iPath.moveTo(f4, f3);
                    f2 = f3;
                    f6 = f2;
                    f = f4;
                    f5 = f;
                    break;
                default:
                    Log.w(TAG, "Invalid path command: " + c2);
                    parserHelper.advance();
                    z = false;
                    break;
            }
            if (!z) {
                f5 = f;
                f6 = f2;
            }
            parserHelper.skipWhitespace();
            c2 = c;
        }
        return iPath;
    }
}

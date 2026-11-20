package androidx.core.graphics;

import android.graphics.Color;

public class ColorUtils {
    public static void RGBToHSL(int r, int g, int b, float[] outHsl) {
        float rf = r / 255f;
        float gf = g / 255f;
        float bf = b / 255f;
        float max = Math.max(rf, Math.max(gf, bf));
        float min = Math.min(rf, Math.min(gf, bf));
        float deltaMaxMin = max - min;
        float l = (max + min) / 2f;
        float h, s;
        if (max == min) {
            h = s = 0f;
        } else {
            if (max == rf) h = ((gf - bf) / deltaMaxMin) % 6f;
            else if (max == gf) h = ((bf - rf) / deltaMaxMin) + 2f;
            else h = ((rf - gf) / deltaMaxMin) + 4f;
            s = deltaMaxMin / (1f - Math.abs(2f * l - 1f));
        }
        h = (h * 60f) % 360f;
        if (h < 0f) h += 360f;
        outHsl[0] = constrain(h, 0f, 360f);
        outHsl[1] = constrain(s, 0f, 1f);
        outHsl[2] = constrain(l, 0f, 1f);
    }

    public static void colorToHSL(int color, float[] outHsl) {
        RGBToHSL(Color.red(color), Color.green(color), Color.blue(color), outHsl);
    }

    public static int HSLToColor(float[] hsl) {
        float h = ((hsl[0] % 360f) + 360f) % 360f;
        float s = hsl[1];
        float l = hsl[2];
        float c = (1f - Math.abs(2f * l - 1f)) * s;
        float m = l - 0.5f * c;
        float x = c * (1f - Math.abs((h / 60f % 2f) - 1f));
        int hueSegment = ((int) h) / 60;
        int r = 0, g = 0, b = 0;
        switch (hueSegment) {
            case 0: r = Math.round(255 * (c + m)); g = Math.round(255 * (x + m)); b = Math.round(255 * m); break;
            case 1: r = Math.round(255 * (x + m)); g = Math.round(255 * (c + m)); b = Math.round(255 * m); break;
            case 2: r = Math.round(255 * m); g = Math.round(255 * (c + m)); b = Math.round(255 * (x + m)); break;
            case 3: r = Math.round(255 * m); g = Math.round(255 * (x + m)); b = Math.round(255 * (c + m)); break;
            case 4: r = Math.round(255 * (x + m)); g = Math.round(255 * m); b = Math.round(255 * (c + m)); break;
            case 5:
            case 6: r = Math.round(255 * (c + m)); g = Math.round(255 * m); b = Math.round(255 * (x + m)); break;
        }
        return Color.rgb(constrain(r, 0, 255), constrain(g, 0, 255), constrain(b, 0, 255));
    }

    public static int setAlphaComponent(int color, int alpha) {
        return (color & 0x00FFFFFF) | ((alpha & 0xFF) << 24);
    }

    private static float constrain(float amt, float low, float high) {
        return amt < low ? low : (amt > high ? high : amt);
    }

    private static int constrain(int amt, int low, int high) {
        return amt < low ? low : (amt > high ? high : amt);
    }
}

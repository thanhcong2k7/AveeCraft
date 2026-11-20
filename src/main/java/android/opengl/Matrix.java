package android.opengl;

public class Matrix {
    public static void setIdentityM(float[] sm, int smOffset) {
        for (int i = 0; i < 16; i++) sm[smOffset + i] = 0f;
        sm[smOffset] = 1f;
        sm[smOffset + 5] = 1f;
        sm[smOffset + 10] = 1f;
        sm[smOffset + 15] = 1f;
    }

    public static void orthoM(float[] m, int mOffset, float left, float right, float bottom, float top, float near, float far) {
        if (left == right || bottom == top || near == far) {
            throw new IllegalArgumentException("Invalid orthoM bounds");
        }
        float rWidth = 1.0f / (right - left);
        float rHeight = 1.0f / (top - bottom);
        float rDepth = 1.0f / (far - near);
        m[mOffset + 0] = 2.0f * rWidth;
        m[mOffset + 1] = 0.0f;
        m[mOffset + 2] = 0.0f;
        m[mOffset + 3] = 0.0f;

        m[mOffset + 4] = 0.0f;
        m[mOffset + 5] = 2.0f * rHeight;
        m[mOffset + 6] = 0.0f;
        m[mOffset + 7] = 0.0f;

        m[mOffset + 8] = 0.0f;
        m[mOffset + 9] = 0.0f;
        m[mOffset + 10] = -2.0f * rDepth;
        m[mOffset + 11] = 0.0f;

        m[mOffset + 12] = -(right + left) * rWidth;
        m[mOffset + 13] = -(top + bottom) * rHeight;
        m[mOffset + 14] = -(far + near) * rDepth;
        m[mOffset + 15] = 1.0f;
    }

    public static void perspectiveM(float[] m, int offset, float fovy, float aspect, float zNear, float zFar) {
        float f = 1.0f / (float) Math.tan(Math.toRadians(fovy) * 0.5);
        float rangeReciprocal = 1.0f / (zNear - zFar);
        m[offset + 0] = f / aspect;
        m[offset + 1] = 0.0f;
        m[offset + 2] = 0.0f;
        m[offset + 3] = 0.0f;

        m[offset + 4] = 0.0f;
        m[offset + 5] = f;
        m[offset + 6] = 0.0f;
        m[offset + 7] = 0.0f;

        m[offset + 8] = 0.0f;
        m[offset + 9] = 0.0f;
        m[offset + 10] = (zFar + zNear) * rangeReciprocal;
        m[offset + 11] = -1.0f;

        m[offset + 12] = 0.0f;
        m[offset + 13] = 0.0f;
        m[offset + 14] = 2.0f * zFar * zNear * rangeReciprocal;
        m[offset + 15] = 0.0f;
    }

    public static void multiplyMM(float[] result, int resultOffset, float[] lhs, int lhsOffset, float[] rhs, int rhsOffset) {
        float[] tmp = new float[16];
        for (int col = 0; col < 4; col++) {
            for (int row = 0; row < 4; row++) {
                float sum = 0f;
                for (int k = 0; k < 4; k++) {
                    sum += lhs[lhsOffset + k * 4 + row] * rhs[rhsOffset + col * 4 + k];
                }
                tmp[col * 4 + row] = sum;
            }
        }
        System.arraycopy(tmp, 0, result, resultOffset, 16);
    }

    public static void multiplyMV(float[] resultVec, int resultVecOffset, float[] lhsMat, int lhsMatOffset, float[] rhsVec, int rhsVecOffset) {
        float x = rhsVec[rhsVecOffset];
        float y = rhsVec[rhsVecOffset + 1];
        float z = rhsVec[rhsVecOffset + 2];
        float w = rhsVec[rhsVecOffset + 3];
        resultVec[resultVecOffset]     = lhsMat[lhsMatOffset] * x + lhsMat[lhsMatOffset + 4] * y + lhsMat[lhsMatOffset + 8] * z + lhsMat[lhsMatOffset + 12] * w;
        resultVec[resultVecOffset + 1] = lhsMat[lhsMatOffset + 1] * x + lhsMat[lhsMatOffset + 5] * y + lhsMat[lhsMatOffset + 9] * z + lhsMat[lhsMatOffset + 13] * w;
        resultVec[resultVecOffset + 2] = lhsMat[lhsMatOffset + 2] * x + lhsMat[lhsMatOffset + 6] * y + lhsMat[lhsMatOffset + 10] * z + lhsMat[lhsMatOffset + 14] * w;
        resultVec[resultVecOffset + 3] = lhsMat[lhsMatOffset + 3] * x + lhsMat[lhsMatOffset + 7] * y + lhsMat[lhsMatOffset + 11] * z + lhsMat[lhsMatOffset + 15] * w;
    }

    public static void translateM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[mOffset + 12 + i] += m[mOffset + i] * x + m[mOffset + 4 + i] * y + m[mOffset + 8 + i] * z;
        }
    }

    public static void rotateM(float[] m, int mOffset, float a, float x, float y, float z) {
        float[] rot = new float[16];
        setRotateM(rot, 0, a, x, y, z);
        multiplyMM(m, mOffset, m, mOffset, rot, 0);
    }

    public static void setRotateM(float[] rm, int rmOffset, float a, float x, float y, float z) {
        float rad = (float) Math.toRadians(a);
        float s = (float) Math.sin(rad);
        float c = (float) Math.cos(rad);
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len > 0f && Math.abs(len - 1f) > 1e-5f) {
            x /= len; y /= len; z /= len;
        }
        float nc = 1.0f - c;
        float xy = x * y, yz = y * z, zx = z * x;
        float xs = x * s, ys = y * s, zs = z * s;
        rm[rmOffset + 0] = x * x * nc + c;  rm[rmOffset + 4] = xy * nc - zs; rm[rmOffset + 8]  = zx * nc + ys; rm[rmOffset + 12] = 0f;
        rm[rmOffset + 1] = xy * nc + zs;    rm[rmOffset + 5] = y * y * nc + c; rm[rmOffset + 9] = yz * nc - xs; rm[rmOffset + 13] = 0f;
        rm[rmOffset + 2] = zx * nc - ys;    rm[rmOffset + 6] = yz * nc + xs; rm[rmOffset + 10] = z * z * nc + c; rm[rmOffset + 14] = 0f;
        rm[rmOffset + 3] = 0f;              rm[rmOffset + 7] = 0f;            rm[rmOffset + 11] = 0f;             rm[rmOffset + 15] = 1f;
    }

    public static void scaleM(float[] m, int mOffset, float x, float y, float z) {
        for (int i = 0; i < 4; i++) {
            m[mOffset + i] *= x;
            m[mOffset + 4 + i] *= y;
            m[mOffset + 8 + i] *= z;
        }
    }
}

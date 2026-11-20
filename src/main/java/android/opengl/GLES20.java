package android.opengl;

import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryStack;

public class GLES20 {
    public static final int GL_ACTIVE_TEXTURE = GL13.GL_ACTIVE_TEXTURE;
    public static final int GL_DEPTH_BUFFER_BIT = GL11.GL_DEPTH_BUFFER_BIT;
    public static final int GL_STENCIL_BUFFER_BIT = GL11.GL_STENCIL_BUFFER_BIT;
    public static final int GL_COLOR_BUFFER_BIT = GL11.GL_COLOR_BUFFER_BIT;
    public static final int GL_FALSE = 0;
    public static final int GL_TRUE = 1;
    public static final int GL_POINTS = GL11.GL_POINTS;
    public static final int GL_LINES = GL11.GL_LINES;
    public static final int GL_LINE_LOOP = GL11.GL_LINE_LOOP;
    public static final int GL_LINE_STRIP = GL11.GL_LINE_STRIP;
    public static final int GL_TRIANGLES = GL11.GL_TRIANGLES;
    public static final int GL_TRIANGLE_STRIP = GL11.GL_TRIANGLE_STRIP;
    public static final int GL_TRIANGLE_FAN = GL11.GL_TRIANGLE_FAN;
    public static final int GL_ZERO = GL11.GL_ZERO;
    public static final int GL_ONE = GL11.GL_ONE;
    public static final int GL_SRC_COLOR = GL11.GL_SRC_COLOR;
    public static final int GL_ONE_MINUS_SRC_COLOR = GL11.GL_ONE_MINUS_SRC_COLOR;
    public static final int GL_SRC_ALPHA = GL11.GL_SRC_ALPHA;
    public static final int GL_ONE_MINUS_SRC_ALPHA = GL11.GL_ONE_MINUS_SRC_ALPHA;
    public static final int GL_DST_ALPHA = GL11.GL_DST_ALPHA;
    public static final int GL_ONE_MINUS_DST_ALPHA = GL11.GL_ONE_MINUS_DST_ALPHA;
    public static final int GL_DST_COLOR = GL11.GL_DST_COLOR;
    public static final int GL_ONE_MINUS_DST_COLOR = GL11.GL_ONE_MINUS_DST_COLOR;
    public static final int GL_BLEND = GL11.GL_BLEND;
    public static final int GL_DEPTH_TEST = GL11.GL_DEPTH_TEST;
    public static final int GL_CULL_FACE = GL11.GL_CULL_FACE;
    public static final int GL_SCISSOR_TEST = GL11.GL_SCISSOR_TEST;
    public static final int GL_TEXTURE_2D = GL11.GL_TEXTURE_2D;
    public static final int GL_TEXTURE0 = GL13.GL_TEXTURE0;
    public static final int GL_TEXTURE1 = GL13.GL_TEXTURE1;
    public static final int GL_TEXTURE2 = GL13.GL_TEXTURE2;
    public static final int GL_TEXTURE3 = GL13.GL_TEXTURE3;
    public static final int GL_RGBA = GL11.GL_RGBA;
    public static final int GL_RGB = GL11.GL_RGB;
    public static final int GL_ALPHA = GL11.GL_ALPHA;
    public static final int GL_UNSIGNED_BYTE = GL11.GL_UNSIGNED_BYTE;
    public static final int GL_UNSIGNED_SHORT = GL11.GL_UNSIGNED_SHORT;
    public static final int GL_FLOAT = GL11.GL_FLOAT;
    public static final int GL_NEAREST = GL11.GL_NEAREST;
    public static final int GL_LINEAR = GL11.GL_LINEAR;
    public static final int GL_LINEAR_MIPMAP_LINEAR = GL11.GL_LINEAR_MIPMAP_LINEAR;
    public static final int GL_LINEAR_MIPMAP_NEAREST = GL11.GL_LINEAR_MIPMAP_NEAREST;
    public static final int GL_NEAREST_MIPMAP_LINEAR = GL11.GL_NEAREST_MIPMAP_LINEAR;
    public static final int GL_NEAREST_MIPMAP_NEAREST = GL11.GL_NEAREST_MIPMAP_NEAREST;
    public static final int GL_TEXTURE_MAG_FILTER = GL11.GL_TEXTURE_MAG_FILTER;
    public static final int GL_TEXTURE_MIN_FILTER = GL11.GL_TEXTURE_MIN_FILTER;
    public static final int GL_TEXTURE_WRAP_S = GL11.GL_TEXTURE_WRAP_S;
    public static final int GL_TEXTURE_WRAP_T = GL11.GL_TEXTURE_WRAP_T;
    public static final int GL_REPEAT = GL11.GL_REPEAT;
    public static final int GL_CLAMP_TO_EDGE = 33071;
    public static final int GL_MIRRORED_REPEAT = GL14.GL_MIRRORED_REPEAT;
    public static final int GL_ARRAY_BUFFER = GL15.GL_ARRAY_BUFFER;
    public static final int GL_ELEMENT_ARRAY_BUFFER = GL15.GL_ELEMENT_ARRAY_BUFFER;
    public static final int GL_STATIC_DRAW = GL15.GL_STATIC_DRAW;
    public static final int GL_DYNAMIC_DRAW = GL15.GL_DYNAMIC_DRAW;
    public static final int GL_STREAM_DRAW = GL15.GL_STREAM_DRAW;
    public static final int GL_FRAGMENT_SHADER = GL20.GL_FRAGMENT_SHADER;
    public static final int GL_VERTEX_SHADER = GL20.GL_VERTEX_SHADER;
    public static final int GL_COMPILE_STATUS = GL20.GL_COMPILE_STATUS;
    public static final int GL_LINK_STATUS = GL20.GL_LINK_STATUS;
    public static final int GL_ACTIVE_UNIFORMS = GL20.GL_ACTIVE_UNIFORMS;
    public static final int GL_ACTIVE_ATTRIBUTES = GL20.GL_ACTIVE_ATTRIBUTES;
    public static final int GL_FRAMEBUFFER = GL30.GL_FRAMEBUFFER;
    public static final int GL_COLOR_ATTACHMENT0 = GL30.GL_COLOR_ATTACHMENT0;
    public static final int GL_FRAMEBUFFER_COMPLETE = GL30.GL_FRAMEBUFFER_COMPLETE;
    public static final int GL_MAX_TEXTURE_SIZE = GL11.GL_MAX_TEXTURE_SIZE;
    public static final int GL_MAX_RENDERBUFFER_SIZE = GL30.GL_MAX_RENDERBUFFER_SIZE;
    public static final int GL_UNPACK_ALIGNMENT = GL11.GL_UNPACK_ALIGNMENT;
    public static final int GL_PACK_ALIGNMENT = GL11.GL_PACK_ALIGNMENT;

    private static volatile int redirectFramebuffer0 = 0;
    private static final boolean GL_TRACE = Boolean.getBoolean("avee.glTrace");
    private static boolean tracedFirstErr = false;

    private static void traceErr(String op) {
        if (!GL_TRACE || tracedFirstErr) return;
        int err = GL11.glGetError();
        if (err != 0) {
            tracedFirstErr = true;
            System.err.println("[GLES20] First GL error " + err + " after " + op);
            new Throwable("GL Error trace").printStackTrace(System.err);
        }
    }

    public static void setRedirectFramebuffer0(int fboId) {
        redirectFramebuffer0 = fboId;
    }

    public static int getRedirectFramebuffer0() {
        return redirectFramebuffer0;
    }

    public static void glActiveTexture(int texture) {
        GL13.glActiveTexture(texture);
        traceErr("glActiveTexture");
    }

    public static void glAttachShader(int program, int shader) {
        GL20.glAttachShader(program, shader);
        traceErr("glAttachShader");
    }

    public static void glBindAttribLocation(int program, int index, String name) {
        GL20.glBindAttribLocation(program, index, name);
        traceErr("glBindAttribLocation");
    }

    public static void glBindBuffer(int target, int buffer) {
        GL15.glBindBuffer(target, buffer);
        traceErr("glBindBuffer");
    }

    public static void glBindFramebuffer(int target, int framebuffer) {
        int actual = (framebuffer == 0 && redirectFramebuffer0 != 0) ? redirectFramebuffer0 : framebuffer;
        GL30.glBindFramebuffer(target, actual);
        traceErr("glBindFramebuffer");
    }

    public static void glBindTexture(int target, int texture) {
        GL11.glBindTexture(target, texture);
        traceErr("glBindTexture");
    }

    public static void glBlendFunc(int sfactor, int dfactor) {
        GL11.glBlendFunc(sfactor, dfactor);
        traceErr("glBlendFunc(" + sfactor + "," + dfactor + ")");
    }

    public static void glBufferData(int target, int size, Buffer data, int usage) {
        if (data == null) {
            GL15.glBufferData(target, size, usage);
        } else {
            ByteBuffer direct = toDirectByteBuffer(data, size);
            GL15.glBufferData(target, direct, usage);
        }
        traceErr("glBufferData(size=" + size + ")");
    }

    public static void glBufferSubData(int target, int offset, int size, Buffer data) {
        if (data == null) return;
        ByteBuffer direct = toDirectByteBuffer(data, size);
        GL15.glBufferSubData(target, offset, direct);
        traceErr("glBufferSubData(offset=" + offset + ",size=" + size + ")");
    }

    public static int glCheckFramebufferStatus(int target) {
        return GL30.glCheckFramebufferStatus(target);
    }

    public static void glClear(int mask) {
        GL11.glClear(mask);
        traceErr("glClear");
    }

    public static void glClearColor(float r, float g, float b, float a) {
        GL11.glClearColor(r, g, b, a);
        traceErr("glClearColor");
    }

    public static void glCompileShader(int shader) {
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == 0) {
            System.err.println("[GLES20] Shader compile failed (" + shader + "): " + GL20.glGetShaderInfoLog(shader));
        }
        traceErr("glCompileShader");
    }

    public static int glCreateProgram() {
        return GL20.glCreateProgram();
    }

    public static int glCreateShader(int type) {
        return GL20.glCreateShader(type);
    }

    public static void glDeleteBuffers(int n, int[] buffers, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(n);
            buf.put(buffers, offset, n).flip();
            GL15.glDeleteBuffers(buf);
        }
    }

    public static void glDeleteFramebuffers(int n, int[] framebuffers, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(n);
            buf.put(framebuffers, offset, n).flip();
            GL30.glDeleteFramebuffers(buf);
        }
    }

    public static void glDeleteProgram(int program) {
        GL20.glDeleteProgram(program);
    }

    public static void glDeleteShader(int shader) {
        GL20.glDeleteShader(shader);
    }

    public static void glDeleteTextures(int n, int[] textures, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(n);
            buf.put(textures, offset, n).flip();
            GL11.glDeleteTextures(buf);
        }
    }

    public static void glDetachShader(int program, int shader) {
        GL20.glDetachShader(program, shader);
    }

    public static void glDisable(int cap) {
        GL11.glDisable(cap);
        traceErr("glDisable(" + cap + ")");
    }

    public static void glDisableVertexAttribArray(int index) {
        if (index < 0) return;
        GL20.glDisableVertexAttribArray(index);
        traceErr("glDisableVertexAttribArray(" + index + ")");
    }

    public static void glDrawArrays(int mode, int first, int count) {
        GL11.glDrawArrays(mode, first, count);
        traceErr("glDrawArrays");
    }

    public static void glDrawElements(int mode, int count, int type, Buffer indices) {
        ByteBuffer direct = toDirectByteBuffer(indices, count * (type == GL11.GL_UNSIGNED_SHORT ? 2 : 4));
        GL11.glDrawElements(mode, type, direct);
        traceErr("glDrawElements(buf)");
    }

    public static void glDrawElements(int mode, int count, int type, int offset) {
        GL11.glDrawElements(mode, count, type, offset);
        traceErr("glDrawElements(off)");
    }

    public static void glEnable(int cap) {
        if (cap == GL11.GL_TEXTURE_2D) return; // deprecated in core, harmless in compat
        GL11.glEnable(cap);
        traceErr("glEnable(" + cap + ")");
    }

    public static void glEnableVertexAttribArray(int index) {
        if (index < 0) return;
        GL20.glEnableVertexAttribArray(index);
        traceErr("glEnableVertexAttribArray(" + index + ")");
    }

    public static void glFinish() {
        GL11.glFinish();
    }

    public static void glFramebufferTexture2D(int target, int attachment, int textarget, int texture, int level) {
        GL30.glFramebufferTexture2D(target, attachment, textarget, texture, level);
        traceErr("glFramebufferTexture2D");
    }

    public static void glGenBuffers(int n, int[] buffers, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(n);
            GL15.glGenBuffers(buf);
            for (int i = 0; i < n; i++) {
                buffers[offset + i] = buf.get(i);
            }
        }
        traceErr("glGenBuffers");
    }

    public static void glGenerateMipmap(int target) {
        GL30.glGenerateMipmap(target);
        traceErr("glGenerateMipmap");
    }

    public static void glGenFramebuffers(int n, int[] framebuffers, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(n);
            GL30.glGenFramebuffers(buf);
            for (int i = 0; i < n; i++) {
                framebuffers[offset + i] = buf.get(i);
            }
        }
    }

    public static void glGenTextures(int n, int[] textures, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(n);
            GL11.glGenTextures(buf);
            for (int i = 0; i < n; i++) {
                textures[offset + i] = buf.get(i);
            }
        }
    }

    public static void glGetActiveAttrib(int program, int index, int bufsize,
                                         int[] length, int lengthOffset,
                                         int[] size, int sizeOffset,
                                         int[] type, int typeOffset,
                                         byte[] name, int nameOffset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer lenBuf = stack.mallocInt(1);
            IntBuffer sizeBuf = stack.mallocInt(1);
            IntBuffer typeBuf = stack.mallocInt(1);
            ByteBuffer nameBuf = stack.malloc(bufsize);
            GL20.glGetActiveAttrib(program, index, lenBuf, sizeBuf, typeBuf, nameBuf);
            int actualLen = Math.min(bufsize, lenBuf.get(0));
            if (length != null) length[lengthOffset] = actualLen;
            if (size != null) size[sizeOffset] = sizeBuf.get(0);
            if (type != null) type[typeOffset] = typeBuf.get(0);
            if (name != null) {
                for (int i = 0; i < actualLen; i++) {
                    name[nameOffset + i] = nameBuf.get(i);
                }
            }
        }
    }

    public static void glGetActiveUniform(int program, int index, int bufsize,
                                          int[] length, int lengthOffset,
                                          int[] size, int sizeOffset,
                                          int[] type, int typeOffset,
                                          byte[] name, int nameOffset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer lenBuf = stack.mallocInt(1);
            IntBuffer sizeBuf = stack.mallocInt(1);
            IntBuffer typeBuf = stack.mallocInt(1);
            ByteBuffer nameBuf = stack.malloc(bufsize);
            GL20.glGetActiveUniform(program, index, lenBuf, sizeBuf, typeBuf, nameBuf);
            int actualLen = Math.min(bufsize, lenBuf.get(0));
            if (length != null) length[lengthOffset] = actualLen;
            if (size != null) size[sizeOffset] = sizeBuf.get(0);
            if (type != null) type[typeOffset] = typeBuf.get(0);
            if (name != null) {
                for (int i = 0; i < actualLen; i++) {
                    name[nameOffset + i] = nameBuf.get(i);
                }
            }
        }
    }

    public static int glGetAttribLocation(int program, String name) {
        int r = GL20.glGetAttribLocation(program, name);
        traceErr("glGetAttribLocation(" + program + "," + name + ")");
        return r;
    }

    public static int glGetError() {
        return GL11.glGetError();
    }

    public static void glGetIntegerv(int pname, int[] params, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(1);
            GL11.glGetIntegerv(pname, buf);
            params[offset] = buf.get(0);
        }
    }

    public static String glGetProgramInfoLog(int program) {
        return GL20.glGetProgramInfoLog(program);
    }

    public static void glGetProgramiv(int program, int pname, int[] params, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(1);
            GL20.glGetProgramiv(program, pname, buf);
            params[offset] = buf.get(0);
        }
    }

    public static String glGetShaderInfoLog(int shader) {
        return GL20.glGetShaderInfoLog(shader);
    }

    public static void glGetShaderiv(int shader, int pname, int[] params, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(1);
            GL20.glGetShaderiv(shader, pname, buf);
            params[offset] = buf.get(0);
        }
    }

    public static void glGetUniformfv(int program, int location, float[] params, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            GL20.glGetUniformfv(program, location, buf);
            int copy = Math.min(params.length - offset, 16);
            for (int i = 0; i < copy; i++) params[offset + i] = buf.get(i);
        }
    }

    public static void glGetUniformfv(int program, int location, FloatBuffer params) {
        if (params == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer buf = stack.mallocFloat(16);
            GL20.glGetUniformfv(program, location, buf);
            int copy = Math.min(params.remaining(), 16);
            for (int i = 0; i < copy; i++) params.put(params.position() + i, buf.get(i));
        }
    }

    public static void glGetUniformiv(int program, int location, int[] params, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(16);
            GL20.glGetUniformiv(program, location, buf);
            int copy = Math.min(params.length - offset, 16);
            for (int i = 0; i < copy; i++) params[offset + i] = buf.get(i);
        }
    }

    public static void glGetUniformiv(int program, int location, IntBuffer params) {
        if (params == null) return;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            IntBuffer buf = stack.mallocInt(16);
            GL20.glGetUniformiv(program, location, buf);
            int copy = Math.min(params.remaining(), 16);
            for (int i = 0; i < copy; i++) params.put(params.position() + i, buf.get(i));
        }
    }

    public static int glGetUniformLocation(int program, String name) {
        return GL20.glGetUniformLocation(program, name);
    }

    public static void glLinkProgram(int program) {
        GL20.glLinkProgram(program);
        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == 0) {
            System.err.println("[GLES20] Program link failed (" + program + "): " + GL20.glGetProgramInfoLog(program));
        }
        traceErr("glLinkProgram");
    }

    public static void glPixelStorei(int pname, int param) {
        GL11.glPixelStorei(pname, param);
        traceErr("glPixelStorei(" + pname + "," + param + ")");
    }

    public static void glReadPixels(int x, int y, int width, int height, int format, int type, Buffer pixels) {
        if (pixels == null) return;
        if (pixels.isDirect()) {
            if (pixels instanceof ByteBuffer) {
                GL11.glReadPixels(x, y, width, height, format, type, (ByteBuffer) pixels);
                return;
            } else if (pixels instanceof IntBuffer) {
                GL11.glReadPixels(x, y, width, height, format, type, (IntBuffer) pixels);
                return;
            }
        }
        int totalPixels = Math.max(1, width * height);
        ByteBuffer tmp = org.lwjgl.system.MemoryUtil.memAlloc(totalPixels * 4).order(ByteOrder.nativeOrder());
        try {
            GL11.glReadPixels(x, y, width, height, format, type, tmp);
            if (pixels instanceof IntBuffer) {
                IntBuffer dst = (IntBuffer) pixels;
                int pos = dst.position();
                int count = Math.min(dst.remaining(), totalPixels);
                int[] arr = new int[count];
                tmp.asIntBuffer().get(arr, 0, count);
                dst.put(arr, 0, count);
                dst.position(pos);
            } else if (pixels instanceof ByteBuffer) {
                ByteBuffer dst = (ByteBuffer) pixels;
                int pos = dst.position();
                int count = Math.min(dst.remaining(), tmp.capacity());
                byte[] arr = new byte[count];
                tmp.get(arr, 0, count);
                dst.put(arr, 0, count);
                dst.position(pos);
            }
        } finally {
            org.lwjgl.system.MemoryUtil.memFree(tmp);
        }
    }

    public static void glShaderSource(int shader, String source) {
        GL20.glShaderSource(shader, adaptEsShaderToDesktop(source));
    }

    private static String adaptEsShaderToDesktop(String src) {
        if (src == null) return "";
        // Strip GLSL ES precision statements and qualifiers so desktop OpenGL 2.1/compat drivers compile Avee's shaders identically
        String s = src.replaceAll("(?m)^\\s*precision\\s+(lowp|mediump|highp)\\s+[a-zA-Z0-9_]+\\s*;", "");
        s = s.replaceAll("\\b(lowp|mediump|highp)\\b\\s*", "");
        if (!s.trim().startsWith("#version")) {
            s = "#version 120\n" + s;
        }
        return s;
    }

    public static void glTexImage2D(int target, int level, int internalformat, int width, int height,
                                    int border, int format, int type, Buffer pixels) {
        if (pixels == null) {
            GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (ByteBuffer) null);
        } else if (pixels instanceof ByteBuffer && pixels.isDirect()) {
            GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (ByteBuffer) pixels);
        } else if (pixels instanceof IntBuffer && pixels.isDirect()) {
            GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, (IntBuffer) pixels);
        } else {
            ByteBuffer direct = toDirectByteBuffer(pixels, width * height * 4);
            GL11.glTexImage2D(target, level, internalformat, width, height, border, format, type, direct);
        }
        traceErr("glTexImage2D(" + width + "x" + height + ")");
    }

    public static void glTexParameteri(int target, int pname, int param) {
        GL11.glTexParameteri(target, pname, param);
        traceErr("glTexParameteri(" + pname + "," + param + ")");
    }

    public static void glTexSubImage2D(int target, int level, int xoffset, int yoffset,
                                       int width, int height, int format, int type, Buffer pixels) {
        ByteBuffer direct = toDirectByteBuffer(pixels, width * height * 4);
        GL11.glTexSubImage2D(target, level, xoffset, yoffset, width, height, format, type, direct);
        traceErr("glTexSubImage2D");
    }

    public static void glUniform1f(int location, float x) { GL20.glUniform1f(location, x); traceErr("glUniform1f"); }
    public static void glUniform1i(int location, int x) { GL20.glUniform1i(location, x); traceErr("glUniform1i"); }
    public static void glUniform2f(int location, float x, float y) { GL20.glUniform2f(location, x, y); traceErr("glUniform2f"); }
    public static void glUniform2i(int location, int x, int y) { GL20.glUniform2i(location, x, y); traceErr("glUniform2i"); }
    public static void glUniform3f(int location, float x, float y, float z) { GL20.glUniform3f(location, x, y, z); traceErr("glUniform3f"); }
    public static void glUniform3i(int location, int x, int y, int z) { GL20.glUniform3i(location, x, y, z); traceErr("glUniform3i"); }
    public static void glUniform4f(int location, float x, float y, float z, float w) { GL20.glUniform4f(location, x, y, z, w); traceErr("glUniform4f"); }
    public static void glUniform4i(int location, int x, int y, int z, int w) { GL20.glUniform4i(location, x, y, z, w); traceErr("glUniform4i"); }

    public static void glUniformMatrix3fv(int location, int count, boolean transpose, float[] value, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(count * 9);
            fb.put(value, offset, count * 9).flip();
            GL20.glUniformMatrix3fv(location, transpose, fb);
        }
        traceErr("glUniformMatrix3fv");
    }

    public static void glUniformMatrix4fv(int location, int count, boolean transpose, float[] value, int offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer fb = stack.mallocFloat(count * 16);
            fb.put(value, offset, count * 16).flip();
            GL20.glUniformMatrix4fv(location, transpose, fb);
        }
        traceErr("glUniformMatrix4fv");
    }

    public static void glUseProgram(int program) {
        GL20.glUseProgram(program);
        traceErr("glUseProgram(" + program + ")");
    }

    public static void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, Buffer ptr) {
        if (indx < 0) return;
        ByteBuffer direct = toDirectByteBuffer(ptr, 0);
        GL20.glVertexAttribPointer(indx, size, type, normalized, stride, direct);
        traceErr("glVertexAttribPointer(buf,indx=" + indx + ")");
    }

    public static void glVertexAttribPointer(int indx, int size, int type, boolean normalized, int stride, int offset) {
        if (indx < 0) return;
        GL20.glVertexAttribPointer(indx, size, type, normalized, stride, (long) offset);
        traceErr("glVertexAttribPointer(off,indx=" + indx + ")");
    }

    public static void glViewport(int x, int y, int width, int height) {
        GL11.glViewport(x, y, width, height);
        traceErr("glViewport(" + width + "x" + height + ")");
    }

    private static ByteBuffer toDirectByteBuffer(Buffer buf, int hintBytes) {
        if (buf instanceof ByteBuffer && buf.isDirect()) {
            return (ByteBuffer) buf;
        }
        if (buf instanceof ByteBuffer) {
            ByteBuffer bb = (ByteBuffer) buf;
            int pos = bb.position();
            int rem = bb.remaining();
            ByteBuffer out = ByteBuffer.allocateDirect(rem).order(ByteOrder.nativeOrder());
            byte[] tmp = new byte[rem];
            bb.get(tmp);
            bb.position(pos);
            out.put(tmp).flip();
            return out;
        }
        if (buf instanceof FloatBuffer) {
            FloatBuffer fb = (FloatBuffer) buf;
            int pos = fb.position();
            int rem = fb.remaining();
            ByteBuffer out = ByteBuffer.allocateDirect(rem * 4).order(ByteOrder.nativeOrder());
            FloatBuffer outF = out.asFloatBuffer();
            float[] tmp = new float[rem];
            fb.get(tmp);
            fb.position(pos);
            outF.put(tmp);
            out.limit(rem * 4);
            return out;
        }
        if (buf instanceof ShortBuffer) {
            ShortBuffer sb = (ShortBuffer) buf;
            int pos = sb.position();
            int rem = sb.remaining();
            ByteBuffer out = ByteBuffer.allocateDirect(rem * 2).order(ByteOrder.nativeOrder());
            ShortBuffer outS = out.asShortBuffer();
            short[] tmp = new short[rem];
            sb.get(tmp);
            sb.position(pos);
            outS.put(tmp);
            out.limit(rem * 2);
            return out;
        }
        if (buf instanceof IntBuffer) {
            IntBuffer ib = (IntBuffer) buf;
            int pos = ib.position();
            int rem = ib.remaining();
            ByteBuffer out = ByteBuffer.allocateDirect(rem * 4).order(ByteOrder.nativeOrder());
            IntBuffer outI = out.asIntBuffer();
            int[] tmp = new int[rem];
            ib.get(tmp);
            ib.position(pos);
            outI.put(tmp);
            out.limit(rem * 4);
            return out;
        }
        return ByteBuffer.allocateDirect(Math.max(4, hintBytes)).order(ByteOrder.nativeOrder());
    }
}

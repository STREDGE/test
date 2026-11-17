package com.videocompress.app;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Carries frames from the decoder to the encoder through OpenGL, which is what lets the
 * output have a different resolution than the input. The decoder draws into
 * {@link #decoderSurface()}; each frame is then painted onto the encoder's input surface.
 */
final class GlBridge implements SurfaceTexture.OnFrameAvailableListener {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;

    private static final String VERTEX =
            "attribute vec4 aPos;\n"
                    + "attribute vec4 aTex;\n"
                    + "uniform mat4 uSt;\n"
                    + "varying vec2 vTex;\n"
                    + "void main() {\n"
                    + "  gl_Position = aPos;\n"
                    + "  vTex = (uSt * aTex).xy;\n"
                    + "}\n";
    private static final String FRAGMENT =
            "#extension GL_OES_EGL_image_external : require\n"
                    + "precision mediump float;\n"
                    + "varying vec2 vTex;\n"
                    + "uniform samplerExternalOES sTex;\n"
                    + "void main() {\n"
                    + "  gl_FragColor = texture2D(sTex, vTex);\n"
                    + "}\n";
    // x, y, u, v for a full-screen triangle strip.
    private static final float[] QUAD = {
            -1f, -1f, 0f, 0f,
            1f, -1f, 1f, 0f,
            -1f, 1f, 0f, 1f,
            1f, 1f, 1f, 1f,
    };

    private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
    private EGLContext context = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

    private final HandlerThread callbackThread = new HandlerThread("frame-callback");
    private SurfaceTexture surfaceTexture;
    private Surface decoderSurface;

    private final FloatBuffer quad;
    private final float[] stMatrix = new float[16];
    private int program;
    private int textureId;
    private int aPos;
    private int aTex;
    private int uSt;

    private final Object frameLock = new Object();
    private boolean frameAvailable;

    GlBridge(Surface encoderInput, int width, int height) {
        quad = ByteBuffer.allocateDirect(QUAD.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(QUAD).position(0);
        try {
            setupEgl(encoderInput);
            setupGl(width, height);
        } catch (RuntimeException e) {
            release();
            throw e;
        }
    }

    Surface decoderSurface() {
        return decoderSurface;
    }

    private void setupEgl(Surface encoderInput) {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new RuntimeException("EGL: нет дисплея");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw new RuntimeException("EGL: не удалось инициализировать");
        }
        int[] attribs = {
                EGL14.EGL_RED_SIZE, 8,
                EGL14.EGL_GREEN_SIZE, 8,
                EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL_RECORDABLE_ANDROID, 1,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, count, 0) || count[0] < 1) {
            throw new RuntimeException("EGL: нет подходящей конфигурации");
        }
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == null || context == EGL14.EGL_NO_CONTEXT) {
            throw new RuntimeException("EGL: не удалось создать контекст");
        }
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInput,
                new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == null || eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new RuntimeException("EGL: не удалось создать поверхность");
        }
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
            throw new RuntimeException("EGL: eglMakeCurrent не сработал");
        }
    }

    private void setupGl(int width, int height) {
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, compile(GLES20.GL_VERTEX_SHADER, VERTEX));
        GLES20.glAttachShader(program, compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT));
        GLES20.glLinkProgram(program);
        int[] ok = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            throw new RuntimeException("GL: " + GLES20.glGetProgramInfoLog(program));
        }
        aPos = GLES20.glGetAttribLocation(program, "aPos");
        aTex = GLES20.glGetAttribLocation(program, "aTex");
        uSt = GLES20.glGetUniformLocation(program, "uSt");

        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        textureId = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glViewport(0, 0, width, height);

        // The callback gets its own thread: the encoding thread is blocked in awaitFrame().
        callbackThread.start();
        surfaceTexture = new SurfaceTexture(textureId);
        surfaceTexture.setOnFrameAvailableListener(this, new Handler(callbackThread.getLooper()));
        decoderSurface = new Surface(surfaceTexture);
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] != GLES20.GL_TRUE) {
            throw new RuntimeException("GL: " + GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        synchronized (frameLock) {
            frameAvailable = true;
            frameLock.notifyAll();
        }
    }

    /** Blocks until the frame the decoder has just released reaches the texture. */
    void awaitFrame() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        synchronized (frameLock) {
            while (!frameAvailable) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) throw new RuntimeException("Декодер не отдал кадр вовремя");
                frameLock.wait(left);
            }
            frameAvailable = false;
        }
    }

    /** Paints the pending frame onto the encoder surface and submits it with its timestamp. */
    void drawFrame(long presentationTimeUs) {
        surfaceTexture.updateTexImage();
        surfaceTexture.getTransformMatrix(stMatrix);

        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId);

        quad.position(0);
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aPos);
        quad.position(2);
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(aTex);
        GLES20.glUniformMatrix4fv(uSt, 1, false, stMatrix, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeUs * 1000);
        EGL14.eglSwapBuffers(display, eglSurface);
    }

    void release() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            if (eglSurface != null && eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface);
            if (context != null && context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context);
            EGL14.eglReleaseThread();
            EGL14.eglTerminate(display);
            display = EGL14.EGL_NO_DISPLAY;
        }
        if (decoderSurface != null) decoderSurface.release();
        if (surfaceTexture != null) surfaceTexture.release();
        callbackThread.quitSafely();
    }
}

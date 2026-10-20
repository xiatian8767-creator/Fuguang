package com.example.parallaxwallpaper.wallpaper

import android.graphics.Bitmap
import com.example.parallaxwallpaper.depth.DepthField
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Manages the EGL context and OpenGL ES 2.0 rendering pipeline for the
 * parallax wallpaper effect.
 *
 * Rendering approach:
 *   - Full-screen quad with two textures: the source image and its depth map.
 *   - The fragment shader shifts each pixel's UV coordinates proportionally
 *     to its depth value and the current gyroscope tilt, making near objects
 *     move more than far objects (classic parallax).
 */
class ParallaxEngine {

    // ── EGL handles ────────────────────────────────────────────────────────
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE

    // ── GL objects ─────────────────────────────────────────────────────────
    private var program = 0
    private var quadVbo = 0
    private val textures = IntArray(2)  // [0] = image, [1] = depth

    // ── Uniform locations ──────────────────────────────────────────────────
    private var uImageTexture = 0
    private var uDepthTexture = 0
    private var uParallaxOffset = 0
    private var uParallaxStrength = 0
    private var imageWidth = 1
    private var imageHeight = 1
    var fitImage = false

    // ── State ──────────────────────────────────────────────────────────────
    private var surfaceWidth = 1
    private var surfaceHeight = 1
    var parallaxStrength: Float = 0.018f   // tuneable

    companion object {
        private const val TAG = "ParallaxEngine"

        // Full-screen quad: position (x,y) + uv (u,v) interleaved
        private val QUAD_VERTICES = floatArrayOf(
            // x      y     u     v
            -1f,  -1f,  0f,   1f,
             1f,  -1f,  1f,   1f,
            -1f,   1f,  0f,   0f,
             1f,   1f,  1f,   0f,
        )

        private val VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord   = aTexCoord;
            }
        """.trimIndent()

        /**
         * Fragment shader: samples depth at current UV, offsets image UV by
         * inverse depth * parallaxOffset * strength, with a small overscan.
         */
        private val FRAGMENT_SHADER = """
            precision highp float;
            uniform sampler2D uImageTexture;
            uniform sampler2D uDepthTexture;
            uniform vec2 uParallaxOffset;
            uniform float uParallaxStrength;
            varying vec2 vTexCoord;
            float sampleDepth(vec2 uv) {
                vec2 encodedDepth = texture2D(uDepthTexture, uv).rg;
                return dot(encodedDepth, vec2(256.0 / 257.0, 1.0 / 257.0));
            }
            void main() {
                vec2 uv = (vTexCoord - 0.5) / 1.08 + 0.5;
                vec2 offset = uParallaxOffset * uParallaxStrength;
                // A prefiltered, slope-bounded field has one continuous inverse.
                // Fixed-point sampling avoids discrete ray-hit jumps and masks
                // that change shape while tilting. CPU preparation bounds the
                // contraction to .42 even at maximum diagonal movement.
                vec2 shifted = uv;
                for (int i = 0; i < 12; i++) {
                    shifted = uv + offset * (sampleDepth(shifted) - 0.5);
                }
                gl_FragColor = texture2D(uImageTexture, clamp(shifted, 0.001, 0.999));
            }
        """.trimIndent()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────────

    /** Must be called from the render thread. */
    fun init(surface: Surface, width: Int, height: Int, recordable: Boolean = false) {
        surfaceWidth = width
        surfaceHeight = height

        setupEgl(surface, recordable)
        setupShaders()
        setupQuad()
        setupTextures()
    }

    fun onSurfaceChanged(width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        if (quadVbo != 0) GLES20.glDeleteBuffers(1, intArrayOf(quadVbo), 0)
        GLES20.glDeleteTextures(2, textures, 0)

        EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        EGL14.eglDestroySurface(eglDisplay, eglSurface)
        EGL14.eglDestroyContext(eglDisplay, eglContext)
        EGL14.eglTerminate(eglDisplay)

        eglDisplay = EGL14.EGL_NO_DISPLAY
        eglContext = EGL14.EGL_NO_CONTEXT
        eglSurface = EGL14.EGL_NO_SURFACE
    }

    // ── Texture uploads ────────────────────────────────────────────────────

    /** Upload source wallpaper image. Safe to call from the render thread. */
    fun uploadImage(bitmap: Bitmap) {
        imageWidth = bitmap.width
        imageHeight = bitmap.height
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)

    }

    /**
     * Upload a stable, 16-bit depth field; applies to cached photos as well.
     * [depth] is a FloatArray of size [w * h] normalized to [0, 1].
     */
    fun uploadDepth(depth: FloatArray, w: Int, h: Int) {
        require(w > 0 && h > 0 && depth.size == w * h)
        val packed = DepthField.pack(DepthField.prepare(depth, w, h))
        val buf = ByteBuffer.allocateDirect(packed.size).apply {
            put(packed)
            rewind()
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
        // Explicit alignment also keeps future depth layouts safe.
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
            w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf
        )
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        GLES20.glUseProgram(program)

    }

    // ── Render ─────────────────────────────────────────────────────────────

    /**
     * Draw one frame. [tiltX] and [tiltY] are normalized gyro offsets in [-1, 1].
     * Must be called from the render thread.
     */
    fun draw(tiltX: Float, tiltY: Float, presentationTimeNs: Long? = null) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        val imageAspect = imageWidth.toFloat() / imageHeight
        val surfaceAspect = surfaceWidth.toFloat() / surfaceHeight
        val vw: Int
        val vh: Int
        if ((imageAspect > surfaceAspect) == fitImage) {
            vw = surfaceWidth
            vh = (vw / imageAspect).toInt().coerceAtLeast(1)
        } else {
            vh = surfaceHeight
            vw = (vh * imageAspect).toInt().coerceAtLeast(1)
        }
        GLES20.glViewport((surfaceWidth - vw) / 2, (surfaceHeight - vh) / 2, vw, vh)
        // Bind textures
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[0])
        GLES20.glUniform1i(uImageTexture, 0)

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textures[1])
        GLES20.glUniform1i(uDepthTexture, 1)

        // Uniforms
        GLES20.glUniform2f(uParallaxOffset, tiltX.coerceIn(-1f, 1f), tiltY.coerceIn(-1f, 1f))
        GLES20.glUniform1f(uParallaxStrength, parallaxStrength.coerceIn(0f, 0.035f))

        // Quad
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        val stride = 4 * 4  // 4 floats × 4 bytes
        val posLoc = GLES20.glGetAttribLocation(program, "aPosition")
        val uvLoc  = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(posLoc)
        GLES20.glEnableVertexAttribArray(uvLoc)
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(uvLoc,  2, GLES20.GL_FLOAT, false, stride, 2 * 4)

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        GLES20.glDisableVertexAttribArray(posLoc)
        GLES20.glDisableVertexAttribArray(uvLoc)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)

        presentationTimeNs?.let { android.opengl.EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, it) }
        val swapped = EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        if (presentationTimeNs != null) check(swapped) { "渲染失败" }
    }

    // ── Private helpers ────────────────────────────────────────────────────

    private fun setupEgl(surface: Surface, recordable: Boolean) {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, null, 0, null, 0)

        val configAttribs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 0,
            EGL14.EGL_DEPTH_SIZE, 0,
            0x3142, if (recordable) 1 else -1, // EGL_RECORDABLE_ANDROID / EGL_DONT_CARE
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)

        val contextAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(
            eglDisplay, configs[0]!!, EGL14.EGL_NO_CONTEXT, contextAttribs, 0
        )
        eglSurface = EGL14.eglCreateWindowSurface(
            eglDisplay, configs[0]!!, surface, intArrayOf(EGL14.EGL_NONE), 0
        )
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glViewport(0, 0, surfaceWidth, surfaceHeight)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
    }

    private fun setupShaders() {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vs)
            GLES20.glAttachShader(it, fs)
            GLES20.glLinkProgram(it)
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        check(linked[0] != GLES20.GL_FALSE) { "Shader link error: ${GLES20.glGetProgramInfoLog(program)}" }

        uImageTexture   = GLES20.glGetUniformLocation(program, "uImageTexture")
        uDepthTexture   = GLES20.glGetUniformLocation(program, "uDepthTexture")
        uParallaxOffset = GLES20.glGetUniformLocation(program, "uParallaxOffset")
        uParallaxStrength = GLES20.glGetUniformLocation(program, "uParallaxStrength")
    }

    private fun setupQuad() {
        val buf: FloatBuffer = ByteBuffer.allocateDirect(QUAD_VERTICES.size * 4)
            .order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
                put(QUAD_VERTICES); rewind()
            }
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        quadVbo = ids[0]
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, quadVbo)
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, QUAD_VERTICES.size * 4, buf, GLES20.GL_STATIC_DRAW)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
    }

    private fun setupTextures() {
        GLES20.glGenTextures(2, textures, 0)
        for ((index, tex) in textures.withIndex()) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + index)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        }
        uploadDepth(floatArrayOf(0.5f), 1, 1)
    }

    private fun compileShader(type: Int, src: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, src)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == GLES20.GL_FALSE) {
            val message = GLES20.glGetShaderInfoLog(shader)
            Log.e(TAG, "Shader compile error: $message")
            GLES20.glDeleteShader(shader)
            error("Shader compile error: $message")
        }
        return shader
    }
}

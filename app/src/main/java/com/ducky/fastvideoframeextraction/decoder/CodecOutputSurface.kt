package com.ducky.fastvideoframeextraction.decoder

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.GLES20
import android.util.Log
import android.view.Surface
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock


/**
 * Created by Duc Ky Ngo on 9/15/2021.
 * duckyngo1705@gmail.com
 */
class CodecOutputSurface(width: Int, height: Int, private val isPortrait: Boolean) : SurfaceTexture.OnFrameAvailableListener {

    private var mTextureRender: SurfaceTextureRender?
    private var mSurfaceTexture: SurfaceTexture?
    private var mSurface: Surface? = null

    private var mEGLDisplay = EGL14.EGL_NO_DISPLAY
    private var mEGLContext = EGL14.EGL_NO_CONTEXT
    private var mEGLSurface = EGL14.EGL_NO_SURFACE
    private var mWidth = 0
    private var mHeight = 0

    private val mFrameSyncLock = ReentrantLock()
    private val condition = mFrameSyncLock.newCondition()

    private var mFrameAvailable = false

    private var mPixelBuf: ByteBuffer

    private var verbose = true

    init {
        require(!(width <= 0 || height <= 0))
        mWidth = width
        mHeight = height
        eglSetup()
        makeCurrent()

        // Creates interconnected instances of SurfaceTextureRender, SurfaceTexture, and Surface.
        mTextureRender = SurfaceTextureRender()
        mTextureRender?.surfaceCreated()
        if (verbose) Log.d(
            TAG,
            "textureID=" + mTextureRender?.getTextureId()
        )

        mSurfaceTexture = SurfaceTexture(mTextureRender?.getTextureId()!!)

        mSurfaceTexture?.setOnFrameAvailableListener(this)
        mSurface = Surface(mSurfaceTexture)
        mPixelBuf = ByteBuffer.allocateDirect(mWidth * mHeight * 4)
        mPixelBuf.order(ByteOrder.LITTLE_ENDIAN)
    }



    /**
     * Prepares EGL.  We want a GLES 2.0 context and a surface that supports pbuffer.
     */
    private fun eglSetup() {
        mEGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (mEGLDisplay === EGL14.EGL_NO_DISPLAY) {
            throw RuntimeException("unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(mEGLDisplay, version, 0, version, 1)) {
            mEGLDisplay = null
            throw RuntimeException("unable to initialize EGL14")
        }

        // Configure EGL for pbuffer and OpenGL ES 2.0, 24-bit RGB.
        val attributeList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(
                mEGLDisplay, attributeList, 0, configs, 0, configs.size,
                numConfigs, 0
            )
        ) {
            throw RuntimeException("unable to find RGB888+recordable ES2 EGL config")
        }

        // Configure context for OpenGL ES 2.0.
        val contextSpec = intArrayOf(
            EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
            EGL14.EGL_NONE
        )
        mEGLContext = EGL14.eglCreateContext(
            mEGLDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
            contextSpec, 0
        )
        checkEglError("eglCreateContext")
        if (mEGLContext == null) {
            throw RuntimeException("null context")
        }

        // Create a PBuffer surface.
        val surfaceAttribs = intArrayOf(
            EGL14.EGL_WIDTH, mWidth,
            EGL14.EGL_HEIGHT, mHeight,
            EGL14.EGL_NONE
        )
        mEGLSurface = EGL14.eglCreatePbufferSurface(mEGLDisplay, configs[0], surfaceAttribs, 0)
        checkEglError("eglCreatePBufferSurface")
        if (mEGLSurface == null) {
            throw RuntimeException("Surface was null")
        }
    }

    /**
     * Discard all resources held by this class, notably the EGL context.
     */
    fun release() {
        if (mEGLDisplay !== EGL14.EGL_NO_DISPLAY) {
            EGL14.eglDestroySurface(mEGLDisplay, mEGLSurface)
            EGL14.eglDestroyContext(mEGLDisplay, mEGLContext)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(mEGLDisplay)
        }
        mEGLDisplay = EGL14.EGL_NO_DISPLAY
        mEGLContext = EGL14.EGL_NO_CONTEXT
        mEGLSurface = EGL14.EGL_NO_SURFACE
        mSurface!!.release()

        // this causes a bunch of warnings that appear harmless but might confuse someone:
        //  W BufferQueue: [unnamed-3997-2] cancelBuffer: BufferQueue has been abandoned!
        //mSurfaceTexture.release();
        mTextureRender = null
        mSurface = null
        mSurfaceTexture = null
    }

    /**
     * Makes our EGL context and surface current.
     */
    private fun makeCurrent() {
        if (!EGL14.eglMakeCurrent(mEGLDisplay, mEGLSurface, mEGLSurface, mEGLContext)) {
            throw RuntimeException("eglMakeCurrent failed")
        }
    }

    /**
     * Returns the Surface.
     */
    fun getSurface(): Surface? {
        return mSurface
    }

    /**
     * Latches the next buffer into the texture.  Must be called from the thread that created
     * the CodecOutputSurface object.  (More specifically, it must be called on the thread
     * with the EGLContext that contains the GL texture object used by SurfaceTexture.)
     */
    fun awaitNewImage() {
        val timeoutMS = 2500
        mFrameSyncLock.withLock{
            while (!mFrameAvailable) {
                try {
                    // Wait for onFrameAvailable() to signal us.  Use a timeout to avoid
                    // stalling the test if it doesn't arrive.
                    condition.await(timeoutMS.toLong(), TimeUnit.MILLISECONDS)
                    if (!mFrameAvailable) {
                        // TODO: if "spurious wakeup", continue while loop
                        throw RuntimeException("Frame wait timed out")
                    }
                } catch (ie: InterruptedException) {
                    // shouldn't happen
                    throw RuntimeException(ie)
                }
            }
            mFrameAvailable = false
        }

        // Latch the data.
        mTextureRender?.checkGlError("before updateTexImage")
        mSurfaceTexture!!.updateTexImage()
    }

    /**
     * Draws the data from SurfaceTexture onto the current EGL surface.
     *
     * @param invert if set, render the image with Y inverted (0,0 in top left)
     */
    fun drawImage(invert: Boolean) {
        mSurfaceTexture?.let {
            mTextureRender?.drawFrame(it, invert)
        }
    }


    // SurfaceTexture callback
    override fun onFrameAvailable(st: SurfaceTexture?) {
        if (verbose) Log.d(TAG, "New frame available")
        mFrameSyncLock.withLock{
        if (mFrameAvailable) {
                throw RuntimeException("mFrameAvailable already set, frame could be dropped")
            }
            mFrameAvailable = true
            condition.signalAll()
        }
    }

    /**
     * Get the current frame by glReadPixels
     */
    @Throws(IOException::class)
    fun retrieveFrame(framePos: Int, timestamp: Long): Frame {
        // glReadPixels gives us a ByteBuffer filled with what is essentially big-endian RGBA
        // data (i.e. a byte of red, followed by a byte of green...).  To use the Bitmap
        // constructor that takes an int[] array with pixel data, we need an int[] filled
        // with little-endian ARGB data.
        //
        // If we implement this as a series of buf.get() calls, we can spend 2.5 seconds just
        // copying data around for a 720p frame.  It's better to do a bulk get() and then
        // rearrange the data in memory.  (For comparison, the PNG compress takes about 500ms
        // for a trivial frame.)
        //
        // So... we set the ByteBuffer to little-endian, which should turn the bulk IntBuffer
        // get() into a straight memcpy on most Android devices.  Our ints will hold ABGR data.
        // Swapping B and R gives us ARGB.  We need about 30ms for the bulk get(), and another
        // 270ms for the color swap.
        //
        // We can avoid the costly B/R swap here if we do it in the fragment shader (see
        // http://stackoverflow.com/questions/21634450/ ).
        //
        // Having said all that... it turns out that the Bitmap#copyPixelsFromBuffer()
        // method wants RGBA pixels, not ARGB, so if we create an empty bitmap and then
        // copy pixel data in we can avoid the swap issue entirely, and just copy straight
        // into the Bitmap from the ByteBuffer.
        //
        // Making this even more interesting is the upside-down nature of GL, which means
        // our output will look upside-down relative to what appears on screen if the
        // typical GL conventions are used.  (For ExtractMpegFrameTest, we avoid the issue
        // by inverting the frame when we render it.)
        //
        // Allocating large buffers is expensive, so we really want mPixelBuf to be
        // allocated ahead of time if possible.  We still get some allocations from the
        // Bitmap / PNG creation.
        mPixelBuf.rewind()
        GLES20.glReadPixels(0, 0, mWidth, mHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, mPixelBuf)

        var rotation = 0
        var isFlipX = false
        if (isPortrait){
            rotation = 180
            isFlipX = true
        }

        return Frame(mPixelBuf, mWidth, mHeight, framePos, timestamp, rotation, isFlipX, false)
    }

    /**
     * Checks for EGL errors.
     */
    private fun checkEglError(msg: String) {
        var error: Int
        if (EGL14.eglGetError().also { error = it } != EGL14.EGL_SUCCESS) {
            throw RuntimeException(msg + ": EGL error: 0x" + Integer.toHexString(error))
        }
    }

    companion object{
        const val TAG = "CodecOutputSurface"
    }
}
package com.polygraphene.alvr;

import android.graphics.Point;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.util.Log;
import android.util.Pair;
import android.util.TimeUtils;
import android.view.Surface;

import com.google.vr.ndk.base.AndroidCompat;
import com.google.vr.ndk.base.BufferSpec;
import com.google.vr.ndk.base.BufferViewport;
import com.google.vr.ndk.base.BufferViewportList;
import com.google.vr.ndk.base.Frame;
import com.google.vr.ndk.base.GvrApi;
import com.google.vr.ndk.base.GvrLayout;
import com.google.vr.ndk.base.SwapChain;
import com.google.vr.sdk.base.Eye;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Daydream-specific code and launch point.
 */
public class DaydreamLayersActivity extends AlvrActivity {
    private GvrLayout mGvrLayout;
    private GLSurfaceView mSurfaceView;
    private Renderer mRenderer;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        AndroidCompat.setSustainedPerformanceMode(this, true);
        AndroidCompat.setVrModeEnabled(this, true);

        mGvrLayout = new GvrLayout(this);
        mSurfaceView = new GLSurfaceView(this);
        mSurfaceView.setEGLContextClientVersion(2);
        mSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 8);
        mGvrLayout.setPresentationView(mSurfaceView);
        mGvrLayout.setKeepScreenOn(true);
        mGvrLayout.setAsyncReprojectionEnabled(true);

        mRenderer = new Renderer(mGvrLayout.getGvrApi());
        mSurfaceView.setRenderer(mRenderer);

        setContentView(mGvrLayout);
    }

    // Aggressive creation and shutdown.
    @Override
    protected void onStart() {
        super.onStart();
        mSurfaceView.onResume();
        mGvrLayout.onResume();
    }

    @Override
    protected void onStop() {
        mGvrLayout.onPause();
        mSurfaceView.onPause();
        mRenderer.shutdown();
        super.onStop();
        finish();
    }

    @Override
    public String getAppName() {
        return getString(R.string.dd_name);
    }

    private class Renderer implements GLSurfaceView.Renderer {
        private final GvrApi mGvrApi;

        private SwapChain swapChain;
        private final BufferViewportList viewportList;
        private final BufferViewport tmpViewport;
        private final Point targetSize = new Point();
        private final float[] headFromWorld = new float[16];

        private Mesh display;
        private Surface displaySurface;
        private SurfaceTexture displayTexture;
        private final float[] displayMatrix = new float[16];

        public Renderer(GvrApi api) {
            mGvrApi = api;
            viewportList = api.createBufferViewportList();
            tmpViewport = api.createBufferViewport();

            // Create quad that the video is rendered to. Size is based on the render target size.
            display = Mesh.createLrStereoQuad(1.33f, 1.33f, 0);
            Matrix.setIdentityM(displayMatrix, 0);
            Matrix.translateM(displayMatrix, 0, -.33f, -.33f, 0);
        }

        @Override
        public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
            // Standard initialization
            mGvrApi.initializeGl();
            mGvrApi.getMaximumEffectiveRenderTargetSize(targetSize);
            BufferSpec bufferSpec = mGvrApi.createBufferSpec();
            bufferSpec.setSize(targetSize);
            BufferSpec[] specList = {bufferSpec};
            swapChain = mGvrApi.createSwapChain(specList);
            bufferSpec.shutdown();

            int[] texId = new int[1];
            GLES20.glGenTextures(1, IntBuffer.wrap(texId));
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId[0]);
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            display.glInit(texId[0]);

            displayTexture = new SurfaceTexture(texId[0]);
            displayTexture.setDefaultBufferSize(1024, 1024);
            displaySurface = new Surface(displayTexture);


            vrThread = new VrThread(DaydreamLayersActivity.this, displayTexture, displaySurface, mGvrApi.getNativeGvrContext());
            vrThread.onSurfaceCreated();
            vrThread.start();
            vrThread.onResume();
        }

        @Override
        public void onSurfaceChanged(GL10 gl10, int i, int i1) {

        }

        private final float[] invRotationMatrix = new float[16];
        private final float[] translationMatrix = new float[16];

        @Override
        public void onDrawFrame(GL10 gl10) {
            Frame frame = swapChain.acquireFrame();
            long poseTime = System.nanoTime();
            mGvrApi.getHeadSpaceFromStartSpaceTransform(headFromWorld, poseTime  + TimeUnit.MILLISECONDS.toNanos(50));
            mGvrApi.getRecommendedBufferViewports(viewportList);
            frame.bindBuffer(0);

            // Extract quaternion since that's what steam expects.
            float[] m = headFromWorld;
            float t0 = m[0] + m[5] + m[10];
            float x, y, z, w;
            if (t0 >= 0) {
                float s = (float) Math.sqrt(t0 + 1);
                w = .5f * s;
                s = .5f / s;
                x = (m[9] - m[6]) * s;
                y = (m[2] - m[8]) * s;
                z = (m[4] - m[1]) * s;
            } else if (m[0] > m[5] && m[0] > m[10]) {
                float s = (float) Math.sqrt(1 + m[0] - m[5] - m[10]);
                x = s * .5f;
                s = .5f / s;
                y = (m[4] + m[1]) * s;
                z = (m[2] + m[8]) * s;
                w = (m[9] - m[6]) * s;
            } else if (m[5] > m[10]) {
                float s = (float) Math.sqrt(1 + m[5] - m[0] - m[10]);
                y = s * .5f;
                s = .5f / s;
                x = (m[4] + m[1]) * s;
                z = (m[9] + m[6]) * s;
                w = (m[2] - m[8]) * s;
            } else {
                float s = (float) Math.sqrt(1 + m[10] - m[0] - m[5]);
                z = s * .5f;
                s = .5f / s;
                x = (m[2] + m[8]) * s;
                y = (m[9] + m[6]) * s;
                w = (m[4] - m[1]) * s;
            }

            // Extract translation. But first undo the rotation.
            Matrix.transposeM(invRotationMatrix, 0, headFromWorld, 0);
            invRotationMatrix[3] = invRotationMatrix[7] = invRotationMatrix[11] = 0;
            Matrix.multiplyMM(translationMatrix, 0, invRotationMatrix, 0, headFromWorld, 0);
            //Log.e("XXX", Arrays.toString(translationMatrix));

            // Set tracking and save the current head pose. The headFromWorld value is saved in frameTracker via a call to trackFrame by the TrackingThread.
            vrThread.setTracking(-translationMatrix[12], 1.8f - translationMatrix[13], -translationMatrix[14], x, y, z, w, headFromWorld, poseTime);
            //Log.e("XXX", "saving frame " + z + " " + Arrays.toString(m) + Math.sqrt(x * x + y * y + z * z + w * w));

            // At this point, the pose is sent to the PC. On some future draw call, we read it back.
            // The code above this point and below this point should actually be in two separate
            // functions and threads.
            long renderedFrameIndex = vrThread.updateTexImage();
            Pair<float[], Long> p = frameTracker.get(renderedFrameIndex);
            frameTracker.remove(renderedFrameIndex - 100); // Reduce leaked memory.
            if (p == null) {
                // frames were dropped.
                m = headFromWorld;
            } else {
                m = p.first;
                Log.e("XXX", "using frame " + renderedFrameIndex + "@" + (System.nanoTime() - p.second)/1000000f);
            }

            // Draw quad across both eyes in one shot.
            GLES20.glClearColor(1, 0, 0, 1);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
            viewportList.get(0, tmpViewport);
            gl10.glViewport(0, 0, targetSize.x, targetSize.y);
            display.glDraw(displayMatrix);

            frame.unbind();
            frame.submit(viewportList, m);
        }

        public void shutdown() {
            viewportList.shutdown();
            tmpViewport.shutdown();
            swapChain.shutdown();
        }
    }

    // Frames are saved from the TrackerThread. Pretend there is no race condition.
    public static final ConcurrentHashMap<Long, Pair<float[], Long>> frameTracker = new ConcurrentHashMap<Long, Pair<float[], Long>>();
    public static void trackFrame(long frameIndex, float[] matrix, long poseTime) {
        frameTracker.put(frameIndex, Pair.create(matrix.clone(), poseTime));
        Log.e("XXX", "tracking frame " + frameIndex + " " + matrix[2]);
    }
}

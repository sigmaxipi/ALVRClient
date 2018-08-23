package com.polygraphene.alvr;

import android.opengl.EGLContext;
import android.util.Log;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

class TrackingThread extends ThreadBase {
    private static final String TAG = "TrackingThread";
    private int mRefreshRate;

    interface TrackingCallback {
        void onTracking(float[] position, float[] orientation);
    }

    private TrackingCallback mCallback;

    public TrackingThread(int refreshRate) {
        mRefreshRate = refreshRate;
    }

    public void setCallback(TrackingCallback callback) {
        mCallback = callback;
    }

    public void start(EGLContext mEGLContext, AlvrActivity mainActivity) {

        super.startBase();
    }

    public void onConnect(){
    }

    public void onDisconnect() {

    }

    @Override
    public void stopAndWait() {
        super.stopAndWait();
    }

    public float[] mPosition = new float[3];
    public float[] mOrientation = new float[]{0, 0, 0, 1};

    @Override
    public void run() {
        long previousFetchTime = System.nanoTime();
        while (!isStopped()) {
            mCallback.onTracking(mPosition, mOrientation);
            try {
                previousFetchTime += 1000 * 1000 * 1000 / mRefreshRate;
                long next = previousFetchTime - System.nanoTime();
                if (next < 0) {
                    // Exceed time!
                    previousFetchTime = System.nanoTime();
                } else {
                    TimeUnit.NANOSECONDS.sleep(next);
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.v(TAG, "TrackingThread has stopped.");
    }


    public String getErrorMessage() {
        return null;
    }
}

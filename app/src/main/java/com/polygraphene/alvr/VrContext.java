package com.polygraphene.alvr;

import android.app.Activity;
import android.content.res.AssetManager;
import android.util.Log;
import android.view.Surface;

import java.util.Arrays;

public class VrContext {

    static {
        System.loadLibrary("native-lib");
    }

    private long handle;


    public void initialize(long nativeGvrContext) {
        handle = initializeNative(nativeGvrContext);
    }


    public long fetchTrackingInfo(long udpManager, float[] position, float[] orientation) {
        //Log.e("XXX", ".fetchTrackingInfo " + Arrays.toString(position) + Arrays.toString(orientation));
        return fetchTrackingInfoNative(handle, udpManager, position, orientation);
    }

    private native long initializeNative(long nativeGvrContext);
    private native long fetchTrackingInfoNative(long handle, long udpManager, float[] position, float[] orientation);
}

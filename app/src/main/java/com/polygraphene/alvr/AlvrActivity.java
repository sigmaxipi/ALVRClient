package com.polygraphene.alvr;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.KeyEvent;
import android.view.SurfaceHolder;

import com.google.vr.sdk.base.GvrActivity;

/**
 * Root ALVR Activity for both Oculus & Daydream.
 */
abstract class AlvrActivity extends GvrActivity {
    private final static String TAG = "AlvrActivity";

    static {
        //System.loadLibrary("native-lib");
    }

    protected VrThread vrThread = null;

    protected final SurfaceHolder.Callback mCallback = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(final SurfaceHolder holder) {
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            vrThread.onSurfaceChanged(holder.getSurface());
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            vrThread.onSurfaceDestroyed();
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.v(TAG, "onCreate: Starting VrThread");
    }

    @Override
    protected void onResume() {
        super.onResume();

        if(vrThread != null) {
            vrThread.onResume();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        if(vrThread != null) {
            vrThread.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        Log.v(TAG, "onDestroy: Stopping VrThread.");
        if(vrThread != null) {
            vrThread.interrupt();
            try {
                vrThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        Log.v(TAG, "onDestroy: VrThread has stopped.");
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        //Log.v(TAG, "dispatchKeyEvent: " + event.getKeyCode());
        if(event.getAction() == KeyEvent.ACTION_DOWN || event.getAction() == KeyEvent.ACTION_UP) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_UP)
            {
                adjustVolume(1);
                return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_VOLUME_DOWN)
            {
                adjustVolume(-1);
                return true;
            }

            vrThread.onKeyEvent(event.getKeyCode(), event.getAction());
            return true;
        }else{
            return super.dispatchKeyEvent(event);
        }
    }

    private void adjustVolume(int direction)
    {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0);
    }

    abstract public String getAppName();

    public String getVersionName(){
        try {
            PackageInfo pInfo = this.getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            return getAppName() + " v" + version;
        } catch (PackageManager.NameNotFoundException e) {
            e.printStackTrace();
            return getAppName() + " Unknown version";
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (!ArThread.onRequestPermissionsResult(this)) {
            finish();
        }
    }
}

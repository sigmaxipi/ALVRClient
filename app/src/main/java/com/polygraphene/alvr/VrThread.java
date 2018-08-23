package com.polygraphene.alvr;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLContext;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.util.Log;
import android.view.Surface;

import java.nio.IntBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

class VrThread extends Thread {
    private static final String TAG = "VrThread";

    private static final String KEY_SERVER_ADDRESS = "serverAddress";
    private static final String KEY_SERVER_PORT = "serverPort";

    private static final int PORT = 9944;
    private final long nativeGvrContext;

    private AlvrActivity mMainActivity;

    private VrContext mVrContext = new VrContext();
    private ThreadQueue mQueue = null;
    private boolean mResumed = false;

    private SurfaceTexture mSurfaceTexture;
    private Surface mSurface;

    private final Object mWaiter = new Object();
    private boolean mFrameAvailable = false;

    private LoadingTexture mLoadingTexture = new LoadingTexture();

    // Worker threads
    private DecoderThread mDecoderThread;
    private UdpReceiverThread mReceiverThread;

    private EGLContext mEGLContext;

    public VrThread(AlvrActivity mainActivity, SurfaceTexture texture, Surface surface, long nativeGvrContext) {
        this.mMainActivity = mainActivity;
        this.nativeGvrContext = nativeGvrContext;

        mSurfaceTexture = texture;
        mSurfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
            @Override
            public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                synchronized (mWaiter) {
                    mFrameAvailable = true;
                    //Log.i("XXX", "onFrameAvailable " + mFrameAvailable);
                    mWaiter.notifyAll();
                }
            }
        });
        mSurface = surface;
    }

    public void onSurfaceCreated() {
        Log.v(TAG, "VrThread.onSurfaceCreated.");
    }

    public void onSurfaceChanged(final Surface surface) {
        Log.v(TAG, "VrThread.onSurfaceChanged.");
    }

    public void onSurfaceDestroyed() {
        Log.v(TAG, "VrThread.onSurfaceDestroyed.");
    }

    public void onResume() {
        synchronized (mWaiter) {
            mResumed = true;
            mWaiter.notifyAll();
        }

        send(new Runnable() {
            @Override
            public void run() {
                Log.v(TAG, "VrThread.onResume: Starting worker threads.");

                mReceiverThread = new UdpReceiverThread(mUdpReceiverCallback, mVrContext);
                mReceiverThread.setPort(PORT);
                loadConnectionState();
                mDecoderThread = new DecoderThread(mReceiverThread, mSurface, mMainActivity);

                try {
                    mDecoderThread.start();
                    if (!mReceiverThread.start(true, mEGLContext, mMainActivity)) {
                        Log.e(TAG, "FATAL: Initialization of ReceiverThread failed.");
                        return;
                    }
                } catch (IllegalArgumentException | IllegalStateException | SecurityException e) {
                    e.printStackTrace();
                }

                Log.v(TAG, "VrThread.onResume: mVrContext.onResume().");
            }
        });
        Log.v(TAG, "VrThread.onResume: Worker threads has started.");
    }

    public void onPause() {
        Log.v(TAG, "VrThread.onPause: Stopping worker threads.");
        synchronized (mWaiter) {
            mResumed = false;
            mWaiter.notifyAll();
        }
        // DecoderThread must be stopped before ReceiverThread
        if (mDecoderThread != null) {
            Log.v(TAG, "VrThread.onPause: Stopping DecoderThread.");
            mDecoderThread.stopAndWait();
        }
        if (mReceiverThread != null) {
            Log.v(TAG, "VrThread.onPause: Stopping ReceiverThread.");
            mReceiverThread.stopAndWait();
        }

        Log.v(TAG, "VrThread.onPause: mVrContext.onPause().");
        Log.v(TAG, "VrThread.onPause: All worker threads has stopped.");
    }

    public void onKeyEvent(final int keyCode, final int action) {
    }

    // Called from onDestroy
    @Override
    public void interrupt() {
        post(new Runnable() {
            @Override
            public void run() {
                mLoadingTexture.destroyTexture();
                mQueue.interrupt();
            }
        });
    }

    private void post(Runnable runnable) {
        waitLooperPrepared();
        mQueue.post(runnable);
    }

    private void send(Runnable runnable) {
        waitLooperPrepared();
        mQueue.send(runnable);
    }

    private void waitLooperPrepared() {
        synchronized (this) {
            while (mQueue == null) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    @Override
    public void run() {
        setName("VrThread");
        Log.v(TAG, "VrThread started.");

        synchronized (this) {
            mQueue = new ThreadQueue();
            notifyAll();
        }

       mVrContext.initialize(nativeGvrContext);

        //mLoadingTexture.initializeMessageCanvas(mVrContext.getLoadingTexture());
        //mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\nLoading...");

        mEGLContext = EGL14.eglGetCurrentContext();

        Log.v(TAG, "Start loop of VrThread.");
        while (mQueue.waitIdle()) {
            if (!mResumed) {
                mQueue.waitNext();
                continue;
            }
            render();
        }

        Log.v(TAG, "Destroying vrapi state.");
    }

    public void setTracking(float px, float py, float pz, float rx, float ry, float rz, float rw, float[] m, long poseTime) {
        if(mReceiverThread == null) {
            return;
        }

        synchronized(mReceiverThread) {
            // Hack to save the post across threads. Pretend there is no race condition.
            mReceiverThread.xyz[0] = px;
            mReceiverThread.xyz[1] = py;
            mReceiverThread.xyz[2] = pz;
            mReceiverThread.xyzw[0] = rx;
            mReceiverThread.xyzw[1] = ry;
            mReceiverThread.xyzw[2] = rz;
            mReceiverThread.xyzw[3] = rw;
            System.arraycopy(m, 0, mReceiverThread.m, 0, 16);
            mReceiverThread.poseTime = poseTime;
        }
    }

    public void render() {
        if(mReceiverThread == null || mDecoderThread == null || mReceiverThread == null) {
            return;
        }
        if (mReceiverThread.isConnected() && mDecoderThread.isFrameAvailable() && mReceiverThread.getErrorMessage() == null) {
            long renderedFrameIndex = waitFrame();
            if (renderedFrameIndex != -1) {
               // mVrContext.render(renderedFrameIndex);
            }
        } else {
            if (mReceiverThread.getErrorMessage() != null) {
                mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\n \n!!! Error on ARCore initialization !!!\n" + mReceiverThread.getErrorMessage());
            } else {
                if (mReceiverThread.isConnected()) {
                    //mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\n \nConnected!\nStreaming will begin soon!");
                } else {
                    //mLoadingTexture.drawMessage(mMainActivity.getVersionName() + "\n \nPress CONNECT button\non ALVR server.");
                }
            }
            //mVrContext.renderLoading();
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    long renderedFrameIndex = -1;
    public long waitFrame() {
        if(mDecoderThread == null) {
            return 0;
        }
        synchronized (mWaiter) {
            mFrameAvailable = false;
            renderedFrameIndex = mDecoderThread.render();
            if (renderedFrameIndex == -1) {
                return -1;
            }
            while (!mFrameAvailable) {
                try {
                    //Log.i("XXX", "waiting for frame ");
                    mWaiter.wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            try {
                //Log.i("XXX", "waiting for tex update ");
                synchronized (mTexWaiter) {
                    readyForUpdate.set(true);
                    mTexWaiter.wait();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            //mSurfaceTexture.updateTexImage();

            return renderedFrameIndex;
        }
    }

    final Object mTexWaiter = new Object();
    AtomicBoolean readyForUpdate = new AtomicBoolean ();
    public long updateTexImage() {
        //Log.i("XXX", "updateTexImage " + mFrameAvailable);

        long renderedFrame;
        synchronized (mTexWaiter) {
            renderedFrame = renderedFrameIndex;
            mSurfaceTexture.updateTexImage();
            mTexWaiter.notifyAll();
            if (readyForUpdate.compareAndSet(true, false)) {
                //Log.i("XXX", "updateTexImage GOOD");
            } else {
                //Log.i("XXX", "updateTexImage FAIL");
            }
        }
        return renderedFrame;
    }


    private UdpReceiverThread.Callback mUdpReceiverCallback = new UdpReceiverThread.Callback() {
        @Override
        public void onConnected(final int width, final int height, final int codec, final int frameQueueSize) {
            // We must wait completion of notifyGeometryChange
            // to ensure the first video frame arrives after notifyGeometryChange.
            send(new Runnable() {
                @Override
                public void run() {
                    //mVrContext.setFrameGeometry(width, height);
                    mDecoderThread.onConnect(codec, frameQueueSize);
                }
            });
        }

        @Override
        public void onChangeSettings(int enableTestMode, int suspend, int frameQueueSize) {
            //mVrContext.onChangeSettings(enableTestMode, suspend);
            mDecoderThread.setFrameQueueSize(frameQueueSize);
        }

        @Override
        public void onShutdown(String serverAddr, int serverPort) {
            saveConnectionState(serverAddr, serverPort);
        }

        @Override
        public void onDisconnect() {
            mDecoderThread.onDisconnect();
        }
    };

    private void saveConnectionState(String serverAddress, int serverPort) {
        Log.v(TAG, "save connection state: " + serverAddress + " " + serverPort);
        SharedPreferences pref = mMainActivity.getSharedPreferences("pref", Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = pref.edit();
        // If server address is NULL, it means no preserved connection.
        edit.putString(KEY_SERVER_ADDRESS, serverAddress);
        edit.putInt(KEY_SERVER_PORT, serverPort);
        edit.apply();
    }

    private void loadConnectionState() {
        SharedPreferences pref = mMainActivity.getSharedPreferences("pref", Context.MODE_PRIVATE);
        String serverAddress = pref.getString(KEY_SERVER_ADDRESS, null);
        int serverPort = pref.getInt(KEY_SERVER_PORT, 0);

        saveConnectionState(null, 0);

        Log.v(TAG, "load connection state: " + serverAddress + " " + serverPort);
        mReceiverThread.recoverConnectionState(serverAddress, serverPort);
    }

    public boolean isTracking() {
        return mVrContext != null && mReceiverThread != null
                && mReceiverThread.isConnected();
    }
}

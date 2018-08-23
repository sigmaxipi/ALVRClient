package com.polygraphene.alvr;

import android.opengl.EGLContext;
import android.util.Log;

import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

class UdpReceiverThread extends ThreadBase implements NALParser, TrackingThread.TrackingCallback {
    private static final String TAG = "UdpReceiverThread";

    static {
        System.loadLibrary("native-lib");
    }

    private static final String BROADCAST_ADDRESS = "255.255.255.255";

    private TrackingThread mTrackingThread;
    private VrContext mVrContext;
    private int mPort;
    private boolean mIs75Hz = false;
    private boolean mInitialized = false;
    private boolean mInitializeFailed = false;

    private String mPreviousServerAddress;
    private int mPreviousServerPort;

    interface Callback {
        void onConnected(int width, int height, int codec, int frameQueueSize);

        void onChangeSettings(int enableTestMode, int suspend, int frameQueueSize);

        void onShutdown(String serverAddr, int serverPort);

        void onDisconnect();
    }

    private Callback mCallback;

    UdpReceiverThread(Callback callback, VrContext vrContext) {
        mCallback = callback;
        mVrContext = vrContext;
    }

    public void setPort(int port) {
        mPort = port;
    }

    private String getDeviceName() {
        String manufacturer = android.os.Build.MANUFACTURER;
        String model = android.os.Build.MODEL;
        if (model.toLowerCase().startsWith(manufacturer.toLowerCase())) {
            return model;
        } else {
            return manufacturer + " " + model;
        }
    }

    public void recoverConnectionState(String serverAddress, int serverPort) {
        mPreviousServerAddress = serverAddress;
        mPreviousServerPort = serverPort;
    }

    public boolean start(final boolean is75Hz, EGLContext mEGLContext, AlvrActivity mainActivity) {
        mTrackingThread = new TrackingThread(is75Hz ? 75 : 60);
        mTrackingThread.setCallback(this);
        mIs75Hz = is75Hz;

        super.startBase();

        synchronized (this) {
            while (!mInitialized && !mInitializeFailed) {
                try {
                    wait();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }

        if(!mInitializeFailed) {
            mTrackingThread.start(mEGLContext, mainActivity);
        }
        return !mInitializeFailed;
    }

    @Override
    public void stopAndWait() {
        mTrackingThread.stopAndWait();
        interruptNative();
        super.stopAndWait();
    }

    @Override
    public void run() {
        try {
            String[] broadcastList = getBroadcastAddressList();

            int ret = initializeSocket(mPort, getDeviceName(), broadcastList, mIs75Hz);
            if (ret != 0) {
                Log.e(TAG, "Error on initializing socket. Code=" + ret + ".");
                synchronized (this) {
                    mInitializeFailed = true;
                    notifyAll();
                }
                return;
            }
            synchronized (this) {
                mInitialized = true;
                notifyAll();
            }
            Log.v(TAG, "UdpReceiverThread initialized.");

            runLoop(mPreviousServerAddress, mPreviousServerPort);
        } finally {
            mCallback.onShutdown(getServerAddress(), getServerPort());
            closeSocket();
        }

        Log.v(TAG, "UdpReceiverThread stopped.");
    }

    // List broadcast address from all interfaces except for mobile network.
    // We should send all broadcast address to use USB tethering or VPN.
    private String[] getBroadcastAddressList() {
        List<String> ret = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();

            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface networkInterface = networkInterfaces.nextElement();

                if (networkInterface.getName().startsWith("rmnet")) {
                    // Ignore mobile network interfaces.
                    Log.v(TAG, "Ignore interface. Name=" + networkInterface.getName());
                    continue;
                }

                List<InterfaceAddress> interfaceAddresses = networkInterface.getInterfaceAddresses();

                String address = "";
                for (InterfaceAddress interfaceAddress : interfaceAddresses) {
                    address += interfaceAddress.toString() + ", ";
                    // getBroadcast() return non-null only when ipv4.
                    if (interfaceAddress.getBroadcast() != null) {
                        ret.add(interfaceAddress.getBroadcast().getHostAddress());
                    }
                }
                Log.v(TAG, "Interface: Name=" + networkInterface.getName() + " Address=" + address + " 2=" + address);
            }
            Log.v(TAG, ret.size() + " broadcast addresses were found.");
            for (String address : ret) {
                Log.v(TAG, address);
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        if (ret.size() == 0) {
            ret.add(BROADCAST_ADDRESS);
        }
        return ret.toArray(new String[]{});
    }

    // Hack to save the post across threads. Pretend there is no race condition.
    public float[] xyz = new float[3];
    public float[] xyzw = new float[4];
    public float[] m = new float[16];
    public long poseTime;

    @Override
    public synchronized void onTracking(float[] position, float[] orientation) {
        if (isTracking()) {
            DaydreamLayersActivity.trackFrame(mVrContext.fetchTrackingInfo(getPointer(), xyz, xyzw), m, poseTime);
        }
    }

    public boolean isTracking() {
        return isConnected();
    }

    public String getErrorMessage() {
        return mTrackingThread.getErrorMessage();
    }

    // called from native
    @SuppressWarnings("unused")
    public void onConnected(int width, int height, int codec, int frameQueueSize) {
        Log.v(TAG, "onConnected is called.");
        mCallback.onConnected(width, height, codec, frameQueueSize);
        mTrackingThread.onConnect();
    }

    @SuppressWarnings("unused")
    public void onDisconnected() {
        Log.v(TAG, "onDisconnected is called.");
        mCallback.onDisconnect();
        mTrackingThread.onDisconnect();
    }

    @SuppressWarnings("unused")
    public void onChangeSettings(int EnableTestMode, int suspend, int frameQueueSize) {
        mCallback.onChangeSettings(EnableTestMode, suspend, frameQueueSize);
    }

    private native int initializeSocket(int port, String deviceName, String[] broadcastAddrList, boolean is72Hz);
    private native void closeSocket();
    private native void runLoop(String serverAddress, int serverPort);
    private native void interruptNative();

    public native boolean isConnected();

    private native long getPointer();
    private native String getServerAddress();
    private native int getServerPort();

    //
    // NALParser interface
    //

    @Override
    public native int getNalListSize();

    @Override
    public native NAL waitNal();

    @Override
    public native NAL getNal();

    @Override
    public native void recycleNal(NAL nal);

    @Override
    public native void flushNALList();

    @Override
    public native void notifyWaitingThread();

    @Override
    public native void clearStopped();
}
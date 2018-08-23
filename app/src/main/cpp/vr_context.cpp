#include <unistd.h>
#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <string>
#include <map>
#include <vector>
#include "utils.h"
#include "vr_context.h"
#include "latency_collector.h"
#include "packet_types.h"
#include "udp.h"
#include "asset.h"

#include "vr/gvr/capi/include/gvr.h"
#include "vr/gvr/capi/include/gvr_controller.h"
#include "vr/gvr/capi/include/gvr_types.h"


struct TrackingFrame {
    ovrTracking2 tracking;
    uint64_t frameIndex;
    uint64_t fetchTime;
    double displayTime;
};

uint64_t FrameIndex = 0;

gvr::ControllerApi* controllerApi;
gvr::ControllerState controllerState;

void VrContext::setControllerInfo(TrackingInfo *packet, double displayTime) {
    gvr_mat4f identity;
    for (int i = 0; i < 4; ++i) {
        for (int j = 0; j < 4; ++j) {
            identity.m[j][i] = (i==j)? 1 : 0;
        }
    }
    controllerApi->ApplyArmModel(gvr::kControllerRightHanded, gvr::kArmModelBehaviorIgnoreGaze, identity);
    controllerState.Update(*controllerApi);
    if (controllerState.GetConnectionState() != gvr::kControllerConnected) {
        LOG("No Daydream controller %x", controllerState.GetConnectionState());
        return;
    }

    packet->flags |= TrackingInfo::FLAG_CONTROLLER_ENABLE;
    packet->flags |= TrackingInfo::FLAG_CONTROLLER_OCULUSGO;
    packet->controllerButtons = 0;

    if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_HOME)) {
        // For this hack to work, the Home button needs to be held down >300ms to starting the
        // Daydream Dashboard. Then the volume buttons have to be pressed <300ms so that the
        // screen recording chord isn't triggered.
        if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_VOLUME_DOWN)) {
            packet->controllerButtons |= 0x00200000; //ovrButton_Back
        }
        if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_VOLUME_UP)) {
            // Map to SteamVR Home button?
        }
        if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_CLICK)) {
            // ???
        }
        if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_APP)) {
            // Map to SteamVR Home button?
        }

        if (controllerState.IsTouching()) {
            // Support clicking on the 4 sides of the touchpad???
            if (controllerState.GetTouchPos().x > .75f) {
                // ???
            }
            // http://blog.riftcat.com/2018/07/dev-update-41-vridge-22-beta.html has special
            // behavior at the sides.
        }
    } else {
        if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_CLICK)) {
            packet->controllerButtons |= 0x00100000; //ovrButton_Enter == OVR controller touchpad click
        }
        if (controllerState.GetButtonDown(GVR_CONTROLLER_BUTTON_APP)) {
            packet->controllerButtons |= 0x00000001; //ovrButton_A == OVR controller trigger
        }
        if (controllerState.IsTouching()) {
            packet->flags |= TrackingInfo::FLAG_CONTROLLER_TRACKPAD_TOUCH;
            packet->controllerTrackpadPosition.x =   controllerState.GetTouchPos().x * 2.f - 1.f;
            packet->controllerTrackpadPosition.y = -(controllerState.GetTouchPos().y * 2.f - 1.f);
        }
    }

    packet->controllerBatteryPercentRemaining = controllerState.GetBatteryLevel() * 20;

    auto q = controllerState.GetOrientation();
    packet->controller_Pose_Orientation.x = q.qx;
    packet->controller_Pose_Orientation.y = q.qy;
    packet->controller_Pose_Orientation.z = q.qz;
    packet->controller_Pose_Orientation.w = q.qw;
    // TODO ApplyArmModel(..)
    auto p = controllerState.GetPosition();
    packet->controller_Pose_Position.x = p.x;
    packet->controller_Pose_Position.y = p.y + 1.8;
    packet->controller_Pose_Position.z = p.z;

    LOG("P %f %f %f", p.x, packet->controller_Pose_Position.y, p.z);

}


// Called TrackingThread. So, we can't use this->env.
void VrContext::sendTrackingInfo(TrackingInfo *packet, double displayTime, ovrTracking2 *tracking,
                                 const ovrVector3f *other_tracking_position,
                                 const ovrQuatf *other_tracking_orientation) {
    memset(packet, 0, sizeof(TrackingInfo));

    uint64_t clientTime = getTimestampUs();

    packet->type = ALVR_PACKET_TYPE_TRACKING_INFO;
    packet->flags = 0;
    packet->clientTime = clientTime;
    packet->FrameIndex = FrameIndex;
    packet->predictedDisplayTime = displayTime;

    memcpy(&packet->HeadPose_Pose_Orientation, &tracking->HeadPose.Pose.Orientation,
           sizeof(ovrQuatf));
    memcpy(&packet->HeadPose_Pose_Position, &tracking->HeadPose.Pose.Position, sizeof(ovrVector3f));

    if (other_tracking_position && other_tracking_orientation) {
        //packet->flags |= TrackingInfo::FLAG_OTHER_TRACKING_SOURCE;
        memcpy(&packet->HeadPose_Pose_Position, other_tracking_position,
               sizeof(ovrVector3f));
        memcpy(&packet->HeadPose_Pose_Orientation, other_tracking_orientation,
               sizeof(ovrQuatf));
    }

    setControllerInfo(packet, displayTime);
    //packet->HeadPose_Pose_Orientation.x += w;
}

typedef std::map<uint64_t, std::shared_ptr<TrackingFrame> > TRACKING_FRAME_MAP;

typedef enum
{
    BACK_BUTTON_STATE_NONE,
    BACK_BUTTON_STATE_PENDING_SHORT_PRESS,
    BACK_BUTTON_STATE_SKIP_UP
} ovrBackButtonState;

TRACKING_FRAME_MAP trackingFrameMap;
Mutex trackingFrameMutex;
static const int MAXIMUM_TRACKING_FRAMES = 180;


// Called TrackingThread. So, we can't use this->env.
void VrContext::fetchTrackingInfo(JNIEnv *env_, UdpManager *udpManager, ovrVector3f *position,
                                     ovrQuatf *orientation) {
    std::shared_ptr<TrackingFrame> frame(new TrackingFrame());

    FrameIndex++;

    frame->frameIndex = FrameIndex;
    frame->fetchTime = getTimestampUs();
    frame->displayTime = frame->fetchTime + 30000;
    //frame->tracking = getTimestampUs();

    //frame->displayTime = vrapi_GetPredictedDisplayTime(Ovr, FrameIndex);
    //frame->tracking = vrapi_GetPredictedTracking2(Ovr, frame->displayTime);

    {
        MutexLock lock(trackingFrameMutex);
        trackingFrameMap.insert(
                std::pair<uint64_t, std::shared_ptr<TrackingFrame> >(FrameIndex, frame));
        if (trackingFrameMap.size() > MAXIMUM_TRACKING_FRAMES) {
            trackingFrameMap.erase(trackingFrameMap.cbegin());
        }
    }

    TrackingInfo info;
    sendTrackingInfo(&info, frame->displayTime, &frame->tracking, position, orientation);
    udpManager->send(&info, sizeof(info));

}

extern "C"
JNIEXPORT jlong JNICALL
Java_com_polygraphene_alvr_VrContext_initializeNative(JNIEnv *env, jobject instance, jlong nativeGvrContext) {
    VrContext *context = new VrContext();
    controllerApi = new gvr::ControllerApi();
    controllerApi->Init(gvr::ControllerApi::DefaultOptions() | GVR_CONTROLLER_ENABLE_ARM_MODEL, (gvr_context*)nativeGvrContext);
    controllerApi->Resume();
    return (jlong) context;
}

// Called from TrackingThread
extern "C"
JNIEXPORT long JNICALL
Java_com_polygraphene_alvr_VrContext_fetchTrackingInfoNative(JNIEnv *env, jobject instance,
                                                             jlong handle,
                                                             jlong udpManager,
                                                             jfloatArray position_,
                                                             jfloatArray orientation_) {
    if(position_ != NULL && orientation_ != NULL) {
        ovrVector3f position;
        ovrQuatf orientation;

        jfloat *position_c = env->GetFloatArrayElements(position_, NULL);
        memcpy(&position, position_c, sizeof(float) * 3);
        env->ReleaseFloatArrayElements(position_, position_c, 0);

        jfloat *orientation_c = env->GetFloatArrayElements(orientation_, NULL);
        memcpy(&orientation, orientation_c, sizeof(float) * 4);
        env->ReleaseFloatArrayElements(orientation_, orientation_c, 0);

        ((VrContext *) handle)->fetchTrackingInfo(env, (UdpManager *)udpManager, &position, &orientation);
    }else {
        ((VrContext *) handle)->fetchTrackingInfo(env, (UdpManager *) udpManager, NULL, NULL);
    }
    return FrameIndex;
}

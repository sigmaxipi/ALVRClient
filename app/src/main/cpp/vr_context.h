#ifndef ALVRCLIENT_VR_CONTEXT_H
#define ALVRCLIENT_VR_CONTEXT_H

#include <memory>
#include <map>
#include <jni.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <android/input.h>
#include "packet_types.h"
#include "utils.h"
#include "udp.h"

/// A 3D vector.
typedef struct ovrVector3f_
{
    float x, y, z;
} ovrVector3f;



typedef struct ovrPosef_
{
    ovrQuatf	Orientation;
    ovrVector3f	Position;
} ovrPosef;

typedef struct ovrRigidBodyPosef_
{
    ovrPosef		Pose;
    ovrVector3f		AngularVelocity;
    ovrVector3f		LinearVelocity;
    ovrVector3f		AngularAcceleration;
    ovrVector3f		LinearAcceleration;
    double			TimeInSeconds;			//< Absolute time of this pose.
    double			PredictionInSeconds;	//< Seconds this pose was predicted ahead.
} ovrRigidBodyPosef;


/// Tracking state at a given absolute time.
typedef struct ovrTracking2_
{
    /// Sensor status described by ovrTrackingStatus flags.
    unsigned int		Status;


    /// Predicted head configuration at the requested absolute time.
    /// The pose describes the head orientation and center eye position.
    ovrRigidBodyPosef	HeadPose;
    struct
    {
        ovrMatrix4f			ProjectionMatrix;
        ovrMatrix4f			ViewMatrix;
    } Eye[ 2 ];
} ovrTracking2;

class VrContext {
public:


    void setControllerInfo(TrackingInfo *packet, double displayTime);

    void sendTrackingInfo(TrackingInfo *packet, double displayTime, ovrTracking2 *tracking,
                              const ovrVector3f *other_tracking_position,
                              const ovrQuatf *other_tracking_orientation);
    void fetchTrackingInfo(JNIEnv *env_, UdpManager *udpManager,
                           ovrVector3f *position, ovrQuatf *orientation);
};

#endif //ALVRCLIENT_VR_CONTEXT_H

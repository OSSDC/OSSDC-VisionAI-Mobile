/*
 * Copyright (C) 2021 Marius Slavescu - OSSDC.org. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.ossdc.visionai.webrtc;

import android.databinding.DataBindingUtil;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;

import org.ossdc.visionai.R;
import org.ossdc.visionai.databinding.ActivityPeerConnectionBinding;

import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoRenderer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;

public class MediaStreamActivity extends AppCompatActivity {
    private static final String TAG = "PeerConnectionAct";
    public static final int VIDEO_RESOLUTION_WIDTH = 1280*2;
    public static final int VIDEO_RESOLUTION_HEIGHT = 720*2;
    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";

    public static final int FPS = 30;
    private static final String DTLS_SRTP_KEY_AGREEMENT_CONSTRAINT = "DtlsSrtpKeyAgreement";

    private ActivityPeerConnectionBinding binding;
    private EglBase rootEglBase;
    private VideoTrack videoTrackFromCamera;
    private PeerConnectionFactory factory;
    private PeerConnection localPeerConnection, remotePeerConnection;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = DataBindingUtil.setContentView(this, R.layout.activity_peer_connection);

        initializeSurfaceViews();

        initializePeerConnectionFactory();

        createVideoTrackFromCameraAndShowIt();

        initializePeerConnections();

        startStreamingVideo();
    }

    private void initializeSurfaceViews() {
        rootEglBase = EglBase.create();
        binding.surfaceView.init(rootEglBase.getEglBaseContext(), null);
        binding.surfaceView.setEnableHardwareScaler(true);
        binding.surfaceView.setMirror(true);

        binding.surfaceView2.init(rootEglBase.getEglBaseContext(), null);
        binding.surfaceView2.setEnableHardwareScaler(true);
        binding.surfaceView2.setMirror(false);
    }

    private void initializePeerConnectionFactory() {
        PeerConnectionFactory.initializeAndroidGlobals(this, true, true, true);
        factory = new PeerConnectionFactory(null);
        factory.setVideoHwAccelerationOptions(rootEglBase.getEglBaseContext(), rootEglBase.getEglBaseContext());
    }

    private void createVideoTrackFromCameraAndShowIt() {
        VideoCapturer videoCapturer = createVideoCapturer();
        VideoSource videoSource = factory.createVideoSource(videoCapturer);
        videoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS);

        videoTrackFromCamera = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        videoTrackFromCamera.setEnabled(true);
        videoTrackFromCamera.addRenderer(new VideoRenderer(binding.surfaceView));
    }

    private void initializePeerConnections() {
        localPeerConnection = createPeerConnection(factory, true);
        remotePeerConnection = createPeerConnection(factory, false);
    }

    private void startStreamingVideo() {
        MediaStream mediaStream = factory.createLocalMediaStream("ARDAMS");
        mediaStream.addTrack(videoTrackFromCamera);
        localPeerConnection.addStream(mediaStream);

        MediaConstraints sdpMediaConstraints = new MediaConstraints();

        localPeerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "onCreateSuccess: ");
                localPeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                remotePeerConnection.setRemoteDescription(new SimpleSdpObserver(), sessionDescription);

                remotePeerConnection.createAnswer(new SimpleSdpObserver() {
                    @Override
                    public void onCreateSuccess(SessionDescription sessionDescription) {
                        localPeerConnection.setRemoteDescription(new SimpleSdpObserver(), sessionDescription);
                        remotePeerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                    }
                }, sdpMediaConstraints);
            }
        }, sdpMediaConstraints);
    }

    private PeerConnection createPeerConnection(PeerConnectionFactory factory, boolean isLocal) {
        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(new ArrayList<>());
        MediaConstraints pcConstraints = new MediaConstraints();

        PeerConnection.Observer pcObserver = new PeerConnection.Observer() {
            @Override
            public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                Log.d(TAG, "onSignalingChange: ");
            }

            @Override
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                Log.d(TAG, "onIceConnectionChange: ");
            }

            @Override
            public void onIceConnectionReceivingChange(boolean b) {
                Log.d(TAG, "onIceConnectionReceivingChange: ");
            }

            @Override
            public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: ");
            }

            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                Log.d(TAG, "onIceCandidate: " + isLocal);
                if (isLocal) {
                    remotePeerConnection.addIceCandidate(iceCandidate);
                } else {
                    localPeerConnection.addIceCandidate(iceCandidate);
                }
            }

            @Override
            public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {
                Log.d(TAG, "onIceCandidatesRemoved: ");
            }

            @Override
            public void onAddStream(MediaStream mediaStream) {
                Log.d(TAG, "onAddStream: " + mediaStream.videoTracks.size());
                VideoTrack remoteVideoTrack = mediaStream.videoTracks.get(0);
                remoteVideoTrack.setEnabled(true);
                remoteVideoTrack.addRenderer(new VideoRenderer(binding.surfaceView2));

            }

            @Override
            public void onRemoveStream(MediaStream mediaStream) {
                Log.d(TAG, "onRemoveStream: ");
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: ");
            }

            @Override
            public void onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded: ");
            }
        };

        return factory.createPeerConnection(rtcConfig, pcConstraints, pcObserver);
    }

    private VideoCapturer createVideoCapturer() {
        VideoCapturer videoCapturer;
        if (useCamera2()) {
            videoCapturer = createCameraCapturer(new Camera2Enumerator(this));
        } else {
            videoCapturer = createCameraCapturer(new Camera1Enumerator(true));
        }
        return videoCapturer;
    }

    private VideoCapturer createCameraCapturer(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    /*
    * Read more about Camera2 here
    * https://developer.android.com/reference/android/hardware/camera2/package-summary.html
    * */
    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this);
    }

}
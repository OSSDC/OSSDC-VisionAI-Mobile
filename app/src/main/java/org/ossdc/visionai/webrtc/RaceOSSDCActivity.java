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

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.databinding.DataBindingUtil;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.ossdc.visionai.R;
import org.ossdc.visionai.databinding.ActivityPeerConnectionBinding;
import org.ossdc.visionai.usb.UsbService;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.webrtc.AudioSource;
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

import java.lang.ref.WeakReference;
import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Set;

import io.socket.client.IO;
import io.socket.client.Socket;
import pub.devrel.easypermissions.AfterPermissionGranted;
import pub.devrel.easypermissions.EasyPermissions;

import static org.ossdc.visionai.webrtc.MediaStreamActivity.FPS;
import static org.ossdc.visionai.webrtc.MediaStreamActivity.VIDEO_RESOLUTION_HEIGHT;
import static org.ossdc.visionai.webrtc.MediaStreamActivity.VIDEO_RESOLUTION_WIDTH;
import static org.ossdc.visionai.webrtc.MediaStreamActivity.VIDEO_TRACK_ID;
import static org.ossdc.visionai.webrtc.MediaStreamActivity.AUDIO_TRACK_ID;

import static io.socket.client.Socket.EVENT_CONNECT;
import static io.socket.client.Socket.EVENT_DISCONNECT;
import static org.webrtc.SessionDescription.Type.ANSWER;
import static org.webrtc.SessionDescription.Type.OFFER;

public class RaceOSSDCActivity extends AppCompatActivity {
    private static final String TAG = "OSSDCVisionAI";

    private static final int RC_CALL = 111;

    private Socket socket;
    private boolean isInitiator;
    private boolean isChannelReady;
    private boolean isStarted;
//    private boolean isOfferer;

    private ActivityPeerConnectionBinding binding;
    private PeerConnection peerConnection;
    private EglBase rootEglBase;
    private PeerConnectionFactory factory;
    private VideoTrack videoTrackFromCamera;

    private String roomName;
    private String roomPassword;
    private String droneRoomName;

    boolean sendTestModeOn = false;
    boolean sendTestModeOff = false;

    boolean useBackCamera = false;

    String robotMode = "OpenBot";
    private VideoCapturer videoCapturer = null;

    /*
     * This handler will be passed to UsbService. Data received from serial port is displayed through this handler
     */
    private static class MyHandler extends Handler {
        private final WeakReference<RaceOSSDCActivity> mActivity;

        public MyHandler(RaceOSSDCActivity activity) {
            mActivity = new WeakReference<>(activity);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case UsbService.MESSAGE_FROM_SERIAL_PORT:
                    String data = (String) msg.obj;
                    Log.d(TAG, "USB-Receive: "+data);
                    mActivity.get().display.append(data);
                    break;
                case UsbService.CTS_CHANGE:
                    Toast.makeText(mActivity.get(), "CTS_CHANGE",Toast.LENGTH_LONG).show();
                    break;
                case UsbService.DSR_CHANGE:
                    Toast.makeText(mActivity.get(), "DSR_CHANGE",Toast.LENGTH_LONG).show();
                    break;
                case UsbService.SYNC_READ:
                    String buffer = (String) msg.obj;
                    Log.d(TAG, "USB-Receive: "+buffer);
                    mActivity.get().display.append(buffer);
                    break;
            }
        }
    }

    /*
     * Notifications from UsbService will be received here.
     */
    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            switch (intent.getAction()) {
                case UsbService.ACTION_USB_PERMISSION:
                    synchronized (this) {
                        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);

                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            if(device != null){
                                if(usbService!=null)
                                    usbService.changeBaudRate(115200);
                            }
                        }
                        else {
                            Log.d("mUsbReceiver", "permission denied for device " + device);
                        }
                    }
                case UsbService.ACTION_USB_PERMISSION_GRANTED: // USB PERMISSION GRANTED
                    Toast.makeText(context, "USB Ready", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_PERMISSION_NOT_GRANTED: // USB PERMISSION NOT GRANTED
                    Toast.makeText(context, "USB Permission not granted", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_NO_USB: // NO USB CONNECTED
                    Toast.makeText(context, "No USB connected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_DISCONNECTED: // USB DISCONNECTED
                    Toast.makeText(context, "USB disconnected", Toast.LENGTH_SHORT).show();
                    break;
                case UsbService.ACTION_USB_NOT_SUPPORTED: // USB NOT SUPPORTED
                    Toast.makeText(context, "USB device not supported", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    };

    private UsbService usbService;
    private TextView display;
    private MyHandler mHandler;
    private final ServiceConnection usbConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName arg0, IBinder arg1) {
            usbService = ((UsbService.UsbBinder) arg1).getService();
            usbService.setHandler(mHandler);
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            usbService = null;
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        binding = DataBindingUtil.setContentView(this, R.layout.activity_peer_connection);
        setSupportActionBar(binding.toolbar);

        // Get Intent parameters.
        final Intent intent = getIntent();
        roomName = intent.getStringExtra("roomName");
        roomPassword = intent.getStringExtra("roomPassword");
        useBackCamera = intent.getBooleanExtra("useBackCamera",false);
        robotMode = intent.getStringExtra("robotMode");

        SurfaceView surfaceView = findViewById(R.id.surface_view);
        TextView fromCamera = findViewById(R.id.fromCamera);

        boolean hideLocalCamera = intent.getBooleanExtra("hideLocalCamera",false);

        if(hideLocalCamera) {
            surfaceView.setVisibility(View.GONE);
            fromCamera.setVisibility(View.GONE);
            FrameLayout peerCameraFrame = findViewById(R.id.peerCameraFrame);
            peerCameraFrame.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
        }
        else {
            surfaceView.setVisibility(View.VISIBLE);
            fromCamera.setVisibility(View.VISIBLE);
        }

        display = findViewById(R.id.displayArea);

        boolean useDisplayArea = intent.getBooleanExtra("useDisplayArea",false);
        if(useDisplayArea)
            display.setVisibility(View.VISIBLE);
        else
            display.setVisibility(View.INVISIBLE);

        mHandler = new MyHandler(this);

        start();
    }

    protected synchronized void runInBackground(final Runnable r) {
        if (mHandler != null) {
            mHandler.post(r);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        setFilters();  // Start listening notifications from UsbService
        startService(UsbService.class, usbConnection, null); // Start UsbService(if it was not started before) and Bind it
    }

    @Override
    public void onPause() {
        super.onPause();
        unregisterReceiver(mUsbReceiver);
        unbindService(usbConnection);
    }

    private void startService(Class<?> service, ServiceConnection serviceConnection, Bundle extras) {
        if (!UsbService.SERVICE_CONNECTED) {
            Intent startService = new Intent(this, service);
            if (extras != null && !extras.isEmpty()) {
                Set<String> keys = extras.keySet();
                for (String key : keys) {
                    String extra = extras.getString(key);
                    startService.putExtra(key, extra);
                }
            }
            startService(startService);
        }
        Intent bindingIntent = new Intent(this, service);
        bindService(bindingIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void setFilters() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbService.ACTION_USB_PERMISSION_GRANTED);
        filter.addAction(UsbService.ACTION_NO_USB);
        filter.addAction(UsbService.ACTION_USB_DISCONNECTED);
        filter.addAction(UsbService.ACTION_USB_NOT_SUPPORTED);
        filter.addAction(UsbService.ACTION_USB_PERMISSION_NOT_GRANTED);
        registerReceiver(mUsbReceiver, filter);
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    @Override
    protected void onDestroy() {

        if (socket != null) {
            socket.send("BYE");
            socket.disconnect();
            socket.close();
        }

        if(peerConnection!=null) {
            peerConnection.close();
            peerConnection=null;
        }

        if(videoCapturer!=null) {
            try {
                videoCapturer.stopCapture();
                videoCapturer = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        isChannelReady = false;
        isStarted = false;
        super.onDestroy();
    }

    @AfterPermissionGranted(RC_CALL)
    private void start() {
        String[] perms = {Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
        if (EasyPermissions.hasPermissions(this, perms)) {
            connectToSignallingServer();

            initializeSurfaceViews();

            initializePeerConnectionFactory();

            createVideoTrackFromCameraAndShowIt();

            initializePeerConnections();

            startStreamingVideo();
        } else {
            EasyPermissions.requestPermissions(this, "Need some permissions", RC_CALL, perms);
        }
    }

    private void startWebRTC()
    {
//        isInitiator = true;
        isChannelReady = true;
        maybeStart();
    }


    public String byteToHex(byte num) {
        char[] hexDigits = new char[2];
        hexDigits[0] = Character.forDigit((num >> 4) & 0xF, 16);
        hexDigits[1] = Character.forDigit((num & 0xF), 16);
        return new String(hexDigits);
    }

    public String md5(String s) {
        try {
            // Create MD5 Hash
            MessageDigest digest = java.security.MessageDigest.getInstance("MD5");
            digest.update(s.getBytes());
            byte[] messageDigest = digest.digest();


            // Create Hex String
            StringBuffer hexString = new StringBuffer();
            for (int i=0; i<messageDigest.length; i++)
                hexString.append(byteToHex(messageDigest[i]));
            return hexString.toString();
        }catch (NoSuchAlgorithmException e) {
            e.printStackTrace();
        }
        return "";
    }

    boolean left_reversed = false;
    boolean right_reversed = true;
    double deadband = 0.3;
    int maxMotorVal = 150;

    private double applyDeadband(double input) {
        if(input > 1.0) return 1.0;
        if(input < -1.0) return -1.0;
        double sign = input > 0 ? 1 : -1;
        double value = Math.abs(input);

        return sign * (deadband + (1.0 - deadband) * value);
    }


    private String convertStickCommand(JSONArray stickValues) {

        String command = null;
        try {
            double linear = -stickValues.getDouble(1);
            double angular = -0.7 * stickValues.getDouble(0);
//            console.log("linear", linear, "angular", angular);

            double motor_left = (linear - angular);
            double motor_right = (linear + angular);
            int speed = (int) Math.abs(Math.floor(linear * 100));

            motor_left = Math.abs(Math.floor(maxMotorVal*motor_left));
            motor_right = Math.abs(Math.floor(maxMotorVal*motor_right));

            if(motor_left<1) motor_left=1;
            if(motor_right<1) motor_right=1;

            if(linear<0)
            {
                motor_left=-motor_left;
                motor_right=-motor_right;
            }

            command = getSetMotorCommand( (int)motor_left, (int)motor_right, speed);
        } catch (JSONException e) {
            e.printStackTrace();
        }
        return  command;
    }

    private String getSetMotorCommand(int motor_left,int motor_right,int speed){

        if(motor_left > maxMotorVal) motor_left = maxMotorVal;
        if(motor_left < -maxMotorVal) motor_left = -maxMotorVal;
        if(motor_right > maxMotorVal) motor_right = maxMotorVal;
        if(motor_right < -maxMotorVal) motor_right = -maxMotorVal;

        if(speed<1)
            speed=1;

        String command = null;

        if("OpenBot".equals(robotMode))
            command = "c"+ motor_left +","+ motor_right +"\n";
        else
            command = "setmotor "+ motor_left +" "+ motor_right +" "+speed+"\n"; //+"\nGetLDSScan\n"

        return command;
    }

    private void connectToSignallingServer() {
        try {
            socket = IO.socket("https://race.ossdc.org/");

            droneRoomName =  md5(roomName + "-" + roomPassword);

            sendTestModeOn=true;
            sendTestModeOff=true;
            socket.on(EVENT_CONNECT, args -> {
                Log.d(TAG, "connectToSignallingServer: connect to droneRoomName: "+droneRoomName);
                socket.emit("subscribe", droneRoomName);

            }).on(droneRoomName + ".message", args2 -> {
                try {

                    String sender = (String)((JSONObject)args2[0]).get("sender");

                    if(sender.equals(socket.id()))
                        return;

                    JSONObject message = (JSONObject)((JSONObject)args2[0]).get("message");
                    Log.d(TAG, "connectToSignallingServer: sender: "+sender+", message: " + message);

                    if(message.has("sdp")) {
                        message = (JSONObject) message.get("sdp");
                    }

                    if (message.has("type") && message.getString("type").equals("offer")) {
                            Log.d(TAG, "connectToSignallingServer: received an offer " + isInitiator + " " + isStarted);
                            if (!isInitiator && !isStarted) {
                                maybeStart();
                            }
                            peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(OFFER, message.getString("sdp")));
                            doAnswer();
                    } else if (message.has("type") && message.getString("type").equals("answer") && isStarted) {
                        peerConnection.setRemoteDescription(new SimpleSdpObserver(), new SessionDescription(ANSWER, message.getString("sdp")));
                    } else if (message.has("candidate") && isStarted) {
                        Log.d(TAG, "connectToSignallingServer: receiving candidates");
                        IceCandidate candidate = null;
                        if(message.has("id"))
                            candidate = new IceCandidate(message.getString("id"), message.getInt("label"), message.getString("candidate"));
                        else {
                            message = (JSONObject)message.get("candidate");
                            candidate = new IceCandidate(message.getString("sdpMid"), message.getInt("sdpMLineIndex"), message.getString("candidate"));
                        }
                        peerConnection.addIceCandidate(candidate);
                    }else {
                        Log.d(TAG, "msg: "+message.toString());

                        //{"stick":[0.050187265917603,-0.7715355805243446,1605574844705]}
                        if(message.has("stick"))
                        {
                            try {
                                JSONArray stick = (JSONArray) message.get("stick");
                                if (usbService != null) { // if UsbService was correctly binded, Send data
                                    String data = "testmode on\n";
                                    if(sendTestModeOn) {
                                        sendTestModeOn = false;
                                        Log.d(TAG, "USB-Send: " + data);
                                        usbService.write(data.getBytes());//send raw
                                    }

                                    data = convertStickCommand(stick);
                                    Log.d(TAG, "USB-Send: "+data);
                                    if(data!=null)
                                        usbService.write(data.getBytes());//send raw
                                }
                            } catch (JSONException e1) {
                                e1.printStackTrace();
                            }
                        }
                        else
                        //{"setmotor":[10,10,50,1605574844705]}
                        if(message.has("setmotor"))
                        {
                            try {
                                JSONArray setmotor = (JSONArray) message.get("setmotor");
                                if (usbService != null) { // if UsbService was correctly binded, Send data
                                    String data = "testmode on\n";
                                    if(sendTestModeOn) {
                                        sendTestModeOn = false;
                                        Log.d(TAG, "USB-Send: " + data);
                                        if(!"OpenBot".equals(robotMode))
                                            usbService.write(data.getBytes());//send raw
                                    }

                                    int motorLeft = setmotor.getInt(0);
                                    int motorRight = setmotor.getInt(1);
                                    int speed = setmotor.getInt(2);
                                    data = getSetMotorCommand(motorLeft,motorRight,speed);

                                    Log.d(TAG, "USB-Send: "+data);
                                    if(data!=null)
                                        usbService.write(data.getBytes());//send raw
                                }
                            } catch (JSONException e1) {
                                e1.printStackTrace();
                            }
                        }
                        else
                            Log.d(TAG, "connectToSignallingServer: not processed");
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).on(droneRoomName + ".members", args1 -> {
                try {
                    JSONArray members = (JSONArray)args1[0];
                        isInitiator = members.length() == 2;
                        startWebRTC();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).on(EVENT_DISCONNECT, args -> {
                Log.d(TAG, "connectToSignallingServer: disconnect");
                isChannelReady = false;
                isStarted = false;
                if(usbService!=null)
                {
                    String data = "testmode off\n";
                    if(sendTestModeOff) {
                        sendTestModeOn = true;
                        sendTestModeOff = false;
                        Log.d(TAG, "USB-Send: " + data);
                        usbService.write(data.getBytes());//send raw
                    }
                }
            });
            socket.connect();
        } catch (URISyntaxException e) {
            e.printStackTrace();
        }
    }

    private void doAnswer() {
//        MediaConstraints videoConstraints = new MediaConstraints();
//        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxHeight", Integer.toString(pcParams.videoHeight)));
//        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxWidth", Integer.toString(pcParams.videoWidth)));
//        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("maxFrameRate", Integer.toString(pcParams.videoFps)));
//        videoConstraints.mandatory.add(new MediaConstraints.KeyValuePair("minFrameRate", Integer.toString(pcParams.videoFps))

        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                JSONObject message = new JSONObject();
                try {
                    JSONObject sdp = new JSONObject();

                    sdp.put("type", "answer");
                    sdp.put("sdp", sessionDescription.description);

                    message.put("sdp",sdp);
                    sendMessagePublish(message);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, new MediaConstraints());
    }

    private void maybeStart() {
        Log.d(TAG, "maybeStart: " + isStarted + " " + isChannelReady);
        if (!isStarted && isChannelReady) {
            isStarted = true;
            if (isInitiator) {
                doCall();
            }
        }
    }

    private void doCall() {
        MediaConstraints sdpMediaConstraints = new MediaConstraints();

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription sessionDescription) {
                Log.d(TAG, "onCreateSuccess: ");
                peerConnection.setLocalDescription(new SimpleSdpObserver(), sessionDescription);
                JSONObject message = new JSONObject();
                try {
                    JSONObject sdp = new JSONObject();

                    sdp.put("type", "offer");
                    sdp.put("sdp", sessionDescription.description);

                    message.put("sdp",sdp);
                    sendMessagePublish(message);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }, sdpMediaConstraints);
    }

    private void sendMessagePublish(JSONObject val) {
        try {

            JSONObject mess = new JSONObject();
            JSONObject room = new JSONObject();
            mess.put("roomName",droneRoomName);
            JSONObject message = new JSONObject();
            mess.put("message",val);
            Log.d(TAG, "sendMessagePublish: "+mess);
            socket.emit("publish", mess);

        }catch (JSONException e)
        {
            e.printStackTrace();
        }
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
        videoCapturer = createVideoCapturer();
        if(videoCapturer ==null)
            return;
        VideoSource videoSource = factory.createVideoSource(videoCapturer);
        videoCapturer.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS);

        videoTrackFromCamera = factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
        videoTrackFromCamera.setEnabled(true);
        videoTrackFromCamera.addRenderer(new VideoRenderer(binding.surfaceView));
    }

    private void initializePeerConnections() {
        peerConnection = createPeerConnection(factory);
    }

    private void startStreamingVideo() {
        if(videoTrackFromCamera==null)
            return;

        MediaStream mediaStream = factory.createLocalMediaStream("ARDAMS");
        mediaStream.addTrack(videoTrackFromCamera);
        peerConnection.addStream(mediaStream);

        mediaStream.addTrack(videoTrackFromCamera);

        AudioSource audioSource = factory.createAudioSource(new MediaConstraints());
        mediaStream.addTrack(factory.createAudioTrack(AUDIO_TRACK_ID, audioSource));
    }

    private PeerConnection createPeerConnection(PeerConnectionFactory factory) {
        ArrayList<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(new PeerConnection.IceServer("stun:race.ossdc.org:5349"));
        iceServers.add(new PeerConnection.IceServer("turn:race.ossdc.org:5349", "testturn",roomName));

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);
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
                Log.d(TAG, "onIceCandidate: ");
                JSONObject messageRoot = new JSONObject();
                JSONObject message = new JSONObject();

                try {
                    message.put("sdpMLineIndex", iceCandidate.sdpMLineIndex);
                    message.put("sdpMid", iceCandidate.sdpMid);
                    message.put("candidate", iceCandidate.sdp);

                    messageRoot.put("candidate",message);
                    Log.d(TAG, "onIceCandidate: sending candidate " + messageRoot);
                    sendMessagePublish(messageRoot);
                } catch (JSONException e) {
                    e.printStackTrace();
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

        for (String deviceName : deviceNames) {
            if(useBackCamera) {
                if (enumerator.isBackFacing(deviceName)) {
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        return videoCapturer;
                    }
                }
            }
            else {
                if (enumerator.isFrontFacing(deviceName)) {
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        return videoCapturer;
                    }
                }
            }
        }

        for (String deviceName : deviceNames) {
            if(useBackCamera) {
                if (!enumerator.isBackFacing(deviceName)) {
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        return videoCapturer;
                    }
                }
            }
            else {
                if (!enumerator.isFrontFacing(deviceName)) {
                    VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                    if (videoCapturer != null) {
                        return videoCapturer;
                    }
                }
            }
        }

        return null;
    }

    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(this);
    }

}

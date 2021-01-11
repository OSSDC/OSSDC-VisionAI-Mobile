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
package org.ossdc.visionai.usb;

import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.felhr.usbserial.CDCSerialDevice;
import com.felhr.usbserial.SerialInputStream;
import com.felhr.usbserial.SerialOutputStream;
import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import io.socket.client.Socket;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

public class UsbService extends Service {

    public static final String TAG = "VisionAIUsbService";
    
    public static final String ACTION_USB_READY = "com.felhr.connectivityservices.USB_READY";
    public static final String ACTION_USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED";
    public static final String ACTION_USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED";
    public static final String ACTION_USB_NOT_SUPPORTED = "com.felhr.usbservice.USB_NOT_SUPPORTED";
    public static final String ACTION_NO_USB = "com.felhr.usbservice.NO_USB";
    public static final String ACTION_USB_PERMISSION_GRANTED = "com.felhr.usbservice.USB_PERMISSION_GRANTED";
    public static final String ACTION_USB_PERMISSION_NOT_GRANTED = "com.felhr.usbservice.USB_PERMISSION_NOT_GRANTED";
    public static final String ACTION_USB_DISCONNECTED = "com.felhr.usbservice.USB_DISCONNECTED";
    public static final String ACTION_CDC_DRIVER_NOT_WORKING = "com.felhr.connectivityservices.ACTION_CDC_DRIVER_NOT_WORKING";
    public static final String ACTION_USB_DEVICE_NOT_WORKING = "com.felhr.connectivityservices.ACTION_USB_DEVICE_NOT_WORKING";
    public static final int MESSAGE_FROM_SERIAL_PORT = 0;
    public static final int CTS_CHANGE = 1;
    public static final int DSR_CHANGE = 2;
    public static final int SYNC_READ = 3;
    public static final String ACTION_USB_PERMISSION = "com.android.example.USB_PERMISSION";
    private static final int BAUD_RATE = 115200; // BaudRate. Change this value if you need
    public static boolean SERVICE_CONNECTED = false;

    private final IBinder binder = new UsbBinder();

    private Context context;
    private Handler mHandler;
    private UsbManager usbManager;
    private UsbDevice device;
    private UsbDeviceConnection connection;
    private UsbSerialDevice serialPort;

    private boolean serialPortConnected;

    private SerialInputStream serialInputStream;
    private SerialOutputStream serialOutputStream;

    private ReadThread readThread;

    private Socket currentConn = null;

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context arg0, Intent arg1) {
            if (arg1.getAction().equals(ACTION_USB_PERMISSION)) {
                boolean granted = arg1.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if (granted) // User accepted our USB connection. Try to open the device as a serial port
                {
                    try {
                        Intent intent = new Intent(ACTION_USB_PERMISSION_GRANTED);
                        arg0.sendBroadcast(intent);
                        connection = usbManager.openDevice(device);
                        new ConnectionThread().start();
                        String usbDeviceFound = String.format("\nConnect to USBDevice (vid:pid) (%X:%X)-%b class:%X:%X name:%s\n",
                                device.getVendorId(), device.getProductId(),
                                UsbSerialDevice.isSupported(device),
                                device.getDeviceClass(), device.getDeviceSubclass(),
                                device.getDeviceName());
                        Log.d(TAG, usbDeviceFound);
                        if(mHandler!=null)
                            mHandler.obtainMessage(SYNC_READ, usbDeviceFound).sendToTarget();
                    }catch (Exception e)
                    {
                        e.printStackTrace();
                    }

                } else // User not accepted our USB connection. Send an Intent to the Main Activity
                {
                    Intent intent = new Intent(ACTION_USB_PERMISSION_NOT_GRANTED);
                    arg0.sendBroadcast(intent);
                }
            } else if (arg1.getAction().equals(ACTION_USB_ATTACHED)) {
                if (!serialPortConnected)
                    findSerialPortDevice(); // A USB device has been attached. Try to open it as a Serial port
            } else if (arg1.getAction().equals(ACTION_USB_DETACHED)) {
                // Usb device was disconnected. send an intent to the Main Activity
                Intent intent = new Intent(ACTION_USB_DISCONNECTED);
                arg0.sendBroadcast(intent);
                if (serialPortConnected) {
                    serialPort.syncClose();
                    readThread.setKeep(false);
                }
                serialPortConnected = false;
            }
        }
    };

    @Override
    public void onCreate() {
        this.context = this;
        serialPortConnected = false;
        UsbService.SERVICE_CONNECTED = true;
        setFilter();
        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        findSerialPortDevice();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        serialPort.close();
        unregisterReceiver(usbReceiver);
        UsbService.SERVICE_CONNECTED = false;
    }

    public static String byteArrayToHex(byte[] a) {
        StringBuilder sb = new StringBuilder(a.length * 2);
        for(byte b: a)
            if(b<127)
                sb.append((char)b);
            else
                sb.append(String.format("_%02x_", b));
        return sb.toString();
    }

    public void write(byte[] data) {
        mHandler.obtainMessage(SYNC_READ, "\nCMD: "+byteArrayToHex(data)).sendToTarget();
        if (serialOutputStream != null)
            serialOutputStream.write(data);
    }

    public void changeBaudRate(int baudRate){
        if(serialPort != null)
            serialPort.setBaudRate(baudRate);
    }

    public void setHandler(Handler mHandler) {
        this.mHandler = mHandler;
    }

    private void findSerialPortDevice() {
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        if (!usbDevices.isEmpty()) {
            
            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                try {
                    device = entry.getValue();
                    String usbDeviceFound = String.format("USBDevice.HashMap (vid:pid) (%X:%X)-%b class:%X:%X name:%s",
                            device.getVendorId(), device.getProductId(),
                            UsbSerialDevice.isSupported(device),
                            device.getDeviceClass(), device.getDeviceSubclass(),
                            device.getDeviceName());
                    Log.d(TAG, usbDeviceFound);
                    if(mHandler!=null)
                        mHandler.obtainMessage(SYNC_READ, usbDeviceFound).sendToTarget();
                }catch (Exception e)
                {
                    e.printStackTrace();
                }
            }

            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                int deviceVID = device.getVendorId();
                int devicePID = device.getProductId();

                if (UsbSerialDevice.isSupported(device)) {
                    requestUserPermission();
                    break;
                } else {
                    connection = null;
                    device = null;
                }
            }
            if (device==null) {
                Intent intent = new Intent(ACTION_NO_USB);
                sendBroadcast(intent);
            }
        } else {
            String message = "findSerialPortDevice() usbManager returned empty device list.";
            Log.d(TAG, message);
            Intent intent = new Intent(ACTION_NO_USB);
            sendBroadcast(intent);
            try {
                if(mHandler!=null)
                    mHandler.obtainMessage(SYNC_READ, message).sendToTarget();
            }catch (Exception e)
            {
                e.printStackTrace();
            }
        }
    }

    private void setFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_USB_PERMISSION);
        filter.addAction(ACTION_USB_DETACHED);
        filter.addAction(ACTION_USB_ATTACHED);
        registerReceiver(usbReceiver, filter);
    }

    private void requestUserPermission() {
        Log.d(TAG, String.format("requestUserPermission(%X:%X)", device.getVendorId(), device.getProductId() ) );
        PendingIntent mPendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(device, mPendingIntent);
    }

    public class UsbBinder extends Binder {
        public UsbService getService() {
            return UsbService.this;
        }
    }

    private class ConnectionThread extends Thread {
        public ConnectionThread() {
            super();
        }

        @Override
        public void run() {
            serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
            if (serialPort != null) {
                if (serialPort.syncOpen()) {
                    serialPortConnected = true;
                    serialPort.setBaudRate(BAUD_RATE);
                    serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                    serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                    serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                    serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);

                    serialInputStream = serialPort.getInputStream();
                    serialOutputStream = serialPort.getOutputStream();

                    readThread = new ReadThread();
                    readThread.start();

                    Intent intent = new Intent(ACTION_USB_READY);
                    context.sendBroadcast(intent);
                    String usbDeviceFound = String.format("Ready USBDevice (vid:pid) (%X:%X)-%b class:%X:%X name:%s\n",
                            device.getVendorId(), device.getProductId(),
                            UsbSerialDevice.isSupported(device),
                            device.getDeviceClass(), device.getDeviceSubclass(),
                            device.getDeviceName());
                    Log.d(TAG, usbDeviceFound);
                    try {
                        mHandler.obtainMessage(SYNC_READ, usbDeviceFound).sendToTarget();
                    }catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                } else {
                    if (serialPort instanceof CDCSerialDevice) {
                        Intent intent = new Intent(ACTION_CDC_DRIVER_NOT_WORKING);
                        context.sendBroadcast(intent);
                    } else {
                        Intent intent = new Intent(ACTION_USB_DEVICE_NOT_WORKING);
                        context.sendBroadcast(intent);
                    }
                }
            } else {
                Intent intent = new Intent(ACTION_USB_NOT_SUPPORTED);
                context.sendBroadcast(intent);
            }
        }
    }

    private static String toASCII(int value) {
        int length = 4;
        StringBuilder builder = new StringBuilder(length);
        for (int i = length - 1; i >= 0; i--) {
            builder.append((char) ((value >> (8 * i)) & 0xFF));
        }
        return builder.toString();
    }

    private class ReadThread extends Thread {
        private final AtomicBoolean keep = new AtomicBoolean(true);
        @Override
        public void run() {
            StringBuffer sb = new StringBuffer();
            while(keep.get()){
                if(serialInputStream == null)
                    return;
                int value = serialInputStream.read();
                if(value != -1) {
                    char str = (char)value;
                    sb.append(str);
                    if(str=='\n')
                    {
                        String data = sb.toString();
                        mHandler.obtainMessage(SYNC_READ,data).sendToTarget();
                        if(currentConn!=null)
                            currentConn.send(data);
                        sb = new StringBuffer();
                    }
                }
            }
            if(sb.length()>0) {
                String data = sb.toString();
                mHandler.obtainMessage(SYNC_READ,data ).sendToTarget();
                if(currentConn!=null)
                    currentConn.send(data);
            }
        }

        public void setKeep(boolean keep){
            this.keep.set(keep);
        }
    }

    public void setWSConnection(Socket conn){
        currentConn = conn;
    }
}

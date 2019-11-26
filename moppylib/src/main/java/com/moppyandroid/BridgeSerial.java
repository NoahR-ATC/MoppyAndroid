/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.moppyandroid;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Intent;
import android.os.Environment;
import android.os.Parcel;
import android.os.Parcelable;

import com.felhr.usbserial.UsbSerialDevice;

import com.moppyandroid.com.moppy.core.comms.MoppyMessage;
import com.moppyandroid.com.moppy.core.comms.MoppyMessageFactory;
import com.moppyandroid.com.moppy.core.comms.NetworkMessageConsumer;
import com.moppyandroid.com.moppy.core.comms.bridge.NetworkBridge;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A Serial connection for Moppy devices.
 */
public class BridgeSerial extends NetworkBridge {
    private static Context context;
    private static UsbManager usbManager;
    private final UsbDevice device;
    private UsbSerialDevice serialPort;
    private Thread listenerThread = null;

    // Define the method to assign static variables
    public static void init(Context passedContext) {
        // Assign static variables
        context = passedContext;
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        {
            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("Class Initialized - BridgeSerial");
            b.setPositiveButton("OK", null);
            b.create().show();
        }
    }

    public BridgeSerial(String serialPortName) {
        device = usbManager.getDeviceList().get(serialPortName);
        {
            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("Device created - BridgeSerial");
            b.setPositiveButton("OK", null);
            b.create().show();
        }
    }

    public static List<String> getAvailableSerials() {
        List<String> serialList = new ArrayList<>();
        serialList.addAll(usbManager.getDeviceList().keySet());
        return serialList;
    }

    public static void onActionUsbPermission(Context context, @org.jetbrains.annotations.NotNull Intent intent) {
        {
            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("ACTION_USB_PERMISSION - BridgeSerial");
            b.setPositiveButton("OK", null);
            b.create().show();
        }
        boolean permissionGranted; // Boolean for whether or not permission to access device was granted

        // Check if any extra information was sent
        if (intent.getExtras() != null) {

            // Attempt to retrieve the PermissionIntentValues instance, checking if it is the correct type
            if (intent.getExtras().get("syncObject") instanceof ParcelableObject) {
                // Save the PermissionIntentValues instance as well as whether or not permission was granted
                ParcelableObject syncObject = (ParcelableObject) intent.getExtras().get("syncObject");
                // Check if permissionIntentValues and permissionIntentValues.device are valid
                if (syncObject != null) {
                    // Notify the connect() method that the message processing is complete
                    syncObject.notify();
                } // End if(permissionIntentValues && device)
            } // End if(permissionIntentValues ∈ PermissionIntentValues)
        } // End if(intent.getExtras)
    } // End onActionUsbPermission method

    @Override
    public void connect() throws IOException {
        {
            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("Broadcast thing acquired- BridgeSerial");
            b.setPositiveButton("OK", null);
            b.create().show();
        }

        if (!usbManager.hasPermission(device)) {
            throw new IOException("permission not granted");
        }

        serialPort = UsbSerialDevice.createUsbSerialDevice(device, usbManager.openDevice(device));
        // Ensure serial connection was created successfully
        if (serialPort != null) {
            // Attempt to open the connection, assigning options if successful
            if (serialPort.syncOpen()) {
                serialPort.setPortName(device.getDeviceName());
                serialPort.setBaudRate(57600);
                serialPort.setDataBits(UsbSerialDevice.DATA_BITS_8);
                serialPort.setStopBits(UsbSerialDevice.STOP_BITS_1);
                serialPort.setFlowControl(UsbSerialDevice.FLOW_CONTROL_OFF);
                serialPort.setParity(UsbSerialDevice.PARITY_NONE);
            } // End if(serialPort.syncOpen)
            else {
                throw new IOException("serialPort did not open");
            } // End if(serialPort.syncOpen) else
        } // End if(serialPort !null)
        else {
            throw new IOException("serialPort is null");
        } // End if(serialPort !null) else

        // ****DEPRECATED****
        // Set to semiblocking mode (will wait for up to 100 milliseconds before returning nothing from a read)
        // On a very slow connection this could maaaybe cause some messages to be dropped / corrupted, but for
        // serial connections it should be a minor risk
        //serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
        // ******************
        // When replacing jSerialComm with usbserial, the new library did not support this "semiblocking" mode
        // After analyzing the dependant code I made the decision to switch to synchronous operation

        {
            AlertDialog.Builder b = new AlertDialog.Builder(context);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("Starting listener - BridgeSerial");
            b.setPositiveButton("OK", null);
            b.create().show();
        }

        // Create and start listener thread
        SerialListener listener = new SerialListener(serialPort, this);
        listenerThread = new Thread(listener);
        listenerThread.start();
    }

    @Override
    public void sendMessage(MoppyMessage messageToSend) throws IOException {
        if (serialPort.isOpen()) {
            serialPort.syncWrite(messageToSend.getMessageBytes(), 0, messageToSend.getMessageBytes().length, 0);
        }
    }

    @Override
    public void close() throws IOException {
        try {
            sendMessage(MoppyMessage.SYS_STOP); // Send a stop message before closing to prevent sticking
        } finally {
            serialPort.syncClose();
            // Stop and cleanup listener thread
            listenerThread.interrupt();
            listenerThread = null;
        }
    }

    @Override
    public String getNetworkIdentifier() {
        return serialPort.getPortName();
    }

    @Override
    public boolean isConnected() {
        return serialPort.isOpen();
    }

    /**
     * Listens to the serial port for MoppyMessages.  Because *all* this
     * thread does is listen for messages, it's fine to block on serial.read().
     */
    private static class SerialListener implements Runnable {

        private final UsbSerialDevice serialPort;
        private final NetworkMessageConsumer messageConsumer;

        public SerialListener(UsbSerialDevice serialPort, NetworkMessageConsumer messageConsumer) {
            this.serialPort = serialPort;
            this.messageConsumer = messageConsumer;
        }

        @Override
        public void run() {

            // MoppyMessages can't be longer than 259 bytes (SOM, DEVADDR, SUBADDR, LEN, [0-255 body bytes])
            // Longer messages will be truncated, but we don't care about them anyway
            byte[] buffer = new byte[259];
            buffer[0] = MoppyMessage.START_BYTE; // We'll be eating the start byte below, so make sure it's here
            int totalMessageLength;

            try (InputStream serialIn = serialPort.getInputStream()) {
                while (serialPort.isOpen() && !Thread.interrupted()) {
                    // Keep reading until we get a START_BYTE
                    if (serialIn.read() == MoppyMessage.START_BYTE) {
                        buffer[1] = (byte) serialIn.read(); // Get Address
                        buffer[2] = (byte) serialIn.read(); // Get Sub-Address
                        buffer[3] = (byte) serialIn.read(); // Get body size
                        serialIn.read(buffer, 4, buffer[3]); // Read body into buffer
                        totalMessageLength = 4 + buffer[3];

                        try {
                            messageConsumer.acceptNetworkMessage(MoppyMessageFactory.networkReceivedFromBytes(
                                    Arrays.copyOf(buffer, totalMessageLength),
                                    BridgeSerial.class.getName(),
                                    serialPort.getPortName(),
                                    "Serial Device")); // Serial ports don't really have a remote address
                        } catch (IllegalArgumentException ex) {
                            Logger.getLogger(BridgeSerial.class.getName()).log(Level.WARNING, "Exception reading network message", ex);
                        }
                    }
                }
            } catch (IOException ex) {
                Logger.getLogger(BridgeSerial.class.getName()).log(Level.WARNING, null, ex);
            }
        }

    }

    /**
     * A class that can be passed with the permission intent in order to retrieve
     * values necessary to finish creation of the BridgeSerial object.
     */
    public static class ParcelableObject implements Parcelable {
        public ParcelableObject() {
        } // End ParcelableObject method

        ParcelableObject(Parcel parcel) {
        } // End ParcelableObject(Parcel) method

        public static final Parcelable.Creator<ParcelableObject> CREATOR = new Parcelable.Creator<ParcelableObject>() {
            public ParcelableObject createFromParcel(Parcel parcel) {
                return new ParcelableObject(parcel);
            } // End createFromParcel method

            public ParcelableObject[] newArray(int size) {
                return new ParcelableObject[size];
            } // End newArray method
        }; // End new Parcelable.Creator class instance

        @Override
        public int describeContents() {
            return 0;
        } // End describeContents method

        @Override
        public void writeToParcel(Parcel parcel, int flags) {
        } // End writeToParcel method
    } // End BridgeSerial.ParcelableObject class
} // End BridgeSerial class

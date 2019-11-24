/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.moppyandroid;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;
import android.content.Intent;

import com.felhr.usbserial.UsbSerialDevice;

import com.moppyandroid.com.moppy.core.comms.MoppyMessage;
import com.moppyandroid.com.moppy.core.comms.MoppyMessageFactory;
import com.moppyandroid.com.moppy.core.comms.NetworkMessageConsumer;
import com.moppyandroid.com.moppy.core.comms.bridge.NetworkBridge;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/**
 * A Serial connection for Moppy devices.
 */
public class BridgeSerial extends NetworkBridge {

    private static final String ACTION_USB_PERMISSION = "com.moppyandroid.bridgeserial.USB_PERMISSION";

    private static Context context;
    private static UsbManager usbManager;
    private UsbSerialDevice serialPort;
    private final UsbDevice device;
    private Thread listenerThread = null;
    private StringBuilder stringBuilder;
    private final Object syncObject = new Object();

    // Define the receiver to process relevant intent messages
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) throws NullPointerException {
            // Ensure intent action is not null
            if (intent.getAction() != null) {
                // Determine action and process accordingly
                if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                    boolean permissionGranted = false; // Boolean for whether or not permission to access device was granted

                    // If the extra data associated with the intent isn't null, copy whether or not permission was granted
                    if (intent.getExtras() != null) {
                        permissionGranted = intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                    }

                    // If permission was granted, attempt to create the serial connection
                    if (permissionGranted) {
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
                                stringBuilder.append("Good");
                            } // End if(serialPort.syncOpen)
                            else {
                                stringBuilder.append("serialPort did not open");
                            } // End if(serialPort.syncOpen) else
                        } // End if(serialPort !null)
                        else {
                            stringBuilder.append("serialPort is null");
                        } // End if(serialPort !null) else
                    } // End if(permissionGranted)
                    else {
                        stringBuilder.append("permission not granted");
                    } // End if(permissionGranted) else

                    // Notify the connect() method that the message processing is complete
                    syncObject.notify();
                } // End if(ACTION_USB_PERMISSION)
                else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    try {
                        close();
                    } catch (IOException e) { // Ignore exception
                    }
                } // End if(intent !null)
            } // End onReceive method
        }
    }; // End new BroadcastReceiver

        // Define the method to assign static variables
        public static void init(Context passedContext) {
            // Assign static variables
            context = passedContext;
            usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        }

        public BridgeSerial(String serialPortName) {
            device = usbManager.getDeviceList().get(serialPortName);
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(ACTION_USB_PERMISSION);
            intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            context.registerReceiver(broadcastReceiver, intentFilter);
        }

        public static List<String> getAvailableSerials() {
            List<String> serialList = new ArrayList<>();
            serialList.addAll(usbManager.getDeviceList().keySet());
            return serialList;
        }

        @Override
        public void connect() throws IOException {
            PendingIntent intent = PendingIntent.getBroadcast(context, 0, new Intent(ACTION_USB_PERMISSION), 0);
            stringBuilder = new StringBuilder();
            usbManager.requestPermission(device, intent);

            // Block until syncObject.notify is called
            synchronized (syncObject) {
                try {
                    syncObject.wait();
                } catch (InterruptedException e) { // Notify called
                }
            }

            if (stringBuilder.toString().equals("Good")) { // For efficiency, if "Good" other checks need not be done
            } else if (stringBuilder.toString().equals("serialPort did not open")) {
                throw new IOException("serialPort did not open");
            } else if (stringBuilder.toString().equals("serialPort is null")) {
                throw new IOException("serialPort is null");
            } else if (stringBuilder.toString().equals("permission not granted")) {
                throw new IOException("permission not granted");
            }

            // ****DEPRECATED****
            // Set to semiblocking mode (will wait for up to 100 milliseconds before returning nothing from a read)
            // On a very slow connection this could maaaybe cause some messages to be dropped / corrupted, but for
            // serial connections it should be a minor risk
            //serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);
            // ******************
            // When replacing jSerialComm with usbserial, the new library did not support this "semiblocking" mode
            // After analyzing the dependant code I made the decision to switch to synchronous operation

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
    }

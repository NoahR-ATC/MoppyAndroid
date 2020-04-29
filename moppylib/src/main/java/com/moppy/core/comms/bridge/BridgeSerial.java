// Originally written by Sam Archer https://github.com/SammyIAm/Moppy2, heavily modified to be compatible with Android by Noah Reeder

/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package com.moppy.core.comms.bridge;

import android.content.Context;
import android.hardware.usb.UsbManager;
import android.hardware.usb.UsbDevice;

import com.felhr.usbserial.UsbSerialDevice;

import com.moppy.core.comms.MoppyMessage;
import com.moppy.core.comms.MoppyMessageFactory;
import com.moppy.core.comms.NetworkMessageConsumer;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * A Serial connection for Moppy devices.
 */
public class BridgeSerial extends NetworkBridge {
    private static UsbManager usbManager;
    private final UsbDevice device;
    private UsbSerialDevice serialPort;
    private Thread listenerThread = null;

    /**
     * Assigns static variables. Must be called before use of BridgeSerial objects.
     *
     * @param context the context used to retrieve Android system resources
     * @author Noah Reeder
     */
    public static void init(Context context) {
        // Assign static variables
        usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
    }

    /**
     * Constructs a BridgeSerial object to communicate on a specific port.
     *
     * @param serialPortName the port to communicate on
     * @throws IllegalArgumentException if the specified device doesn't exist
     * @throws UnableToObtainDeviceException if the serial port representing the specified device could not be obtained
     * @author Noah Reeder
     */
    public BridgeSerial(String serialPortName) {
        // Get the USB device attached to the specified port and ensure it is valid
        device = usbManager.getDeviceList().get(serialPortName);
        if (device == null) {
            // The device doesn't exist, log it and return an IllegalArgumentException
            Logger.getLogger(BridgeSerial.class.getName()).log(Level.SEVERE, "Device at " + serialPortName + " doesn't exist");
            throw new IllegalArgumentException("Unable to find device at " + serialPortName);
        }

        // Create the serial port and ensure it is valid
        serialPort = UsbSerialDevice.createUsbSerialDevice(device, usbManager.openDevice(device));
        if (serialPort == null) {
            // There's nothing we can do about it not opening so just log it and throw a runtime exception
            Logger.getLogger(BridgeSerial.class.getName()).log(Level.SEVERE, "Unable to open port " + serialPortName, device);
            throw new UnableToObtainDeviceException("Unable to open " + serialPortName);
        }

        // Set the friendly name of the serial port
        serialPort.setPortName(serialPortName);
    }

    public static List<String> getAvailableSerials() {
        List<String> serialList = new ArrayList<>();
        serialList.addAll(usbManager.getDeviceList().keySet());
        return serialList;
    }

    @Override
    public void connect() throws IOException {
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
            throw new IOException("UsbSerialDevice instance did not open");
        } // End if(serialPort.syncOpen) else

        /* ***********DEPRECATED************
        Part of original BridgeSerial implementation, preserved for completeness and clarity

        // Set to semiblocking mode (will wait for up to 100 milliseconds before returning nothing from a read)
        // On a very slow connection this could maaaybe cause some messages to be dropped / corrupted, but for
        // serial connections it should be a minor risk
        serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0);

        ************************************
        When replacing jSerialComm with UsbSerial, the new library did not support this "semiblocking" mode
        After analyzing the dependant code I made the decision to switch to synchronous operation
         */

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

    /**
     * Closes this BridgeSerial connection. Unlike the original MoppyLib implementation, renders this BridgeSerial inoperable
     * @throws IOException if unable to write the SYS_STOP message
     * @throws UnableToObtainDeviceException if the serial port could not be recreated after closing communication
     * @author Sam Archer
     * @author Noah Reeder
     */
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

        SerialListener(UsbSerialDevice serialPort, NetworkMessageConsumer messageConsumer) {
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
                        }
                        catch (IllegalArgumentException ex) {
                            Logger.getLogger(BridgeSerial.class.getName()).log(Level.WARNING, "Exception reading network message", ex);
                        }
                    }
                }
            }
            catch (IOException ex) {
                Logger.getLogger(BridgeSerial.class.getName()).log(Level.WARNING, null, ex);
            }
        }

    }

    /**
     * Runtime exception thrown if the requested USB device could not be opened
     */
    public static class UnableToObtainDeviceException extends RuntimeException {
        public UnableToObtainDeviceException() { super(); }

        public UnableToObtainDeviceException(String message) { super(message); }

        public UnableToObtainDeviceException(String message, Throwable cause) { super(message, cause); }

        public UnableToObtainDeviceException(Throwable cause) { super(cause); }
    } // End UnableToOpenDeviceException class
} // End BridgeSerial class

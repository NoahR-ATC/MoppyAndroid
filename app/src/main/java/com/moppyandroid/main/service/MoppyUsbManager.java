// A streamlined version of com.moppy.control.NetworkManager from Moppy2 adapted to run on Android.
// NetworkManager author: Sam Archer https://github.com/SammyIAm

package com.moppyandroid.main.service;

import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.util.Log;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.moppy.core.comms.bridge.BridgeSerial;
import com.moppy.core.comms.bridge.MultiBridge;
import com.moppy.core.comms.bridge.NetworkBridge;
import com.moppy.core.status.StatusBus;
import com.moppy.core.status.StatusUpdate;

public class MoppyUsbManager {
    private final StatusBus statusBus;
    private final MultiBridge multiBridge;
    private final HashMap<String, NetworkBridge> networkBridges;
    private final List<String> bridgeIdentifiers;
    private final UsbManager androidUsbManager;
    private List<String> connectedIdentifiers;

    /**
     * Creates a MoppyUsbManager, using the specified bus to alert consumers to device change events.
     *
     * @param statusBus the bus used to communicate with status consumers
     */
    public MoppyUsbManager(StatusBus statusBus, Context context) {
        this.statusBus = statusBus;
        multiBridge = new MultiBridge();
        networkBridges = new HashMap<>();
        bridgeIdentifiers = new ArrayList<>();
        connectedIdentifiers = new ArrayList<>();
        androidUsbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);

        refreshDeviceList();
    } // End MoppyUsbManager constructor

    /**
     * Connects to a specified network bridge using its serial port path (e.g. /dev/bus/usb/001/002).
     * Potentially throws {@link com.moppy.core.comms.bridge.BridgeSerial.UnableToObtainDeviceException}.
     *
     * @param bridgeIdentifier the identifier of the bridge to connect
     * @throws IOException if unable to create the bridge
     */
    public void connectBridge(String bridgeIdentifier) throws IOException {
        try {
            // Check if the bridge has already been created
            NetworkBridge currentBridge = networkBridges.get(bridgeIdentifier);
            if (currentBridge != null) { currentBridge.connect(); }
            else {
                BridgeSerial newBridge = new BridgeSerial(bridgeIdentifier);
                newBridge.connect();
                newBridge.registerMessageReceiver(multiBridge);
                multiBridge.addBridge(newBridge);
                networkBridges.put(bridgeIdentifier, newBridge);
                connectedIdentifiers.add(bridgeIdentifier);
            } // End if(currentBridge != null) {} else
        } // End try {bridge.connect}
        finally {
            statusBus.receiveUpdate(StatusUpdate.NET_STATUS_CHANGED);
        } // End try {bridge.connect} finally
    } // End addBridge method

    /**
     * Closes a specified network bridge using its serial port path (e.g. /dev/bus/usb/001/002). The
     * {@link IOException} is informational and can be ignored without any side effects.
     *
     * @param bridgeIdentifier the identifier of the bridge to close
     * @throws IOException if unable to send the close message to the device
     */
    public void closeBridge(String bridgeIdentifier) throws IOException {
        NetworkBridge bridge = networkBridges.get(bridgeIdentifier);
        if (bridge == null) { return; }
        multiBridge.removeBridge(bridge);
        bridge.deregisterMessageReceiver(multiBridge); // Just in case a message gets sent mid-closing
        networkBridges.remove(bridgeIdentifier);
        connectedIdentifiers.remove(bridgeIdentifier);
        try {
            bridge.close();
        } // End try {bridge.close}
        finally {
            statusBus.receiveUpdate(StatusUpdate.NET_STATUS_CHANGED);
        } // End try {networkBridges.put(new BridgeSerial)} finally
    } // End closeBridge method

    /**
     * Closes all open connections, logging and discarding any generated {@link IOException}s.
     */
    public void closeAllBridges() {
        for (String identifier : connectedIdentifiers) {
            try { closeBridge(identifier); }
            catch (IOException e) {
                // In the words of MoppyLib's author when they deal with this exception, "There's not
                // much we can do if it fails to close (it's probably already closed). Just log it and move on"
                Log.e(MoppyUsbManager.class.getName() + "->closeAllBridges", "Unable to close bridge '" + identifier + "'", e);
            } // End try {closeBridge} catch(IOException)
        } // End for(identifier : connectedIdentifiers)
    } // End closeAllBridges method

    /**
     * Checks if a specific bridge is connected using its serial port path (e.g. /dev/bus/usb/001/002).
     *
     * @param bridgeIdentifier the identifier of the bridge to check connection status
     */
    public boolean isConnected(String bridgeIdentifier) {
        NetworkBridge bridge = networkBridges.get(bridgeIdentifier);
        return (bridge != null && bridge.isConnected());
    } // End isConnected method

    /**
     * Refreshes the list of bridge identifiers with connected devices guaranteed to remain.
     */
    public void refreshDeviceList() {
        // Clear bridge list and add all serial bridges
        bridgeIdentifiers.clear();
        bridgeIdentifiers.addAll(BridgeSerial.getAvailableSerials());

        // Create a new list of connected bridges and remove any disconnected bridges from the list
        List<String> newConnectedIdentifiers = new ArrayList<>();
        for (int i = 0; i < connectedIdentifiers.size(); ++i) {
            if (bridgeIdentifiers.contains(connectedIdentifiers.get(i))) {
                newConnectedIdentifiers.add(connectedIdentifiers.get(i));
            } // End if(bridgeIdentifiers ∋ currentConnectedIdentifier)
            else {
                NetworkBridge bridge = networkBridges.get(connectedIdentifiers.get(i));
                if (bridge == null) { continue; }
                multiBridge.removeBridge(bridge);
                bridge.deregisterMessageReceiver(multiBridge);
                networkBridges.remove(connectedIdentifiers.get(i));
            } // End if(bridgeIdentifiers ∋ currentConnectedIdentifier) {} else
        } // End for(i < connectedIdentifiers.size)

        connectedIdentifiers = newConnectedIdentifiers;
    } // End refreshDeviceList method

    /**
     * Retrieves the {@link MultiBridge} this {@code MoppyUsbManager} manages. Needed for registering
     * this {@code MoppyUsbManager} to receive messages from a {@link com.moppy.core.midi.MoppyMIDIReceiverSender}.
     *
     * @return the managed {@code MultiBridge}
     */
    public NetworkBridge getPrimaryBridge() { return multiBridge; }

    /**
     * Retrieves the list of bridge identifiers that are available for connection. The list used by {@link #connectBridge(String)} is
     * updated automatically upon device plugging/unplugging, however the list returned here is not. This list can be updated
     * with {@link #refreshDeviceList()}.
     *
     * @return the cached bridge identifiers
     */
    public List<String> getDevices() { return bridgeIdentifiers; }

    /**
     * Retrieves the number of connected bridges.
     *
     * @return the number of bridges
     */
    public int getNumberConnected() { return connectedIdentifiers.size(); }

    /**
     * Retrieves the list of the identifiers for all connected bridges
     *
     * @return the connect bridge identifiers
     */
    public List<String> getConnectedIdentifiers() { return connectedIdentifiers; }

    /**
     * Uses the identifier of a device to get the following information in an {@link ArrayList}:
     * {port name, product name, manufacturer name, hex vendor ID, hex product ID}.
     * {@code ArrayList} returned rather than {@link List} because it implements {@link java.io.Serializable}.
     *
     * @param identifier the identifier of the device to find information on
     * @return an {@code ArrayList<String>} with the device information
     */
    public ArrayList<String> getDeviceInfo(String identifier) {
        ArrayList<String> info = new ArrayList<>(5);
        info.add(identifier);

        // Try to retrieve the UsbDevice object for the current device. NOTE: null if not found
        UsbDevice usbDevice = androidUsbManager.getDeviceList().get(identifier);

        // If we received a UsbDevice object add information of interest to the array
        if (usbDevice != null) {
            if (usbDevice.getProductName() != null) { info.add(usbDevice.getProductName()); }
            else { info.add(null); }

            if (usbDevice.getManufacturerName() != null) {
                info.add(usbDevice.getManufacturerName());
            }
            else { info.add(null); }

            info.add("0x" + String.format("%1$04X", usbDevice.getVendorId()));
            info.add("0x" + String.format("%1$04X", usbDevice.getProductId()));
        } // End if(usbDevice != null)
        else {
            // Fill information array with null if device not found
            info.add(null);
            info.add(null);
            info.add(null);
            info.add(null);
        } // End if(usbDevice != null) {} else

        return info;
    } // End getDeviceInfo method

    /**
     * Retrieves the following information for all devices in an {@link ArrayList}:
     * {port name, product name, manufacturer name, hex vendor ID, hex product ID}.
     * {@code ArrayList} returned rather than {@link List} because it implements {@link java.io.Serializable}.
     *
     * @return an {@code ArrayList<ArrayList<String>>} of the information for all available devices
     */
    public ArrayList<ArrayList<String>> getDeviceInfoForAll() {
        ArrayList<ArrayList<String>> result = new ArrayList<>();
        for (String identifier : bridgeIdentifiers) { result.add(getDeviceInfo(identifier)); }
        return result;
    } // End getDeviceInfoForAll method
} // End MoppyUsbManager class


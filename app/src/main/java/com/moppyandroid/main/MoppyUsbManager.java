// A streamlined version of com.moppy.control.NetworkManager from Moppy2 adapted to run on Android.
// NetworkManager author: Sam Archer https://github.com/SammyIAm

package com.moppyandroid.main;

import android.util.Log;

import java.io.IOException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import com.moppy.core.comms.bridge.BridgeSerial;
import com.moppy.core.comms.bridge.BridgeUDP;
import com.moppy.core.comms.bridge.MultiBridge;
import com.moppy.core.comms.bridge.NetworkBridge;
import com.moppy.core.status.StatusBus;
import com.moppy.core.status.StatusUpdate;

public class MoppyUsbManager {
    private final StatusBus statusBus;
    private final MultiBridge multiBridge;
    private final HashMap<String, NetworkBridge> networkBridges;
    private final List<String> bridgeIdentifiers;
    private final List<String> connectedIdentifiers;

    /**
     * Creates a MoppyUsbManager, using the specified bus to alert consumers to device change events.
     *
     * @param statusBus the bus used to communicate with status consumers
     */
    public MoppyUsbManager(StatusBus statusBus) {
        this.statusBus = statusBus;
        multiBridge = new MultiBridge();
        networkBridges = new HashMap<>();
        bridgeIdentifiers = new ArrayList<>();
        connectedIdentifiers = new ArrayList<>();

        // Attempt to create a UDP bridge and refresh the device lists
        try {
            BridgeUDP udpBridge = new BridgeUDP();
            networkBridges.put(udpBridge.getNetworkIdentifier(), udpBridge);
        } // End try {networkBridges.put(new BridgeUDP())}
        catch (UnknownHostException ex) {
            Log.e(MoppyUsbManager.class.getName(), "Unable to create UDP Bridge", ex);
        } // End try {networkBridges.put(new BridgeUDP)} catch(UnknownHostException)
        refreshDeviceList();
    } // End MoppyUsbManager constructor

    /**
     * Connects to a specified network bridge using its identifier, which usually is either a UDP socket
     * or serial port path. Potentially throws {@link BridgeSerial.UnableToObtainDeviceException}.
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
            } // End if(currentBridge != null) {} else
            connectedIdentifiers.add(bridgeIdentifier);
        } // End try {bridge.connect}
        finally {
            statusBus.receiveUpdate(StatusUpdate.NET_STATUS_CHANGED);
        } // End try {bridge.connect} finally
    } // End addBridge method

    /**
     * Closes a specified network bridge using its identifier, which usually is either a UDP socket or serial
     * port path. The {@link IOException} is mostly informational and can be ignored without any side effects.
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
        try {
            bridge.close();
            connectedIdentifiers.remove(bridgeIdentifier);
        } // End try {bridge.close}
        finally {
            statusBus.receiveUpdate(StatusUpdate.NET_STATUS_CHANGED);
        } // End try {networkBridges.put(new BridgeSerial)} finally
    } // End closeBridge method

    /**
     * Closes all open connections, logging and discarding any generated {@link IOException}s.
     */
    public void closeAllBridges() {
        for (String bridgeIdentifier : connectedIdentifiers) {
            try { closeBridge(bridgeIdentifier); }
            catch (IOException e) {
                // In the words of MoppyLib's author when they deal with this exception, "There's not
                // much we can do if it fails to close (it's probably already closed). Just log it and move on"
                Log.e(MoppyUsbManager.class.getName() + "->closeAllBridges", "Unable to close a bridge", e);
            }
        }
    }

    /**
     * Checks if a specific bridge is connected using its identifier, which usually is either a UDP socket
     * or serial port path.
     *
     * @param bridgeIdentifier the identifier of the bridge to check connection status
     */
    public boolean isConnected(String bridgeIdentifier) {
        NetworkBridge bridge = networkBridges.get(bridgeIdentifier);
        return (bridge != null && bridge.isConnected());
    } // End isConnected method

    /**
     * Refreshes the list of bridge identifiers
     */
    public void refreshDeviceList() {
        // Add all UDP bridges as well as all available serial bridges to the identifier list
        // Note: If any serial bridges are in the networkBridges hashmap the keys will be the same so it doesn't matter
        bridgeIdentifiers.clear();
        bridgeIdentifiers.addAll(networkBridges.keySet());
        bridgeIdentifiers.addAll(BridgeSerial.getAvailableSerials());
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
} // End MoppyUsbManager class

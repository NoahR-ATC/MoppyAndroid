package com.moppyandroid.main;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.CheckBox;

import com.moppyandroid.main.service.MoppyMediaService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class DeviceSelectorDialog implements AutoCloseable {
    private boolean refreshNext = false;
    private boolean initialized = false;
    private int loadingBarRequests;
    private Map<UsbDevice, CheckBox> deviceCheckBoxMap;
    private MediaBrowserCompat mediaBrowser;
    private RecyclerView deviceRecycler;
    private AlertDialog selectorDialog;
    private AlertDialog emptyDialog;
    private AlertDialog loadingBar;
    private Context context;
    private UsbManager usbManager;

    /**
     * Constructs a {@code DeviceSelectorDialog} but does not show it.
     *
     * @param context the context to create the {@code DeviceSelectorDialog} in
     */
    public DeviceSelectorDialog(Context context) {
        loadingBarRequests = 0;
        deviceCheckBoxMap = new HashMap<>();
        this.context = context;
        usbManager = (UsbManager) context.getSystemService(Service.USB_SERVICE);
        assert usbManager != null; // Shouldn't ever arise

        View v = LayoutInflater.from(context).inflate(R.layout.selector_dialog_layout, null);
        deviceRecycler = v.findViewById(R.id.device_recycler);
        deviceRecycler.setLayoutManager(new LinearLayoutManager(context));
        deviceRecycler.setAdapter(new DeviceAdapter(null, null, null, null));

        // Connect to the media service for media tree loading
        mediaBrowser = new MediaBrowserCompat(
                context,
                new ComponentName(context, MoppyMediaService.class),
                new DeviceSelectorDialog.BrowserConnectionCallback(),
                null
        );
        mediaBrowser.connect();

        AlertDialog.Builder selectorBuilder = new AlertDialog.Builder(context);
        selectorBuilder.setTitle("Connect to a device");
        selectorBuilder.setCancelable(true);
        selectorBuilder.setView(v);
        selectorDialog = selectorBuilder.create();

        AlertDialog.Builder emptyBuilder = new AlertDialog.Builder(context);
        emptyBuilder.setTitle("Connect to a device");
        emptyBuilder.setCancelable(true);
        emptyBuilder.setMessage("No Moppy devices available");
        emptyDialog = emptyBuilder.create();

        // Create the loading bar from an alert dialog containing loading_dialog_layout
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(context);
        alertBuilder.setCancelable(false);
        alertBuilder.setView(LayoutInflater.from(context).inflate(R.layout.loading_dialog_layout, null));
        loadingBar = alertBuilder.create();
    } // End DeviceSelectorDialog(Context) constructor

    /**
     * Releases held resources. <b>MUST</b> be called before this {@code DeviceSelectorDialog}'s destruction.
     */
    @Override
    public void close() {
        if (mediaBrowser != null && mediaBrowser.isConnected()) { mediaBrowser.disconnect(); }
        selectorDialog.dismiss();
        emptyDialog.dismiss();
    } // End close method

    /**
     * Handles device connection when permission had to be requested first. <b>MUST</b> be called
     * when a {@link MainActivity#ACTION_USB_PERMISSION} broadcast is handled by {@code MainActivity}'s
     * {@link android.content.BroadcastReceiver}.
     *
     * @param intent the intent associated with the {@code ACTION_USB_PERMISSION} broadcast
     */
    public void onUsbPermissionIntent(Intent intent) {
        UsbDevice device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (device == null) { return; }
        CheckBox checkBox = deviceCheckBoxMap.get(device);
        if (checkBox == null) { return; }

        // Ensure permission was granted
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            connectDevice(device, checkBox);
        } // End if(EXTRA_PERMISSION_GRANTED)
        else {
            checkBox.setChecked(false);
            AlertDialog.Builder builder = new AlertDialog.Builder(context);
            builder.setTitle("MoppyAndroid")
                    .setCancelable(false)
                    .setMessage("USB permission is required to connect")
                    .setPositiveButton("OK", null)
                    .show();
        } // End if(EXTRA_PERMISSION_GRANTED) {} else
        deviceCheckBoxMap.remove(device);
        closeLoadingBar();
    } // End onUsbPermissionIntent method

    /**
     * Updates this {@code DeviceSelectorDialog}'s available device lists if it is showing, otherwise waits
     * for the next time this {@code DeviceSelectorDialog} is shown to refresh. Should be called when either
     * {@link UsbManager#ACTION_USB_DEVICE_ATTACHED} or {@link UsbManager#ACTION_USB_DEVICE_DETACHED} occurs.
     */
    public void onDeviceConnectionStateChanged() {
        // Just in case updateDevices is called before the activity's refresh completes, lets do our own refresh
        refreshNext = true;
        if (selectorDialog.isShowing() || emptyDialog.isShowing()) { updateDevices(); }
    } // End onDeviceConnectionStateChange method

    /**
     * Shows this {@code DeviceSelectorDialog}.
     */
    public void show() {
        selectorDialog.show();
        updateDevices(); // Will close selectorDialog and show emptyDialog if necessary
    } // End show method

    /**
     * Updates the list of available devices in this {@code DeviceSelectorDialog}.
     */
    public void updateDevices() {
        if (initialized) {
            String action = refreshNext ? MoppyMediaService.ACTION_REFRESH_DEVICES : MoppyMediaService.ACTION_GET_DEVICES;
            mediaBrowser.sendCustomAction(action, null, new MediaBrowserCompat.CustomActionCallback() {
                @Override
                public void onResult(String action, Bundle extras, Bundle resultData) {
                    List<UsbDevice> list = resultData.getParcelableArrayList(MoppyMediaService.EXTRA_DEVICE_INFOS);
                    assert list != null; // Should be impossible, the service doesn't ever send this action's result without it

                    // Sort by port name and recreate the adapter
                    list.sort((o1, o2) -> o1.getDeviceName().compareTo(o2.getDeviceName()));
                    deviceRecycler.setAdapter(new DeviceAdapter(list,
                            resultData.getStringArrayList(MoppyMediaService.EXTRA_DEVICES_CONNECTED),
                            DeviceSelectorDialog.this::showDeviceInfo,
                            (device, checkBox, isChecked) -> {
                                if (isChecked) { onConnectDevice(device, checkBox); }
                                else { onRemoveDevice(device); }
                            } // End checkBoxListener lambda
                    )); // End setAdapter call

                    // If necessary, switch the selector dialog to the empty dialog or vice versa
                    if (list.size() < 1 && selectorDialog.isShowing()) {
                        selectorDialog.dismiss();
                        emptyDialog.show();
                    } // End if(size < 1 && selectorDialog.showing)
                    else if (list.size() > 1 && emptyDialog.isShowing()) {
                        emptyDialog.dismiss();
                        selectorDialog.show();
                    } // End if(size < 1 && selectorDialog.showing) {} else if (size > 1 && emptyDialog.showing)

                    super.onResult(action, extras, resultData);
                } // End ACTION_GET_DEVICES.onResult method
            }); // End ACTION_GET_DEVICES callback
            refreshNext = false;
        } // End if(initialized)
        else {
            if (selectorDialog.isShowing()) {
                selectorDialog.dismiss();
                emptyDialog.show();
            } // End if(selectorDialog.isShowing)
        } // End if(initialized) {} else
    } // End updateDevices method

    // Triggered by a device's checkbox being checked. Requests the service to connect a device, requesting
    // USB access permission from the OS if necessary
    private void onConnectDevice(UsbDevice device, CheckBox checkBox) {
        showLoadingBar(); // Needed so a connect and disconnect can't be sent at the same time

        // Request device permission if necessary
        if (!usbManager.hasPermission(device)) {
            deviceCheckBoxMap.put(device, checkBox);
            PendingIntent pendingIntent = PendingIntent.getBroadcast(context,
                    MainActivity.REQUEST_DEVICE_PERMISSION,
                    new Intent(MainActivity.ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_UPDATE_CURRENT
            );
            usbManager.requestPermission(device, pendingIntent);
        } // End if(device.permissionGranted)
        else { connectDevice(device, checkBox); }
    } // End onConnectDevice method

    // Triggered by a device's checkbox being unchecked. Requests the service to disconnect a device
    // Note: Will be called when a connection fails, but that isn't a problem
    private void onRemoveDevice(UsbDevice device) {
        // Send intent to close the current bridge
        Bundle disconnectBundle = new Bundle();
        disconnectBundle.putString(MoppyMediaService.EXTRA_PORT_NAME, device.getDeviceName());
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_REMOVE_DEVICE, disconnectBundle, null);
    } // End onRemoveDevice method

    // Triggered by clicking on a device name/info icon. Pops up a message box showing information about the device
    private void showDeviceInfo(UsbDevice device) {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(context);
        StringBuilder messageBuilder = new StringBuilder();
        alertBuilder.setTitle(device.getDeviceName());
        messageBuilder.append("Product: ")
                .append(device.getProductName() == null ? "" : device.getProductName())
                .append("\n")
                .append("Manufacturer: ")
                .append(device.getManufacturerName() == null ? "" : device.getManufacturerName())
                .append("\n")
                .append("Vendor/Product ID: ")
                .append("0x").append(String.format("%1$04X", device.getVendorId())).append("/")
                .append("0x").append(String.format("%1$04X", device.getProductId()));
        alertBuilder.setMessage(messageBuilder.toString());
        alertBuilder.show();
    } // End showDeviceInfo method

    // Initializes the dialog
    private void init() {
        updateDevices();
        initialized = true;
    } // End init method

    // Requests the service to connect to a device, showing an error and unchecking its box if unsuccessful
    private void connectDevice(UsbDevice device, CheckBox checkBox) {
        Bundle connectBundle = new Bundle();
        connectBundle.putString(MoppyMediaService.EXTRA_PORT_NAME, device.getDeviceName());
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_ADD_DEVICE, connectBundle, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            public void onResult(String action, Bundle extras, Bundle resultData) {
                loadingBar.dismiss();
                super.onResult(action, extras, resultData);
            } // End ACTION_ADD_DEVICE.onResult method

            @Override
            public void onError(String action, Bundle extras, Bundle data) {
                checkBox.setChecked(false);

                // Log and show error alert
                Log.e(DeviceSelectorDialog.class.getName() + "->onConnectDevice",
                        "Unable to connect to device",
                        (Throwable) (data.getSerializable(MoppyMediaService.EXTRA_EXCEPTION))
                );
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle("MoppyAndroid")
                        .setCancelable(false)
                        .setMessage("Unable to connect to " + data.getString(MoppyMediaService.EXTRA_PORT_NAME))
                        .setPositiveButton("OK", null)
                        .show();

                closeLoadingBar(); // Remove the loading bar if all requests fulfilled
                super.onError(action, extras, data);
            } // End ACTION_ADD_DEVICE.onError method
        }); // End ACTION_ADD_DEVICE callback
    } // End onConnectDevice method

    // Adds a loading bar usage and shows the bar if necessary
    private void showLoadingBar() {
        if (loadingBarRequests++ == 0) { loadingBar.show(); }
    } // End showLoadingBar method

    // Subtracts a loading bar usage and closes the bar if necessary
    private void closeLoadingBar() {
        if (--loadingBarRequests == 0) { loadingBar.dismiss(); }
    } // End closeLoadingBar method

    // Receives callbacks about mediaBrowser.connect
    private class BrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
        @Override
        public void onConnected() { // Successful connection
            // Run the initializer
            if (!initialized) { init(); }
            super.onConnected();
        } // End onConnected method

        @Override
        public void onConnectionSuspended() { // Server crashed, awaiting restart
            // Nothing to do here, mediaBrowser is only vulnerably accessed through loadRecyclers and that
            // ignores the input if not connected
            super.onConnectionSuspended();
        } // End onConnectionSuspended method

        @Override
        public void onConnectionFailed() { // Connection refused
            // Log what (shouldn't have) happened and close the activity
            Log.wtf(BrowserActivity.class.getName() + "->onConnectionFailed", "Unable to connect to MoppyMediaService in browser");
            super.onConnectionFailed();
        } // End onConnectionFailed method
    } // End BrowserConnectionCallback class
} // End DeviceSelectorDialog class

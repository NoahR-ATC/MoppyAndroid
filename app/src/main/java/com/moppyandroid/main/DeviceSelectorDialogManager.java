package com.moppyandroid.main;

import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;

import androidx.fragment.app.FragmentManager;

import com.moppyandroid.main.service.MoppyMediaService;

/**
 * Manager for a {@link DeviceSelectorDialog}.
 */
public class DeviceSelectorDialogManager implements AutoCloseable {
    private boolean sendConnectionStateChange = false;
    private DeviceSelectorDialog deviceDialog;
    private MediaBrowserCompat mediaBrowser;
    private FragmentManager fragmentManager;
    private String tag;

    /**
     * Constructs a new {@code DeviceSelectorDialogManager} and connects to a {@link DeviceSelectorDialog}.
     *
     * @param context the {@link Context} to use
     * @param fragmentManager the {@link FragmentManager} to use
     * @param tag the tag to use when registering the {@link DeviceSelectorDialog} with {@code fragmentManager}
     */
    public DeviceSelectorDialogManager(Context context, FragmentManager fragmentManager, String tag) {
        this.fragmentManager = fragmentManager;
        this.tag = tag;

        // Check if the dialog has already been created and create it if not
        deviceDialog = (DeviceSelectorDialog) fragmentManager.findFragmentByTag(tag);
        if (deviceDialog == null) {
            // Connect to the media service for media tree loading
            mediaBrowser = new MediaBrowserCompat(
                    context,
                    new ComponentName(context, MoppyMediaService.class),
                    new DeviceSelectorDialogManager.BrowserConnectionCallback(),
                    null
            );
            deviceDialog = DeviceSelectorDialog.newInstance(mediaBrowser);
            mediaBrowser.connect();
        } // End if(deviceDialog == null)
        else { // Existing dialog found
            // Retrieve the MediaBrowser from the dialog
            mediaBrowser = deviceDialog.getMediaBrowser();
            // Since the service wouldn't have received device connection intents while the app was
            // shutdown we need to force a refresh upon service connection
            deviceDialog.onDeviceConnectionStateChanged();
            mediaBrowser.connect();
        } // End if(deviceDialog == null) {} else
    } // End DeviceSelectorDialogManager constructor

    /**
     * Releases held resources. <b>MUST</b> be called before this {@code DeviceSelectorDialogManager}'s destruction.
     */
    @Override
    public void close() {
        // Always disconnect if possible since there's no way to know if the mediaBrowser is currently connecting
        if (mediaBrowser != null) { mediaBrowser.disconnect(); }
    } // End close method

    /**
     * Forwards results from {@link android.hardware.usb.UsbManager#requestPermission(UsbDevice, PendingIntent)}
     * to the {@link DeviceSelectorDialog} for further processing.
     *
     * @param intent the {@link Intent} received in the {@link android.content.BroadcastReceiver}
     */
    public void onUsbPermissionIntent(Intent intent) {
        if (deviceDialog != null) { deviceDialog.onUsbPermissionIntent(intent); }
        // If the dialog isn't created then it can't be awaiting the intent so there is no need to send it
    } // End onUsbPermissionIntent

    /**
     * Lets the {@link DeviceSelectorDialog} know that a USB device has been connected or disconnected.
     * Should be called when handling {@link android.hardware.usb.UsbManager#ACTION_USB_DEVICE_ATTACHED}
     * and {@link android.hardware.usb.UsbManager#ACTION_USB_DEVICE_DETACHED} messages.
     */
    public void onDeviceConnectionStateChanged() {
        if (deviceDialog != null) { deviceDialog.onDeviceConnectionStateChanged(); }
        else { sendConnectionStateChange = true; }
    } // End onDeviceConnectionStateChanged method

    /**
     * Shows the {@link DeviceSelectorDialog}.
     */
    public void show() { deviceDialog.show(fragmentManager, tag); }

    // Receives callbacks about mediaBrowser.connect
    private class BrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
        @Override
        public void onConnected() { // Successful connection
            // Update the devices now that we can access a list
            if (deviceDialog != null) {
                if (sendConnectionStateChange) { deviceDialog.onDeviceConnectionStateChanged(); }
                deviceDialog.updateDevices();
            }
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
} // End DeviceSelectorDialogManager class

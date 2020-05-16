package com.moppyandroid.main;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.ComponentName;
import android.content.Context;
import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.moppyandroid.main.service.MoppyMediaService;

import java.util.List;

public class DeviceSelectorDialog implements AutoCloseable {
    private boolean initialized = false;
    private MediaBrowserCompat mediaBrowser;
    private RecyclerView deviceRecycler;
    private AlertDialog selectorDialog;
    private AlertDialog emptyDialog;
    private Context context;

    public DeviceSelectorDialog(Context context) {
        this.context = context;

        View v = LayoutInflater.from(context).inflate(R.layout.selector_dialog_layout, null);
        deviceRecycler = v.findViewById(R.id.device_recycler);
        deviceRecycler.setLayoutManager(new LinearLayoutManager(context));
        deviceRecycler.setAdapter(new DeviceAdapter(null, null, null));

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
    }

    @Override
    public void close() {
        if (mediaBrowser != null && mediaBrowser.isConnected()) { mediaBrowser.disconnect(); }
    }

    public void show() {
        selectorDialog.show();
        updateDevices(); // Will close selectorDialog and show emptyDialog if necessary
    }

    public void updateDevices() {
        if (initialized) {
            mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_GET_DEVICES, null, new MediaBrowserCompat.CustomActionCallback() {
                @Override
                public void onResult(String action, Bundle extras, Bundle resultData) {
                    List<UsbDevice> list = resultData.getParcelableArrayList(MoppyMediaService.EXTRA_DEVICE_INFOS);
                    assert list != null; // Should be impossible, the service doesn't ever send this action's result without it

                    // Sort by port name and recreate the adapter
                    list.sort((o1, o2) -> o1.getDeviceName().compareTo(o2.getDeviceName()));
                    deviceRecycler.setAdapter(new DeviceAdapter(list,
                            DeviceSelectorDialog.this::showDeviceInfo,
                            null //TODO implement listener
                    ));

                    // If necessary, switch the selector dialog to the empty dialog or vice versa
                    if (list.size() < 1 && selectorDialog.isShowing()) {
                        selectorDialog.hide();
                        emptyDialog.show();
                    } // End if(size < 1 && selectorDialog.showing)
                    else if (list.size() > 1 && emptyDialog.isShowing()) {
                        emptyDialog.hide();
                        selectorDialog.show();
                    } // End if(size < 1 && selectorDialog.showing) {} else if (size > 1 && emptyDialog.showing)

                    super.onResult(action, extras, resultData);
                } // End ACTION_GET_DEVICES.onResult method
            }); // End ACTION_GET_DEVICES callback
        } // End if(initialized)
    } // End updateDevices method

    private void init() {
        updateDevices();
        initialized = true;
    } // End init method

    private void showDeviceInfo(UsbDevice device) {
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(context);
        StringBuilder messageBuilder = new StringBuilder();
        alertBuilder.setTitle(device.getDeviceName());
        messageBuilder
                .append("Product: ")
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
    }

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
}

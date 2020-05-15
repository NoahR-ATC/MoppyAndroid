package com.moppyandroid.main;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;

import com.moppyandroid.main.service.MoppyMediaService;

import java.util.ArrayList;

public class DeviceSelectorDialog implements AutoCloseable {
    private boolean initialized = false;
    private MediaBrowserCompat mediaBrowser;
    private RecyclerView deviceRecycler;
    private AlertDialog alertDialog;

    public DeviceSelectorDialog(Context context) {
        View v = LayoutInflater.from(context).inflate(R.layout.selector_dialog_layout, null);
        deviceRecycler = v.findViewById(R.id.device_recycler);
        deviceRecycler.setLayoutManager(new LinearLayoutManager(context));
        deviceRecycler.setAdapter(new DeviceAdapter(null, null));

        // Connect to the media service for media tree loading
        mediaBrowser = new MediaBrowserCompat(
                context,
                new ComponentName(context, MoppyMediaService.class),
                new DeviceSelectorDialog.BrowserConnectionCallback(),
                null
        );
        mediaBrowser.connect();

        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Connect to a device");
        builder.setCancelable(true);
        builder.setView(v);
        alertDialog = builder.create();
    }

    @Override
    public void close() {
        if (mediaBrowser != null && mediaBrowser.isConnected()) { mediaBrowser.disconnect(); }
    }

    public void show() { alertDialog.show(); }

    private void init() {
        updateDevices();
        initialized = true;
    }

    private void updateDevices() {
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_GET_DEVICES, null, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            public void onResult(String action, Bundle extras, Bundle resultData) {
                ArrayList<String> list = resultData.getStringArrayList(MoppyMediaService.EXTRA_DEVICE_NAMES);
                list.add("/dev/test");
                list.add("/dev/test2");
                deviceRecycler.setAdapter(new DeviceAdapter(list, null));
                super.onResult(action, extras, resultData);
            }
        });
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

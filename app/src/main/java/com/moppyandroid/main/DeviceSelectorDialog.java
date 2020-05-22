package com.moppyandroid.main;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.app.Dialog;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AdapterView;
import android.widget.CheckBox;
import android.widget.Spinner;
import android.widget.TextView;

import com.moppyandroid.main.service.MoppyMediaService;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link DialogFragment} for selecting Moppy devices to connect/disconnect to.
 */
public class DeviceSelectorDialog extends DialogFragment implements DialogInterface.OnShowListener, Spinner.OnItemSelectedListener {
    private boolean refreshNext = false;
    private boolean emptyShowing = false;
    private int loadingBarRequests;
    private Map<UsbDevice, CheckBox> deviceCheckBoxMap;
    private MediaBrowserCompat mediaBrowser;
    private RecyclerView deviceRecycler;
    private TextView noDevicesText;
    private Spinner midiInSpinner;
    private Spinner midiOutSpinner;
    private AlertDialog loadingBar;
    private Context context;
    private UsbManager usbManager;
    private Dialog currentDialog;

    /**
     * Creates a new {@code DeviceSelectorDialog}. <b>This is the only way a {@code DeviceSelectorDialog}
     * should be created</b>. If creation is not done through this method the {@code DeviceSelectorDialog}
     * will be unable to communicate with the {@link MoppyMediaService} and will be effectively useless.
     *
     * @param mediaBrowser the {@link MediaBrowserCompat} used to communicate with the {@code MoppyMediaService}
     * @return the created {@code DeviceSelectorDialog}
     */
    public static DeviceSelectorDialog newInstance(MediaBrowserCompat mediaBrowser) {
        DeviceSelectorDialog dialog = new DeviceSelectorDialog();
        dialog.mediaBrowser = mediaBrowser;
        return dialog;
    } // End newInstance method

    /**
     * Triggered when this {@code DeviceSelectorDialog} is created.
     *
     * @see #onCreateDialog(Bundle)
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setRetainInstance(true);

        context = getContext();
        if (context == null) { throw new IllegalStateException("No context available"); }

        loadingBarRequests = 0;
        deviceCheckBoxMap = new HashMap<>();
        usbManager = (UsbManager) context.getSystemService(Service.USB_SERVICE);
        assert usbManager != null; // Shouldn't ever arise

        // Create the loading bar from an alert dialog containing loading_dialog_layout
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(context);
        alertBuilder.setCancelable(false);
        alertBuilder.setView(LayoutInflater.from(context).inflate(R.layout.loading_dialog_layout, null));
        loadingBar = alertBuilder.create();
    } // End onCreate method

    /**
     * Triggered when the contained dialog is created. If the dialog was recreated due to a configuration
     * change (e.g. device rotation, mouse plugged in) then this will be called without {@link #onCreate(Bundle)},
     * otherwise this will be called subsequently to {@code onCreate}.
     */
    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState) {
        View v = LayoutInflater.from(context).inflate(R.layout.selector_dialog_layout, null);
        noDevicesText = v.findViewById(R.id.no_devices_text);

        midiInSpinner = v.findViewById(R.id.midi_in_spinner);
        midiInSpinner.setOnItemSelectedListener(this);
        midiOutSpinner = v.findViewById(R.id.midi_out_spinner);
        midiOutSpinner.setOnItemSelectedListener(this);

        deviceRecycler = v.findViewById(R.id.device_recycler);
        deviceRecycler.setLayoutManager(new LinearLayoutManager(context));
        deviceRecycler.setAdapter(new DeviceAdapter(null, null, null, null));

        AlertDialog.Builder selectorBuilder = new AlertDialog.Builder(context);
        selectorBuilder.setTitle("Connect to a device");
        selectorBuilder.setCancelable(true);
        selectorBuilder.setView(v);
        Dialog thisDialog = selectorBuilder.create();
        thisDialog.setOnShowListener(this);
        currentDialog = thisDialog;
        return currentDialog;
    } // End onCreateDialog method

    /**
     * Triggered when the contained dialog is destroyed. If the dialog was destroyed due to a configuration
     * change (e.g. device rotation, mouse plugged in) then {@link androidx.fragment.app.Fragment#onDestroy()}
     * will not be called, but if the dialog was destroyed due to dismissal then {@code onDestroy} will be called.
     */
    @Override
    public void onDestroyView() { // Seriously? Issue from 2011 still not fixed...
        Dialog dialog = getDialog();
        // handles https://code.google.com/p/android/issues/detail?id=17423
        if (dialog != null && getRetainInstance()) {
            dialog.setDismissMessage(null);
        }
        super.onDestroyView();
    } // End onDestroyView method

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
        if (currentDialog != null && currentDialog.isShowing()) { updateDevices(); }
    } // End onDeviceConnectionStateChange method

    /**
     * Triggered when the contained dialog is shown.
     */
    @Override
    public void onShow(DialogInterface dialog) { updateDevices(); }

    /**
     * Triggered when an item in either of the {@link Spinner}s has been selected.
     */
    @Override
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        if (parent == midiInSpinner) {
            // TODO: Implement MIDI input connection
        } // End if(parent == midiInSpinner)
        else if (parent == midiOutSpinner) {
            // TODO: Implement MIDI output connection
        } // End if(parent == midiInSpinner) {} else if(parent == midiOutSpinner)
    } // End onItemSelected method

    /**
     * Triggered when the selection disappears from this view. The selection can disappear for
     * instance when touch is activated or when the adapter becomes empty.
     */
    @Override
    public void onNothingSelected(AdapterView<?> parent) { /* Nothing to do */ }

    /**
     * Updates the list of available devices in this {@code DeviceSelectorDialog}.
     */
    public void updateDevices() {
        if (deviceRecycler != null) {
            if (mediaBrowser != null && mediaBrowser.isConnected()) {
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
                        if (list.size() < 1 && !emptyShowing) {
                            deviceRecycler.setVisibility(View.GONE);
                            noDevicesText.setVisibility(View.VISIBLE);
                            emptyShowing = true;
                        } // End if(size < 1 && selectorDialog.showing)
                        else if (list.size() > 1 && emptyShowing) {
                            deviceRecycler.setVisibility(View.VISIBLE);
                            noDevicesText.setVisibility(View.GONE);
                            emptyShowing = false;
                        } // End if(size < 1 && selectorDialog.showing) {} else if (size > 1 && emptyDialog.showing)

                        super.onResult(action, extras, resultData);
                    } // End ACTION_GET_DEVICES.onResult method
                }); // End ACTION_GET_DEVICES callback
                refreshNext = false;
            } // End if(initialized)
            else {
                if (currentDialog != null && currentDialog.isShowing()) {
                    deviceRecycler.setVisibility(View.GONE);
                    noDevicesText.setVisibility(View.VISIBLE);
                    emptyShowing = true;
                } // End if(selectorDialog.isShowing)
            } // End if(initialized) {} else
        } // End if(deviceRecycler != null)
    } // End updateDevices method

    /**
     * Retrieves the {@link MediaBrowserCompat} this {@code DeviceSelectorDialog} uses to manage devices.
     *
     * @return the {@code MediaBrowserCompat} used to communicate with the {@link MoppyMediaService}
     */
    public MediaBrowserCompat getMediaBrowser() { return mediaBrowser; }

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


} // End DeviceSelectorDialog class

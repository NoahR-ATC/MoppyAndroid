package com.moppyandroid.main;

/*
Author: Noah Reeder, noahreederatc@gmail.com

Known bugs:
    - TODO: Investigate issues with restarting app (force stopping first works)
    - TODO: Gracefully close BridgeSerial connection on application close
    - TODO: Bug likely exists with refreshing devices while playing

Known problems:
    - Hard to use track slider in slide menu (adjust slide menu sensitivity?)
    - Must connect to device, disconnect, and connect again for connection to work


Features to implement:
    - MIDI I/O
    - Playlist


Scope creep is getting really bad... let's make a list of nice-to-have-but-slightly-out-of-scope features:
    - Sigh... Another port of MIDISplitter


Miscellaneous Notes:
    - MoppyMIDIReceiverSender used for MIDI I/O, not necessarily needed
        * We can still use it for MIDI in, but as of writing I have disabled output
        https://github.com/bluenote10/gervill/blob/master/src/main/java/com/sun/media/sound/RealTimeSequencer.java
        https://github.com/frohoff/jdk8u-dev-jdk/blob/master/src/share/classes/com/sun/media/sound/AbstractMidiDevice.java
           ^ createTransmitter


Regexes:
    Tip: When find box is open, press CTRL+ALT+F or filter button to be able to exclude comments

    Find all single-line braces without proper spacing:
        \{[^ \n}]
        [^ \n{]}

    Find all closing braces without a comment about what they close
        (^[^{\n]*})[^//\n]*$

 */


import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.RelativeLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.os.Bundle;

import com.moppyandroid.BridgeSerial;
import com.moppyandroid.com.moppy.core.events.mapper.MIDIEventMapper;
import com.moppyandroid.com.moppy.core.events.mapper.MapperCollection;
import com.moppyandroid.com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppyandroid.com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppyandroid.MoppyMIDISequencer;
import com.moppyandroid.com.moppy.core.status.StatusBus;
import com.moppyandroid.NetworkManager;

import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.sothree.slidinguppanel.SlidingUpPanelLayout.PanelState;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;
import jp.kshoji.javax.sound.midi.io.StandardMidiFileReader;
import jp.kshoji.javax.sound.midi.spi.MidiFileReader;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener {
    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private StatusBus statusBus;
    private MapperCollection<MidiMessage> mappers;
    private NetworkManager netManager;
    private UsbManager usbManager;
    private String currentBridgeIdentifier;
    private HashMap<String, String> spinnerHashMap;
    private HashMap<Integer, UsbDevice> devices;
    private HashMap<Integer, Boolean> permissionStatuses;
    private SlidingUpPanelLayout panelLayout;

    public static final String ACTION_USB_PERMISSION = "com.moppyandroid.USB_PERMISSION";

    // Define the receiver to process relevant intent messages
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) throws NullPointerException {
            // Ensure intent action is not null, exiting processing if so
            if (intent.getAction() == null) { return; }

            // Determine action and process accordingly
            switch (intent.getAction()) {
                case ACTION_USB_PERMISSION: {
                    onUsbPermission(intent);
                    break;
                } // End case ACTION_USB_PERMISSION
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    onUsbDeviceAttached(intent);
                    break;
                } // End case ACTION_USB_DEVICE_ATTACHED
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    onUsbDeviceDetached();
                    break;
                } // End case ACTION_USB_DEVICE_DETACHED
            } // End switch(intent.action)
        } // End onReceive method
    }; // End new BroadcastReceiver

    // Method triggered upon activity creation
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Forward to onCreate method of superclass
        super.onCreate(savedInstanceState);

        // Set the initial view
        setContentView(R.layout.activity_main);

        // Create the filter describing which intents to process and register it
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_USB_PERMISSION);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, intentFilter);

        // Configure the sliding panel and toolbar
        panelLayout = findViewById(R.id.sliding_panel_layout);
        RelativeLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        panelLayout.setDragView(R.id.toolbar_layout);
        toolbarLayout.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLayout.setPanelHeight(toolbarLayout.getMeasuredHeight());

        // Run the initializer
        init();

        // Set this activity to be called when the spinner for choosing the Moppy device has a selection event
        ((Spinner) findViewById(R.id.device_box)).setOnItemSelectedListener(this);

        // Define the listener lambdas for the load, play, and stop buttons
        findViewById(R.id.load_button).setOnClickListener((View v) -> onLoadButton());
        findViewById(R.id.play_button).setOnClickListener((View v) -> onPlayButton());
        findViewById(R.id.pause_button).setOnClickListener((View v) -> onPauseButton());
    } // End onCreate method

    // Method triggered when the back event is raised (e.g. back button pressed). Taken from AndroidSlidingUpPanel
    // demo application located at https://github.com/umano/AndroidSlidingUpPanel/tree/master/demo
    @Override
    public void onBackPressed() {
        if (panelLayout == null) { return; }
        if (panelLayout.getPanelState() == PanelState.EXPANDED || panelLayout.getPanelState() == PanelState.ANCHORED) {
            panelLayout.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        } // End if(panelLayout.state == EXPANDED || panelLayout.state == ANCHORED)
        else {
            super.onBackPressed();
        } // End if(panelLayout.state == EXPANDED || panelLayout.state == ANCHORED) {} else
    } // End onBackPressed method

    // Method triggered when a spawned activity exits
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        // Allow the superclass do process the spawned activity's result
        super.onActivityResult(requestCode, resultCode, resultData);

        // Check if the request was a load file request made by us
        if (requestCode == RequestCodes.LOAD_FILE) {
            String midiFileName;

            // Check if the request went through without issues (e.g. wasn't cancelled)
            if (resultCode == Activity.RESULT_OK) {
                // Exit method if the result data is invalid
                if (resultData == null) { return; }

                // Retrieve the URI of the selected file
                Uri midiFileUri = resultData.getData();

                // Exit method if the file's URI is invalid
                if (midiFileUri == null || midiFileUri.getPath() == null) { return; }

                // Query the URI for the name of the MIDI file
                Cursor midiFileQueryCursor = getContentResolver().query(midiFileUri, null, null, null, null);
                if (midiFileQueryCursor != null) {
                    int index = midiFileQueryCursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    midiFileQueryCursor.moveToFirst();
                    midiFileName = midiFileQueryCursor.getString(index);
                    midiFileQueryCursor.close();
                }
                else { midiFileName = ""; }

                // Attempt to load the file
                try {
                    // Get an input stream for the file and read it, raising an exception if the stream is invalid
                    InputStream stream = getContentResolver().openInputStream(midiFileUri);
                    MidiFileReader reader = new StandardMidiFileReader();
                    if (stream == null) { throw new IOException("Unable to open file"); }
                    seq.loadSequence(reader.getSequence(stream));
                } // End try {loadSequence}
                catch (FileNotFoundException e) {
                    // Print the stack trace and exit method
                    // TODO: More elegant handling
                    e.printStackTrace();
                    return;
                } // End try {loadSequence} catch(FileNotFoundException)
                catch (IOException e) {
                    // Show a message box and exit method
                    {
                        AlertDialog.Builder b = new AlertDialog.Builder(this);
                        b.setTitle("MoppyAndroid");
                        b.setCancelable(false);
                        b.setMessage("Unable to load file" + (midiFileName.equals("") ? "" : ": " + midiFileName));
                        b.setPositiveButton("OK", null);
                        b.create().show();
                    }
                    return;
                } // End try {loadSequence} catch(IOException)
                catch (InvalidMidiDataException e) {
                    // Show a message box and exit method
                    {
                        AlertDialog.Builder b = new AlertDialog.Builder(this);
                        b.setTitle("MoppyAndroid");
                        b.setCancelable(false);
                        b.setMessage((midiFileName.equals("") ? "Selected file" : midiFileName) + " is not a valid MIDI file");
                        b.setPositiveButton("OK", null);
                        b.create().show();
                    }
                    return;
                } // End try {loadSequence} catch(IOException)

                // If an exception wasn't raised (in which case control would have returned), set the song title
                setSongName(midiFileName);
            } // End if(result == OK)
        } // End if(request == LOAD_FILE)
    } // End onActivityResult method

    // Method triggered when an item in a spinner is selected
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // Ensure the event is regarding the device selection box, exiting if not so
        if (parent.getId() != R.id.device_box) { return; }

        // Check that the selected entry isn't "NONE" (index 0)
        if (position != 0) {
            // Attempt to start a connection to the selected device/bridge
            try {
                // If necessary, close the current connection
                if (currentBridgeIdentifier != null) {
                    netManager.closeBridge(currentBridgeIdentifier);
                    currentBridgeIdentifier = null;
                } // End if(currentBridgeIdentifier != null)

                // Get the bridge to connect to and do so, recording the new bridge as the current bridge if successful
                String bridgeIdentifier = spinnerHashMap.get((String) parent.getItemAtPosition(position));
                netManager.connectBridge(bridgeIdentifier);
                currentBridgeIdentifier = bridgeIdentifier; // Updated here in case connectBridge throws exception
            } // End try {connectBridge}
            catch (IOException e) {
                {
                    AlertDialog.Builder b = new AlertDialog.Builder(this);
                    b.setTitle("MoppyAndroid");
                    b.setCancelable(false);
                    b.setMessage("Unable to connect to " + parent.getItemAtPosition(position));
                    b.setPositiveButton("OK", null);
                    b.create().show();
                }

                // Set the selection to "NONE"
                parent.setSelection(0);
            } // end try {connectBridge} catch(IOException)
        } // End if(position != 0)
        else { // "NONE" selected
            // Return if there isn't a bridge to close
            if (currentBridgeIdentifier == null) { return; }

            // Attempt to close the current bridge
            try {
                netManager.closeBridge(currentBridgeIdentifier);
                currentBridgeIdentifier = null;
            } // End try {closeBridge}
            catch (IOException e) {
                // TODO: More elegant handling
                // Print out the stack trace
                e.printStackTrace();
            } // End try {closeBridge} catch(IOException e)
        } // End if(position != 0) {} else
    } // End onItemSelected method

    // Method triggered when a spinner is closed without selecting something
    public void onNothingSelected(AdapterView<?> parent) {
        // Don't care, but we need have to implement it
    } // End onNothingSelected method

    // Method triggered when a USB permission dialog completes
    private void onUsbPermission(Intent intent) {
        // Exit processing if the current device index wasn't included or deviceIndex ∉ Integer
        if (intent.getExtras() == null) { return; }
        if (!(intent.getExtras().get("deviceIndex") instanceof Integer)) { return; }

        // Retrieve the current device index from the intent and exit processing if it is null
        Integer pos = (Integer) intent.getExtras().get("deviceIndex");
        if (pos == null) { return; }

        // Ensure permission was granted
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            // Mark that the permission for this device has been granted
            permissionStatuses.replace(pos, true);
        } // End if(EXTRA_PERMISSION_GRANTED)
        else {
            // Permission not granted, give a message box and request again
            // TODO: Permission window shows over top of alert
            {
                AlertDialog.Builder b = new AlertDialog.Builder(this);
                b.setTitle("MoppyAndroid");
                b.setCancelable(false);
                b.setMessage("Permission to access all USB devices is required for operation, please grant it this time");
                b.setPositiveButton("OK", null);
                b.create().show();
            }
            requestPermission(devices.get(pos), pos);
        } // End if(EXTRA_PERMISSION_GRANTED) {} else

        // Check if all permission requests have been satisfied, and initialize objects if applicable
        if (!permissionStatuses.values().contains(false)) {
            // Check if Moppy objects are uninitialized
            if (netManager == null) {
                // TODO: More elegant handling?
                try { initMoppy(); }
                catch (Exception e) {
                    e.printStackTrace();
                } // End try {initMoppy} catch(Exception)
            } // End if(netManager == null)
            else {
                // If objects have already been initialized, refresh them
                netManager.refreshSerialDevices();
                refreshDevices();
            } // End if(permissionStatuses.allTrue)
        }
    } // End onUsbPermission method

    // Method triggered when a USB device is attached
    private void onUsbDeviceAttached(Intent intent) {
        int index;
        UsbDevice device;

        {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("USB device plugged in");
            b.setPositiveButton("OK", null);
            b.create().show();
        }

        index = devices.size();
        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        devices.put(index, device);
        permissionStatuses.put(index, false);
        requestPermission(device, index);
    } // End onUsbDeviceAttached method

    // Method triggered when a USB device is detached
    private void onUsbDeviceDetached() {
        {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("USB device unplugged");
            b.setPositiveButton("OK", null);
            b.create().show();
        }

        // Refresh the device lists
        netManager.refreshSerialDevices();
        refreshDevices();
    } // End onUsbDeviceDetached method

    // Method triggered when load button pressed
    private void onLoadButton() {
        // Trigger the open document dialog of the Android system, looking for MIDI files
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("audio/midi");
        startActivityForResult(intent, RequestCodes.LOAD_FILE);
    } // End onLoadButton method

    // Method triggered when play button pressed
    private void onPlayButton() {
        seq.play();
    } // End onPlayButton method

    // Method triggered when pause/stop button pressed
    private void onPauseButton() {
        if (seq.isPlaying()) { seq.pause(); }
        else { seq.stop(); }
    } // End onPauseButton method

    // Refresh the device box and related lists
    private void refreshDevices() {
        // Retrieve the device box spinner and clear the hashmap
        Spinner spinner = findViewById(R.id.device_box);
        spinnerHashMap.clear();

        // Ensure that the usbManager is valid
        if (usbManager == null || usbManager.getDeviceList() == null) { return; }

        // Get the list of Moppy devices from the network manager and iterate over it
        ArrayList<String> modifiedDeviceList = new ArrayList<>(netManager.getDeviceList());
        for (int i = 0; i < modifiedDeviceList.size(); i++) {
            // Start building a string to act as the device description
            StringBuilder deviceDescription = new StringBuilder();

            // Start with the string representing the current device
            deviceDescription.append(modifiedDeviceList.get(i));

            // Try to retrieve the UsbDevice object for the current device. NOTE: null if not found
            UsbDevice usbDevice = usbManager.getDeviceList().get(modifiedDeviceList.get(i));

            // If we received a UsbDevice object (not guarenteed, netManager.getDeviceList contains
            // network bridges), add information of interest to the string
            if (usbDevice != null) {
                // Attach a comma to the end, and if available add the product name
                deviceDescription.append(", ");
                if (usbDevice.getProductName() != null) {
                    deviceDescription.append(usbDevice.getProductName()).append(", ");
                } // End if(usbDevice.productName != null)

                // Add the manufacturer name and vendor/product IDs
                deviceDescription.append(usbDevice.getManufacturerName()).append(", ");
                deviceDescription.append(usbDevice.getVendorId()).append("/").append(usbDevice.getProductId()).append(", ");
            } // End if(usbDevice != null)

            // Add the device description to the hashmap for the spinner, and update our copy of the device list
            spinnerHashMap.put(deviceDescription.toString(), modifiedDeviceList.get(i));
            modifiedDeviceList.set(i, deviceDescription.toString());
        } // End for(i < modifiedDeviceList.size)

        // Create an array adapter to populate the spinner with the values from our copy of the device list
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, modifiedDeviceList);

        // Insert a "NONE" entry at the beginning of the adapter and set the dropdown style
        adapter.insert("NONE", 0);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);

        // Apply the adapter to the spinner
        spinner.setAdapter(adapter);
    } // End refreshDeviceLists method

    // Initialize non-MoppyLib objects of the class and start the Moppy initialization chain
    @SuppressLint("UseSparseArrays")
    private void init() {
        // Initialize objects
        devices = new HashMap<>();
        permissionStatuses = new HashMap<>();
        spinnerHashMap = new HashMap<>();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);

        // Initialize BridgeSerial
        BridgeSerial.init(this);

        // Request permission to access all attached USB devices (also initializes Moppy on completion)
        requestPermissionForAllDevices();
    } // End init method

    // Initialize all MoppyLib objects of the class
    // Note: Separate from init because these cannot be initialized before permission to access USB devices is awarded
    private void initMoppy() throws java.io.IOException, MidiUnavailableException {
        // Initialize Moppy objects
        statusBus = new StatusBus();
        mappers = new MapperCollection<>();
        mappers.addMapper(MIDIEventMapper.defaultMapper((byte) 0x01));
        netManager = new NetworkManager(statusBus);
        receiverSender = new MoppyMIDIReceiverSender(mappers, MessagePostProcessor.PASS_THROUGH, netManager.getPrimaryBridge());
        seq = new MoppyMIDISequencer(statusBus, receiverSender);

        // Start the network manager and refresh the device lists
        netManager.start();
        refreshDevices();
    } // End initMoppy method

    // Requests permission to access all attached USB devices
    private void requestPermissionForAllDevices() {
        // Exit method if either the USB manager or the device list is invalid
        if (usbManager == null || usbManager.getDeviceList() == null) { return; }

        // Skip requesting permission and initialize Moppy objects if there are no devices
        if (usbManager.getDeviceList().size() == 0) {
            // TODO: More elegant handling?
            try { initMoppy(); }
            catch (Exception e) {
                e.printStackTrace();
            } // End try {initMoppy} catch(Exception)
            return;
        }

        // Get the list of all USB devices and iterate over it
        // TODO: Synchronize access?
        permissionStatuses.clear();
        devices.clear();
        ArrayList<UsbDevice> usbDevices = new ArrayList<>(usbManager.getDeviceList().values());
        for (int i = 0; i < usbDevices.size(); ++i) {
            // Add an entry for the device in the permission status list, add it to the device list
            permissionStatuses.put(i, false);
            devices.put(i, usbDevices.get(i));

            // Request permission to access the device
            requestPermission(devices.get(i), i);
        } // End for(i < usbDevices.size)
    } // End requestPermissionForAllDevices method

    // Request permission to access a specific attached USB device, specifying the index of the permissionStatuses entry for it
    private void requestPermission(UsbDevice device, int index) {
        // Create an intent message for the USB permission request
        Intent intent = new Intent(ACTION_USB_PERMISSION);

        // Add the device index to the intent message and broadcast it
        intent.putExtra("deviceIndex", index);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(device, pendingIntent);
    } // End requestPermission method

    private void setSongName(String songName) {
        ((TextView) findViewById(R.id.toolbar_song_title)).setText(songName);
        ((TextView) findViewById(R.id.song_title)).setText(songName);
    } // End setSongName method

    // The code constants for requests sent with startActivityForResult
    static final public class RequestCodes {
        static public final int LOAD_FILE = 1;
    } // End RequestCodes class
} // End MainActivity class

package com.moppyandroid.main;

/*
Author: Noah Reeder, noahreederatc@gmail.com

Known bugs:
TODO: Sequencer stops on file load


Known problems:
    - Hard to use track slider in slide menu (adjust slide menu sensitivity?)
    - Must connect to device, disconnect, and connect again for connection to work... sometimes
    - To fix the app not shutting down properly, I eliminated being able to play songs while the app is
        minimized or the device is locked. This can be reintroduced by switching to UI/Service model


Features to implement:
    - MIDI I/O
    - Playlist
    - Multi-device selection


Scope creep is getting really bad... let's make a list of nice-to-have-but-slightly-out-of-scope features:
    - Sigh... Another port of MIDISplitter
    - Visualization
    - Media control notification. Requires project restructure into UI/Service model
        • Solves most issues with application lifetime, but exaggerates issues with device disconnection
        • Use a foreground service to run sequencer and interact with Intents/StatusConsumer updates


Regexes:
    Tip: When find box is open, press CTRL+ALT+F or filter button to be able to exclude comments

    Find all single-line braces without proper spacing:
        \{[^ \n}]
        [^ \n{]}

    Find all closing braces without a comment about what they close
        (^[^{\n]*})[^//\n]*$

    Find all closing parentheses on a new line without a comment about what they close
        ^[ \t^]*\)[^//\n]*$

 */


import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.os.Bundle;

import com.moppyandroid.BridgeSerial;
import com.moppyandroid.MoppyMIDISequencer;
import com.moppyandroid.com.moppy.core.events.mapper.MIDIEventMapper;
import com.moppyandroid.com.moppy.core.events.mapper.MapperCollection;
import com.moppyandroid.com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppyandroid.com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppyandroid.com.moppy.core.status.StatusBus;
import com.moppyandroid.com.moppy.core.status.StatusConsumer;
import com.moppyandroid.com.moppy.core.status.StatusUpdate;

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
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener, StatusConsumer, SeekBar.OnSeekBarChangeListener {
    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private StatusBus statusBus;
    private MapperCollection<MidiMessage> mappers;
    private MoppyUsbManager netManager;
    private UsbManager usbManager;
    private String currentBridgeIdentifier;
    private HashMap<String, String> spinnerHashMap;
    private HashMap<Integer, UsbDevice> devices;
    private HashMap<Integer, Boolean> permissionStatuses;
    private SlidingUpPanelLayout panelLayout;
    private SeekBar songSlider;
    private Timer songProgressTimer;
    private SongTimerTask songTimerTask;
    private Handler uiHandler;
    private boolean sequenceLoaded;
    private boolean playAfterTrackingFinished;

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
                    onUsbPermissionIntent(intent);
                    break;
                } // End case ACTION_USB_PERMISSION
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    onUsbDeviceAttachedIntent(intent);
                    break;
                } // End case ACTION_USB_DEVICE_ATTACHED
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    onUsbDeviceDetachedIntent();
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

        // Set the initial view and disable the pause and play buttons
        setContentView(R.layout.activity_main);
        songSlider = findViewById(R.id.song_slider);
        enablePlayButton(false);
        enableSongSlider(false);
        enablePauseButton(false);

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

        // Set this activity to be called when the USB device spinner or the song slider have a selection event
        ((Spinner) findViewById(R.id.device_box)).setOnItemSelectedListener(this);
        ((SeekBar) findViewById(R.id.song_slider)).setOnSeekBarChangeListener(this);

        // Define the listener lambdas for the load, play, and stop buttons
        findViewById(R.id.load_button).setOnClickListener((View v) -> onLoadButton());
        findViewById(R.id.play_button).setOnClickListener((View v) -> onPlayButton());
        findViewById(R.id.pause_button).setOnClickListener((View v) -> onPauseButton());
    } // End onCreate method

    // Method triggered when the activity is obscured by another activity (e.g. another app or file load dialog)
    @Override
    protected void onStop() {
        // Disconnect the bridge, but don't reset the current bridge identifier
        closeBridge(false);
        super.onStop();
    } // End onStop method

    // Method triggered when the activity is brought back into focus after an onStop call
    @Override
    protected void onRestart() {
        if (currentBridgeIdentifier != null) {
            try {
                netManager.connectBridge(currentBridgeIdentifier);
                // Enable the stop and play buttons as necessary
                enablePauseButton(true);
                if (sequenceLoaded) {
                    enablePlayButton(true);
                    enableSongSlider(true);
                } // End if(sequenceLoaded)
            } // End try {connectBridge}
            catch (IOException e) {
                Spinner deviceBox = findViewById(R.id.device_box);
                showMessageDialog("Unable to connect to " + deviceBox.getSelectedItem(), null);

                // Set the selection to "NONE"
                deviceBox.setSelection(0);
            } // end try {connectBridge} catch(IOException)
        } // End if(currentBridgeIdentifier != null)
        super.onRestart();
    } // End onResume method

    // Method triggered when the app is destroyed (e.g. force killed, finalize called, killed to reclaim memory)
    @Override
    protected void onDestroy() {
        if (currentBridgeIdentifier != null && netManager.isConnected(currentBridgeIdentifier)) {
            closeBridge(false);
        } // End if(currentBridgeIdentifier.isConnected)
        super.onDestroy();
    } // End onDestroy method

    // Method triggered when the back event is raised (e.g. back button pressed). Taken from AndroidSlidingUpPanel
    // demo application located at https://github.com/umano/AndroidSlidingUpPanel/tree/master/demo
    @Override
    public void onBackPressed() {
        // If the sliding menu is defined and open collapse it, otherwise do the default action
        if (panelLayout == null) { super.onBackPressed(); }
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
                } // End if(midiFileQueryCursor != null)
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
                    // Show a message box and exit method
                    showMessageDialog(
                            "File" + (midiFileName.equals("") ? "" : " " + midiFileName) + " not found",
                            null
                    ); // End showMessageDialog call
                    return;
                } // End try {loadSequence} catch(FileNotFoundException)
                catch (IOException e) {
                    // Show a message box and exit method
                    showMessageDialog(
                            "Unable to load file" + (midiFileName.equals("") ? "" : ": " + midiFileName),
                            null
                    ); // End showMessageDialog call
                    return;
                } // End try {loadSequence} catch(IOException)
                catch (InvalidMidiDataException e) {
                    // Show a message box and exit method
                    showMessageDialog(
                            (midiFileName.equals("") ? "Selected file" : midiFileName) + " is not a valid MIDI file",
                            null
                    ); // End showMessageDialog call
                    return;
                } // End try {loadSequence} catch(IOException)

                // If an exception wasn't raised (in which case control would have returned), set the song title
                setSongName(midiFileName);

                // Mark that a sequence has been loaded, and enable the play button and song slider if necessary
                sequenceLoaded = true;
                if (currentBridgeIdentifier != null && netManager.isConnected(currentBridgeIdentifier) && !songSlider.isEnabled()) {
                    enablePlayButton(true);
                    enableSongSlider(true);
                } // End if(currentBridgeIdentifier.isConnected && !songSlider.enabled)
            } // End if(result == OK)
        } // End if(request == LOAD_FILE)
    } // End onActivityResult method

    // Method triggered when the song slider has been used
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == songSlider) {
            if (fromUser) {
                seq.setSecondsPosition(progress);
                updateSongPositionText();
            } // End if(fromUser)
        } // End if(seekBar == songSlider)
    } // End onProgressChanged method

    // Method triggered when the song slider begins to be moved
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        if (seekBar == songSlider) {
            // Pause the sequencer so there isn't any garbled (possibly damaging?) notes as the slider moves
            if (seq.isPlaying()) { seq.pause(); }
        } // End if(seekBar == songSlider)
    } // End onStartTrackingTouch method

    // Method triggered when the song time slider finishes being moved
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (seekBar == songSlider) {
            if (playAfterTrackingFinished) { seq.play(); }
        } // End if(seekBar == songSlider)
    } // End onStopTrackingTouch method

    // Method triggered when the sequencer sends a status update
    @Override
    public void receiveUpdate(StatusUpdate update) {
        switch (update.getType()) {
            case SEQUENCE_LOAD: {
                // Start the timer, shutting it down first if it is still running, and ensure the task is paused
                if (songTimerTask != null) { songTimerTask.cancel(); }
                if (songProgressTimer != null) { songProgressTimer.cancel(); }
                songTimerTask = new SongTimerTask();
                songProgressTimer = new Timer();
                songTimerTask.pause();
                // Note: The timer tick is 500 so that if the timer's ticks are slightly offset from
                // the sequencer's ticks the progress bar will still be pretty accurate
                songProgressTimer.schedule(songTimerTask, 0, 500);
                long songLength = seq.getSecondsLength();
                songSlider.setMax(songLength < Integer.MAX_VALUE ? (int) songLength : Integer.MAX_VALUE);

                // Set the song time text
                StringBuilder timeTextBuilder = new StringBuilder();
                String temp;
                timeTextBuilder.append("0:00:00/");
                timeTextBuilder.append(songLength / 3600);
                timeTextBuilder.append(":");
                temp = Long.toString((songLength % 3600) / 60);
                timeTextBuilder.append(("00" + temp).substring(temp.length())).append(":");
                temp = Long.toString(songLength % 60);
                timeTextBuilder.append(("00" + temp).substring(temp.length()));
                ((TextView) findViewById(R.id.song_time_text)).setText(timeTextBuilder.toString());

                // If necessary, unpause the timer task
                if (seq.isPlaying()) { songTimerTask.unpause(); }
                break;
            } // End SEQUENCE_LOAD case
            case SEQUENCE_START: {
                songTimerTask.unpause();
                break;
            } // End SEQUENCE_START case
            case SEQUENCE_PAUSE: {
                if (songTimerTask != null) { songTimerTask.pause(); }
                break;
            } // End SEQUENCE_PAUSE case
            case SEQUENCE_END: {
                songTimerTask.pause();
                ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
                update.getData().ifPresent(reset -> {
                    if ((boolean) reset) { updateSongProgress(); }
                }); // End ifPresent lambda
                break;
            } // End SEQUENCE_PAUSE/SEQUENCE_END case
        } // End switch(update)
    } // End receiveUpdate method

    // Method triggered when an item in a spinner is selected
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // Ensure the event is regarding the device selection box, exiting if not so
        if (parent.getId() != R.id.device_box) { return; }

        // Check that the selected entry isn't "NONE" (index 0)
        if (position != 0) {
            // Attempt to start a connection to the selected device/bridge
            try {
                // If necessary, close the current connection
                closeBridge(true);

                // Get the bridge to connect to and do so, recording the new bridge as the current bridge if successful
                String bridgeIdentifier = spinnerHashMap.get(parent.getItemAtPosition(position));
                netManager.connectBridge(bridgeIdentifier);
                currentBridgeIdentifier = bridgeIdentifier; // Updated here in case connectBridge throws exception

                // Enable the stop and play buttons as necessary
                enablePauseButton(true);
                if (sequenceLoaded) {
                    enablePlayButton(true);
                    enableSongSlider(true);
                } // End if(sequenceLoaded)
            } // End try {connectBridge}
            catch (IOException | BridgeSerial.UnableToObtainDeviceException e) {
                showMessageDialog("Unable to connect to " + parent.getItemAtPosition(position), null);
                Log.e(this.getClass().getName(), "Unable to connect to device", e);

                // Set the selection to "NONE"
                parent.setSelection(0);
            } // end try {connectBridge} catch(IOException)
        } // End if(position != 0)
        else { // "NONE" selected
            // Set the buttons to be disabled
            enablePlayButton(false);
            enableSongSlider(false);
            enablePauseButton(false);

            closeBridge(true);
        } // End if(position != 0) {} else
    } // End onItemSelected method

    // Method triggered when a spinner is closed without selecting something
    public void onNothingSelected(AdapterView<?> parent) {
        // Don't care, but we need to implement it
    } // End onNothingSelected method

    // Method triggered when a USB permission dialog completes
    private void onUsbPermissionIntent(Intent intent) {
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
            showMessageDialog(
                    "Permission to access all of the attached USB devices is required for operation, please grant it this time",
                    (dialog, which) -> requestPermission(devices.get(pos), pos)
            ); // End showMessageDialog call
        } // End if(EXTRA_PERMISSION_GRANTED) {} else

        // Check if all permission requests have been satisfied, and initialize objects if applicable
        if (!permissionStatuses.containsValue(false)) {
            // Check if Moppy objects are uninitialized
            if (netManager == null) { initMoppy(); }
            else {
                // If objects have already been initialized, refresh them
                netManager.refreshDeviceList();
                refreshDevices();
            } // End if(netManager == null) {} else
        } // End if(permissionStatuses.allTrue)
        else {
            if (pos < permissionStatuses.size() - 1) {
                requestPermission(devices.get(pos + 1), pos + 1);
            } // End if(pos < permissionStatuses.lastIndex)
        } // End if(permissionStatuses.allTrue)
    } // End onUsbPermissionIntent method

    // Method triggered when a USB device is attached
    private void onUsbDeviceAttachedIntent(Intent intent) {
        int index;
        UsbDevice device;

        index = devices.size();
        device = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        devices.put(index, device);
        permissionStatuses.put(index, false);
        requestPermission(device, index);
    } // End onUsbDeviceAttachedIntent method

    // Method triggered when a USB device is detached
    private void onUsbDeviceDetachedIntent() {
        // Refresh the netManger device lists, waiting for the message box to be acknowledged before refreshing ours
        netManager.refreshDeviceList();
        currentBridgeIdentifier = null;
        refreshDevices();
    } // End onUsbDeviceDetachedIntent method

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
        ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_pause);
        playAfterTrackingFinished = true;
    } // End onPlayButton method

    // Method triggered when pause/stop button pressed
    private void onPauseButton() {
        playAfterTrackingFinished = false;
        if (seq.isPlaying()) {
            seq.pause();
            ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
        } // End if(seq.isPlaying)
        else { seq.stop(); }
    } // End onPauseButton method

    // Initialize non-MoppyLib objects of the class and start the Moppy initialization chain
    @SuppressLint("UseSparseArrays")
    private void init() {
        // Initialize objects
        devices = new HashMap<>();
        permissionStatuses = new HashMap<>();
        spinnerHashMap = new HashMap<>();
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
        uiHandler = new Handler();
        sequenceLoaded = false;

        // Initialize BridgeSerial
        BridgeSerial.init(this);

        // Request permission to access all attached USB devices (also initializes Moppy on completion)
        requestPermissionForAllDevices();
    } // End init method

    // Initialize all MoppyLib objects of the class
    // Note: Separate from init because these cannot be initialized before permission to access USB devices is awarded
    private void initMoppy() {
        // Initialize Moppy objects
        statusBus = new StatusBus();
        mappers = new MapperCollection<>();
        mappers.addMapper(MIDIEventMapper.defaultMapper((byte) 0x01));
        netManager = new MoppyUsbManager(statusBus);
        try {
            receiverSender = new MoppyMIDIReceiverSender(mappers, MessagePostProcessor.PASS_THROUGH, netManager.getPrimaryBridge());
        } // End try {new MoppyMIDIReceiverSender}
        catch (IOException ignored) {} // Not actually generated, method signature outdated
        try { seq = new MoppyMIDISequencer(statusBus, receiverSender); }
        catch (MidiUnavailableException e) {
            // Log the unrecoverable error, throw up a message box, and exit the application with a non-zero value
            Log.e("com.moppyandroid.main.MainActivity", "Unable to construct MoppyMIDISequencer", e);
            showMessageDialog(
                    "Unrecoverable error: Unable to get MIDI resources for Moppy initialization. exiting",
                    (dialog, which) -> System.exit(1)
            ); // End showMessageDialog call
        } // End try {new MoppyMidiSequencer} catch(MidiUnavailableException)

        // Register this class as a consumer of the status updates generated by the MoppyMIDISequencer
        statusBus.registerConsumer(this);

        // Refresh the device lists
        refreshDevices();
    } // End initMoppy method

    // Refresh the device box and related lists
    private void refreshDevices() {
        String previousSelection;

        // Note: If this was called as a result of a device detachment then the netManager refresh
        // would have handled the device disconnection

        // Retrieve the device box spinner, save the current selection, and clear the hashmap
        Spinner spinner = findViewById(R.id.device_box);
        previousSelection = (String) spinner.getSelectedItem();
        spinnerHashMap.clear();

        // Ensure that the usbManager is valid
        if (usbManager == null || usbManager.getDeviceList() == null) { return; }

        // Get the list of Moppy devices from the network manager and iterate over it
        ArrayList<String> modifiedDeviceList = new ArrayList<>(netManager.getDevices());
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
                if (usbDevice.getManufacturerName() != null) {
                    deviceDescription.append(usbDevice.getManufacturerName()).append(", ");
                } // End if(usbDevice.manufacturerName != null)
                deviceDescription.append(Integer.toHexString(usbDevice.getVendorId())).append("/");
                deviceDescription.append(Integer.toHexString(usbDevice.getProductId()));
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

        // If there was no previous selection or the previous selection was "NONE", finish processing here
        if (previousSelection == null || previousSelection.equals("NONE")) { return; }

        // Attempt to get the new index of the previously selected item, throwing up a message box and returning if not found
        // Note: spinner.setSelection will trigger onItemSelected, so we don't need to connect here
        int index = adapter.getPosition(previousSelection);
        if (index == -1) {
            showMessageDialog("The previously selected device is no longer available", null);
            // Replicate a pause event if the device was being played to
            if (seq.isPlaying()) { onPauseButton(); }
            return;
        } // End if(index == -1)
        spinner.setSelection(index);
    } // End refreshDeviceLists method

    // Requests permission to access all attached USB devices
    private void requestPermissionForAllDevices() {
        // Exit method if either the USB manager or the device list is invalid
        if (usbManager == null || usbManager.getDeviceList() == null) { return; }

        // Skip requesting permission and initialize Moppy objects if there are no devices
        if (usbManager.getDeviceList().size() == 0) {
            initMoppy();
            return;
        } // End if(usbManager.getDeviceList.size == 0)

        // Get the list of all USB devices and iterate over it
        // Note: synchronization not needed because this is guaranteed to be done on the UI thread
        permissionStatuses.clear();
        devices.clear();
        ArrayList<UsbDevice> usbDevices = new ArrayList<>(usbManager.getDeviceList().values());
        for (int i = 0; i < usbDevices.size(); ++i) {
            // Add an entry for the device in the permission status list, add it to the device list
            if (!usbManager.hasPermission(usbDevices.get(i))) {
                permissionStatuses.put(i, false);
                devices.put(i, usbDevices.get(i));
            } // End if(usbDevices.get(i).needsPermission)

            // Request permission to access the device
            //requestPermission(devices.get(i), i);
        } // End for(i < usbDevices.size)

        if (devices.size() != 0) { requestPermission(devices.get(0), 0); }
        else { initMoppy(); }

    } // End requestPermissionForAllDevices method

    // Closes the currently connected bridge, resetting currentBridgeIdentifier to null if passed true
    private void closeBridge(boolean resetIdentifier) {
        // Return if there isn't a bridge to close
        if (currentBridgeIdentifier == null || !netManager.isConnected(currentBridgeIdentifier)) {
            return;
        } // End if(currentBridgeIdentifier.notConnected)

        // Attempt to close the current bridge
        try {
            netManager.closeBridge(currentBridgeIdentifier);
            if (resetIdentifier) { currentBridgeIdentifier = null; }
        } // End try {closeBridge}
        catch (IOException e) {
            // In the words of MoppyLib's author when they deal with this exception, "There's not
            // much we can do if it fails to close (it's probably already closed). Just log it and move on"
            Log.w("com.moppyandroid.main.MainActivity", "Unable to close bridge", e);
        } // End try {closeBridge} catch(IOException e)
    } // End closeBridge method

    // Request permission to access a specific attached USB device, specifying the index of the permissionStatuses entry for it
    private void requestPermission(UsbDevice device, int index) {
        // Create an intent message for the USB permission request
        Intent intent = new Intent(ACTION_USB_PERMISSION);

        // Add the device index to the intent message and broadcast it
        intent.putExtra("deviceIndex", index);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        usbManager.requestPermission(device, pendingIntent);
    } // End requestPermission method

    // Enables/disables the play button
    private void enablePlayButton(boolean enabled) {
        ImageButton playButton = findViewById(R.id.play_button);
        if (enabled) {
            playButton.setEnabled(true);
            playButton.setAlpha(1f);
        } // End if(enabled)
        else {
            playButton.setEnabled(false);
            playButton.setAlpha(0.5f);
        } // End if(enabled) {} else
    } // End enablePlayButton method

    // Enables/disables the pause/stop button
    private void enablePauseButton(boolean enabled) {
        ImageButton pauseButton = findViewById(R.id.pause_button);
        if (enabled) {
            pauseButton.setEnabled(true);
            pauseButton.setAlpha(1f);
        } // End if(enabled)
        else {
            pauseButton.setEnabled(false);
            pauseButton.setAlpha(0.5f);
        } // End if(enabled) {} else
    } // End enablePauseButton method

    // Enables/disables the song position slider
    private void enableSongSlider(boolean enabled) {
        if (enabled) {
            songSlider.setEnabled(true);
            songSlider.setAlpha(1f);
        } // End if(enabled)
        else {
            songSlider.setEnabled(false);
            songSlider.setAlpha(0.5f);
        } // End if(enabled) {} else
    } // End enableSongSlider method

    // Assigns the passed string as the name of the loaded song
    private void setSongName(String songName) {
        ((TextView) findViewById(R.id.toolbar_song_title)).setText(songName);
        ((TextView) findViewById(R.id.song_title)).setText(songName);
    } // End setSongName method

    // Updates the song position slider and textual counter
    private void updateSongProgress() {
        long currentTime = seq.getSecondsPosition();
        if (currentTime < 0 || currentTime > Integer.MAX_VALUE) {
            songSlider.setProgress(songSlider.getMax());
        } // End if(currentTime < 1 || currentTime > INT_MAX)
        else { songSlider.setProgress((int) currentTime); }

        updateSongPositionText();
    } // End updateSongProgress method

    // Updates the text label representing the song position (e.g. 0:02:24/0:03:00)
    private void updateSongPositionText() {
        long currentTime = seq.getSecondsPosition();

        // Update the textual view of the current song time, retaining the song length portion
        // Note: A substring is taken for minutes and seconds to ensure that there is 2 digits
        TextView timeTextView = findViewById(R.id.song_time_text);
        StringBuilder timeTextBuilder = new StringBuilder();
        String temp;
        timeTextBuilder.append(currentTime / 3600);
        timeTextBuilder.append(":");
        temp = Long.toString((currentTime % 3600) / 60);
        timeTextBuilder.append(("00" + temp).substring(temp.length())).append(":");
        temp = Long.toString(currentTime % 60);
        timeTextBuilder.append(("00" + temp).substring(temp.length()));
        timeTextBuilder.append(timeTextView.getText().subSequence(
                timeTextView.getText().toString().indexOf("/"),
                timeTextView.getText().length()
        )); // End subSequence call

        // Ensure the text update is done on the UI thread (since this method is likely called from a TimerTask's thread)
        uiHandler.post(() -> timeTextView.setText(timeTextBuilder.toString()));
    } // End updateSongPositionText

    // Shows a non-cancellable alert dialog with only an OK button
    private void showMessageDialog(String message, DialogInterface.OnClickListener listener) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("MoppyAndroid");
        builder.setCancelable(false);
        builder.setMessage(message);
        builder.setPositiveButton("OK", listener);
        builder.create().show();
    } // End showMessageDialog method

    // Class used to update the song position slider and text every timer tick
    protected class SongTimerTask extends TimerTask {
        private boolean notPaused;

        public SongTimerTask() { notPaused = true; }

        public SongTimerTask(boolean startPaused) { notPaused = !startPaused; }

        @Override
        public void run() {
            if (notPaused) { updateSongProgress(); }
        } // End run method

        public void pause() { notPaused = false; }

        public void unpause() { notPaused = true; }
    } // End SongTimerTask class

    // The code constants for requests sent with startActivityForResult
    static final public class RequestCodes {
        static public final int LOAD_FILE = 1;
    } // End RequestCodes class
} // End MainActivity class

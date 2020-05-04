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
    - Playlist/file queue
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
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.net.Uri;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.PlaybackStateCompat;
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

import com.moppy.core.comms.bridge.BridgeSerial;

import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.sothree.slidinguppanel.SlidingUpPanelLayout.PanelState;

import jp.kshoji.javax.sound.midi.io.StandardMidiFileReader;
import jp.kshoji.javax.sound.midi.spi.MidiFileReader;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener, SeekBar.OnSeekBarChangeListener {
    private String currentBridgeIdentifier;
    private HashMap<String, String> spinnerHashMap;
    private HashMap<Integer, UsbDevice> devices;
    private HashMap<Integer, Boolean> permissionStatuses;
    private SlidingUpPanelLayout panelLayout;
    private SeekBar songSlider;
    private SongTimerTask songTimerTask;
    private Handler uiHandler;
    private boolean movingSlider;
    private boolean initialized;
    private MediaBrowserCompat mediaBrowser;
    private MediaControllerCallback mediaControllerCallback;
    private MediaControllerCompat.TransportControls transportControls;
    private PlaybackStateCompat playbackState;
    private MediaMetadataCompat metadata;

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
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: { // TODO: Move to service
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
        super.onCreate(savedInstanceState);

        devices = new HashMap<>();
        permissionStatuses = new HashMap<>();
        spinnerHashMap = new HashMap<>();
        uiHandler = new Handler();
        movingSlider = false;
        initialized = false;

        // Set the initial view and disable the pause and play buttons
        setContentView(R.layout.activity_main);
        songSlider = findViewById(R.id.song_slider);
        setControlState(true);

        // Start the media service
        // Note: This is in addition to binding/unbinding in onStart and onStop to allow the service
        //      to run when the activity is minimized
        startForegroundService(new Intent(this, MoppyMediaService.class));

        mediaBrowser = new MediaBrowserCompat(
                this,
                new ComponentName(this, MoppyMediaService.class),
                new BrowserConnectionCallback(),
                null
        );
        mediaControllerCallback = new MediaControllerCallback();

        // Configure the sliding panel and toolbar
        panelLayout = findViewById(R.id.sliding_panel_layout);
        RelativeLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        panelLayout.setDragView(R.id.toolbar_layout);
        toolbarLayout.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLayout.setPanelHeight(toolbarLayout.getMeasuredHeight());

        // Set this activity to be called when the USB device spinner or the song slider have a selection event
        ((Spinner) findViewById(R.id.device_box)).setOnItemSelectedListener(this);
        ((SeekBar) findViewById(R.id.song_slider)).setOnSeekBarChangeListener(this);

        // Define the listener lambdas for the load, play, and stop buttons
        findViewById(R.id.load_button).setOnClickListener((View v) -> onLoadButton());
        findViewById(R.id.play_button).setOnClickListener((View v) -> onPlayButton());
        findViewById(R.id.pause_button).setOnClickListener((View v) -> onPauseButton());

        mediaBrowser.connect();
    } // End onCreate method

    /**
     * Method triggered when the app is destroyed (e.g. force killed, finalize called, killed to reclaim memory).
     */
    @Override
    protected void onDestroy() {
        if (MediaControllerCompat.getMediaController(this) != null) {
            MediaControllerCompat.getMediaController(this).unregisterCallback(mediaControllerCallback);
        }
        mediaBrowser.disconnect();

        if (initialized) { unregisterReceiver(broadcastReceiver); }
        super.onDestroy();
    } // End onDestroy method


    /**
     * Method triggered when the back event is raised (e.g. back button pressed). Taken from AndroidSlidingUpPanel
     * demo application located at https://github.com/umano/AndroidSlidingUpPanel/tree/master/demo.
     */
    @Override
    public void onBackPressed() { // TODO: Quits without disconnecting devices, look into populating current device on startup
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
                    //seq.loadSequence(reader.getSequence(stream));
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
                // End try {loadSequence} catch(IOException)

                // If an exception wasn't raised (in which case control would have returned), set the song title
                setSongName(midiFileName);

                // Mark that a sequence has been loaded, and enable the play button and song slider if necessary
                if (!songSlider.isEnabled() && metadata != null) {
                    setControlState(false);
                } // End if(!songSlider.enabled && fileLoaded)
            } // End if(result == OK)
        } // End if(request == LOAD_FILE)

    } // End onActivityResult method

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == RequestCodes.READ_STORAGE) {
            mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_INIT_LIBRARY, null, null);
        }
    } // End onRequestPermissionsResult method

    // Method triggered when the song slider has been used
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == songSlider) {
            if (fromUser && !movingSlider) {
                // Seek to the new position, which will update the playback state and therefore the text
                transportControls.seekTo(progress);
            } // End if(fromUser)
        } // End if(seekBar == songSlider)
    } // End onProgressChanged method

    // Method triggered when the song slider begins to be moved
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        if (seekBar == songSlider) {
            movingSlider = true;
            songTimerTask.pause();
        } // End if(seekBar == songSlider)
    } // End onStartTrackingTouch method

    // Method triggered when the song time slider finishes being moved
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (seekBar == songSlider) {
            movingSlider = false;
            onProgressChanged(seekBar, seekBar.getProgress(), true);
            songTimerTask.unpause(); // After onProgressChanged so time calculation doesn't overwrite seek
        } // End if(seekBar == songSlider)
    } // End onStopTrackingTouch method

    // Method triggered when an item in a spinner is selected
    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
        // Ensure the event is regarding the device selection box, exiting if not so
        if (parent.getId() != R.id.device_box) { return; }

        // Check that the selected entry isn't "NONE" (index 0)
        if (position != 0) {
            // If necessary, close the current connection
            closeBridge(true);

            // Get the bridge to connect to and do so, recording the new bridge as the current bridge if successful
            String bridgeIdentifier = spinnerHashMap.get(parent.getItemAtPosition(position));
            Bundle connectBundle = new Bundle();
            connectBundle.putString(MoppyMediaService.EXTRA_PORT_NAME, bridgeIdentifier);
            mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_ADD_DEVICE, connectBundle, new MediaBrowserCompat.CustomActionCallback() {
                @Override
                public void onResult(String action, Bundle extras, Bundle resultData) {
                    currentBridgeIdentifier = bridgeIdentifier;
                    // Enable the stop and play buttons as necessary
                    setControlState(false);
                    super.onResult(action, extras, resultData);
                } // End ACTION_ADD_DEVICE.onResult method

                @Override
                public void onError(String action, Bundle extras, Bundle data) {
                    parent.setSelection(0); // Select "NONE"
                    Log.e(
                            MainActivity.class.getName() + "->onItemSelected",
                            "Unable to connect to device",
                            (Throwable) (data.getSerializable(MoppyMediaService.EXTRA_EXCEPTION)));
                    showMessageDialog("Unable to connect to " + data.getString(MoppyMediaService.EXTRA_PORT_NAME), null);

                    super.onError(action, extras, data);
                } // End ACTION_ADD_DEVICE.onError method
            }); // End anonymous CustomActionCallback
        } // End if(position != 0)
        else { // "NONE" selected
            // Set the buttons to be disabled
            setControlState(true);

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
            requestDevicesRefresh();
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
        requestDevicesRefresh();
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
    private void onPlayButton() { transportControls.play(); }

    // Method triggered when pause/stop button pressed
    private void onPauseButton() {
        if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            transportControls.pause();
        }
        else { transportControls.stop(); }
    } // End onPauseButton method

    // Initialize non-MoppyLib objects of the class and start the Moppy initialization chain
    @SuppressLint("UseSparseArrays")
    private void init() {
        // Create the filter describing which Android system intents to process and register it
        IntentFilter globalIntentFilter = new IntentFilter();
        globalIntentFilter.addAction(ACTION_USB_PERMISSION);
        globalIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        globalIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, globalIntentFilter);

        // Initialize BridgeSerial
        BridgeSerial.init(this);
        MidiLibrary.requestStoragePermission(this);

        // Request permission to access all attached USB devices (also initializes Moppy on completion)
        requestPermissionForAllDevices();

        Timer songProgressTimer = new Timer();
        songTimerTask = new SongTimerTask();
        songTimerTask.pause();
        // Note: The timer tick is 500 so that if the timer's ticks are slightly offset from
        // the sequencer's ticks the progress bar will still be pretty accurate
        songProgressTimer.schedule(songTimerTask, 0, 500);

        initialized = true;
    } // End init method

    private void requestDevicesRefresh() {
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_REFRESH_DEVICES, null, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            @SuppressWarnings("unchecked cast")
            public void onResult(String action, Bundle extras, Bundle resultData) {
                updateDevicesUI((ArrayList<ArrayList<String>>) resultData.getSerializable(MoppyMediaService.EXTRA_DEVICE_INFOS));
                super.onResult(action, extras, resultData);
            }
        });
    }

    private void updateDevicesUI() {
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_GET_DEVICES, null, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            @SuppressWarnings("unchecked cast")
            public void onResult(String action, Bundle extras, Bundle resultData) {
                updateDevicesUI((ArrayList<ArrayList<String>>) resultData.getSerializable(MoppyMediaService.EXTRA_DEVICE_INFOS));
                super.onResult(action, extras, resultData);
            }
        });
    }

    // Refresh the device box and related lists
    private void updateDevicesUI(ArrayList<ArrayList<String>> deviceInfos) {
        if (deviceInfos == null) { return; }

        // Note: If this was called as a result of a device detachment then the service would have
        // handled the device disconnection

        // Retrieve the device box spinner, save the current selection, and clear the hashmap
        Spinner spinner = findViewById(R.id.device_box);
        String previousSelection = (String) spinner.getSelectedItem();
        spinnerHashMap.clear();

        // Get the list of Moppy devices from the network manager and iterate over it
        ArrayList<String> deviceDescriptors = new ArrayList<>();
        for (ArrayList<String> currentDeviceInfo : deviceInfos) {
            if (currentDeviceInfo == null || currentDeviceInfo.get(0) == null) { continue; }
            String portName = currentDeviceInfo.get(0);
            String productName = currentDeviceInfo.get(1);
            String manufacturer = currentDeviceInfo.get(2);
            String vendorId = currentDeviceInfo.get(3);
            String productId = currentDeviceInfo.get(4);

            // Start building a string to act as the device description
            StringBuilder deviceDescription = new StringBuilder();
            deviceDescription.append(portName);

            // Since an int cannot be null, the only way the vendor ID string can be null is if the UsbDevice didn't exist
            if (vendorId != null) {
                // Attach a comma to the end, and if available add the product name
                deviceDescription.append(", ");
                if (productName != null) { deviceDescription.append(productName).append(", "); }
                else { deviceDescription.append("unknown product, "); }
                if (manufacturer != null) { deviceDescription.append(manufacturer).append(", "); }
                else { deviceDescription.append("unknown manufacturer, "); }
                deviceDescription.append(vendorId).append("/").append(productId);
            } // End if(usbDevice != null)

            // Add the device description to the hashmap for the spinner, and update our copy of the device list
            spinnerHashMap.put(deviceDescription.toString(), portName);
            deviceDescriptors.add(deviceDescription.toString());
        } // End for(i < deviceInfos.size)

        // Create an array adapter to populate the spinner with the values from our copy of the device list
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, deviceDescriptors);

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
            currentBridgeIdentifier = null;
            // Replicate a pause event if the device was being played to
            if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) { onPauseButton(); }
            spinner.setSelection(0); // Manual selection triggers onItemSelected and the service gets informed about disconnection
            return;
        } // End if(index == -1)
        spinner.setSelection(index);
    } // End refreshDevices method

    // Requests permission to access all attached USB devices
    // TODO: Switch to only requesting permission when a device is to be connected
    private void requestPermissionForAllDevices() {
        // Exit method if either the USB manager or the device list is invalid
        UsbManager usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null || usbManager.getDeviceList() == null) { return; }

        // Skip requesting permission and initialize Moppy objects if there are no devices
        if (usbManager.getDeviceList().size() == 0) {
            updateDevicesUI();
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
        else { updateDevicesUI(); }
    } // End requestPermissionForAllDevices method

    // Closes the currently connected bridge, resetting currentBridgeIdentifier to null if passed true
    private void closeBridge(boolean resetIdentifier) {
        // Return if there isn't a bridge to close
        if (currentBridgeIdentifier == null) {
            return;
        }

        // Send intent to close the current bridge
        Bundle disconnectBundle = new Bundle();
        disconnectBundle.putString(MoppyMediaService.EXTRA_PORT_NAME, currentBridgeIdentifier);
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_REMOVE_DEVICE, disconnectBundle, null);
        if (resetIdentifier) { currentBridgeIdentifier = null; }
    } // End closeBridge method

    // Request permission to access a specific attached USB device, specifying the index of the permissionStatuses entry for it
    private void requestPermission(UsbDevice device, int index) {
        // Create an intent message for the USB permission request
        Intent intent = new Intent(ACTION_USB_PERMISSION);

        // Add the device index to the intent message and broadcast it
        intent.putExtra("deviceIndex", index);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT);
        ((UsbManager) getSystemService(Context.USB_SERVICE)).requestPermission(device, pendingIntent);
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

    private void setControlState(boolean forceDisable) {
        if (forceDisable) {
            enablePauseButton(false);
            enablePlayButton(false);
            enableSongSlider(false);
        } // End if(forceDisable)
        else {
            enablePauseButton(true);
            if (metadata != null) {
                enablePlayButton(true);
                enableSongSlider(true);
            } // End if(sequenceLoaded)
            else {
                enablePlayButton(false);
                enableSongSlider(false);
            } // End if(sequenceLoaded) {} else
        } // End if(forceDisable) {} else
    } // End enableControls method

    // Assigns the passed string as the name of the loaded song
    private void setSongName(String songName) {
        ((TextView) findViewById(R.id.toolbar_song_title)).setText(songName);
        ((TextView) findViewById(R.id.song_title)).setText(songName);
    } // End setSongName method

    // Updates the song position slider and textual counter
    private void updateSongProgress() {
        long time = playbackState.getPosition();
        time += playbackState.getPlaybackSpeed() * (SystemClock.elapsedRealtime() - playbackState.getLastPositionUpdateTime());
        songSlider.setProgress((int) Math.min(time, Integer.MAX_VALUE));
        updateSongPositionText(time);
    } // End updateSongProgress method

    // Updates the text label representing the song position (e.g. 0:02:24/0:03:00)
    private void updateSongPositionText(long time) {
        long currentTime = time / 1000;

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

    private class BrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
        @Override
        public void onConnected() { // Successful connection
            // Run the initializer
            if (!initialized) { init(); }

            MediaControllerCompat mediaController;
            try {
                mediaController = new MediaControllerCompat(
                        MainActivity.this,
                        mediaBrowser.getSessionToken()
                );
            }
            catch (RemoteException e) {
                Log.wtf(MainActivity.class.getName() + "->onConnected", "Unable to connect to MediaControllerCompat", e);
                showMessageDialog("Unable to connect to media controller", (dialog, which) -> MainActivity.this.finish());
                super.onConnected();
                return;
            }
            MediaControllerCompat.setMediaController(MainActivity.this, mediaController);
            mediaController.registerCallback(mediaControllerCallback);
            playbackState = mediaController.getPlaybackState();
            metadata = mediaController.getMetadata();
            transportControls = mediaController.getTransportControls();
            setControlState(false);

            super.onConnected();
        }

        @Override
        public void onConnectionSuspended() { // Server crashed, awaiting restart
            setControlState(true);
            super.onConnectionSuspended();
        }

        @Override
        public void onConnectionFailed() { // Connection refused
            // Log what (shouldn't have) happened and close the app
            Log.wtf(MainActivity.class.getName() + "->onConnected", "Unable to connect to MoppyMediaService");
            showMessageDialog("Unable to connect to Media Service", (dialog, which) -> MainActivity.this.finish());
            super.onConnectionFailed();
        }
    }

    private class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            playbackState = state;
            switch (playbackState.getState()) {
                case PlaybackStateCompat.STATE_PLAYING: {
                    songTimerTask.unpause();
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_pause);
                    break;
                }
                case PlaybackStateCompat.STATE_PAUSED: {
                    songTimerTask.pause();
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
                    break;
                }
                case PlaybackStateCompat.STATE_STOPPED: {
                    songTimerTask.pause();
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
                    // Move slider to where sequencer stopped (0 if reset, otherwise song end)
                    updateSongProgress();
                    break;
                }
                case PlaybackStateCompat.STATE_BUFFERING:
                case PlaybackStateCompat.STATE_CONNECTING:
                case PlaybackStateCompat.STATE_ERROR:
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_REWINDING:
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM:
                    songTimerTask.pause();
                    break;
            }
            super.onPlaybackStateChanged(state);
        }

        @Override
        public void onMetadataChanged(MediaMetadataCompat metadata) {
            MainActivity.this.metadata = metadata;
            setControlState(false);
            long duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION);

            // Pause the slider updates while we update the maximum, re-enabling after if necessary
            songTimerTask.pause();
            songSlider.setMax((int) Math.min(duration, Integer.MAX_VALUE));

            // Set the song time text
            int durationSeconds = (int) Math.min(duration / 1000, Integer.MAX_VALUE);
            StringBuilder timeTextBuilder = new StringBuilder();
            String temp;
            timeTextBuilder.append("0:00:00/");
            timeTextBuilder.append(durationSeconds / 3600);
            timeTextBuilder.append(":");
            temp = Long.toString((durationSeconds % 3600) / 60);
            timeTextBuilder.append(("00" + temp).substring(temp.length())).append(":");
            temp = Long.toString(durationSeconds % 60);
            timeTextBuilder.append(("00" + temp).substring(temp.length()));
            ((TextView) findViewById(R.id.song_time_text)).setText(timeTextBuilder.toString());

            if (playbackState != null && playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
                songTimerTask.unpause();
            }

            super.onMetadataChanged(metadata);
        }
    }

    // The code constants for requests sent with startActivityForResult
    static final public class RequestCodes {
        static public final int LOAD_FILE = 1;
        static public final int READ_STORAGE = 2;
    } // End RequestCodes class
} // End MainActivity class

package com.moppyandroid.main;

/*
Author: Noah Reeder, noahreederatc@gmail.com

Known bugs:
TODO: Setup instance state to reshow connected device if app is destroyed, keyboard/mouse plugged in, rotation, etc.
TODO: "W/ActivityThread: handleWindowVisibilty: no activity for token" in log when starting BrowserActivity, unable to find reason
TODO: Marquee file name in toolbar

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


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
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

import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.JustifyContent;
import com.moppy.core.comms.bridge.BridgeSerial;

import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.sothree.slidinguppanel.SlidingUpPanelLayout.PanelState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemSelectedListener, SeekBar.OnSeekBarChangeListener {
    private static final String EXTRA_DEVICE_INDEX = "DEVICE_INDEX";
    private static final int REQUEST_READ_STORAGE = 1;
    private static final int REQUEST_BROWSE_ACTIVITY = 2;

    private String currentBridgeIdentifier;
    private HashMap<String, String> spinnerHashMap;
    private HashMap<Integer, String> statusDeviceIds;
    private HashMap<Integer, Boolean> permissionStatuses;
    private SlidingUpPanelLayout panelLayout;
    private SeekBar songSlider;
    private Spinner deviceBox;
    private RecyclerView libraryRecycler;
    private AlertDialog loadingBar;
    private SongTimerTask songTimerTask;
    private Handler uiHandler;
    private UsbManager usbManager;
    private boolean movingSlider;
    private boolean initialized;
    private boolean sendInitLibraryOnConnect;
    private int progressBarRequests;
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
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: {
                    onUsbDeviceAttachedIntent();
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
    @SuppressLint("InflateParams") // Not applicable to inflation for AlertDialog
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        statusDeviceIds = new HashMap<>();
        permissionStatuses = new HashMap<>();
        spinnerHashMap = new HashMap<>();
        uiHandler = new Handler();
        movingSlider = false;
        initialized = false;
        sendInitLibraryOnConnect = false;
        progressBarRequests = 0;

        // Set the initial view and disable the pause and play buttons
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.main_toolbar));
        songSlider = findViewById(R.id.song_slider);
        deviceBox = findViewById(R.id.device_box);
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

        // Create the loading bar from an alert dialog containing loading_dialog_layout
        AlertDialog.Builder alertBuilder = new AlertDialog.Builder(this);
        alertBuilder.setCancelable(false);
        alertBuilder.setView(getLayoutInflater().inflate(R.layout.loading_dialog_layout, null));
        loadingBar = alertBuilder.create();

        // Set this activity to be called when the USB device spinner or the song slider have a selection event
        deviceBox.setOnItemSelectedListener(this);
        songSlider.setOnSeekBarChangeListener(this);

        // Define the listener lambdas for the play and pause/stop buttons
        findViewById(R.id.play_button).setOnClickListener((View v) -> onPlayButton());
        findViewById(R.id.pause_button).setOnClickListener((View v) -> onPauseButton());

        libraryRecycler = findViewById(R.id.library_recycler);
        FlexboxLayoutManager libraryLayout = new FlexboxLayoutManager(this);
        libraryLayout.setFlexDirection(FlexDirection.ROW_REVERSE);
        libraryLayout.setJustifyContent(JustifyContent.SPACE_AROUND);
        libraryRecycler.setHasFixedSize(true);
        libraryRecycler.setLayoutManager(libraryLayout);
        libraryRecycler.setAdapter(new LibraryAdapter(null, null)); // Dummy adapter until items loaded

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
        if (mediaBrowser.isConnected()) {
            mediaBrowser.unsubscribe(mediaBrowser.getRoot());
            mediaBrowser.disconnect();
        }
        if (initialized) { unregisterReceiver(broadcastReceiver); }
        super.onDestroy();
    } // End onDestroy method


    /**
     * Method triggered when the back event is raised (e.g. back button pressed). Taken from AndroidSlidingUpPanel
     * demo application located at https://github.com/umano/AndroidSlidingUpPanel/tree/master/demo.
     */
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
        super.onActivityResult(requestCode, resultCode, resultData);

        // Check if the request was a file load made by us
        if (requestCode == REQUEST_BROWSE_ACTIVITY && resultCode == RESULT_OK) {
            loadItem(resultData.getParcelableExtra(BrowserActivity.EXTRA_SELECTED_FILE));
        }
    } // End onActivityResult method

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_READ_STORAGE) {
            if (initialized) {
                mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_INIT_LIBRARY, null, null);
            }
            else { sendInitLibraryOnConnect = true; }
        } // End if(READ_STORAGE)
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

            // Get the requested UsbDevice
            //noinspection SuspiciousMethodCalls
            String bridgeIdentifier = spinnerHashMap.get(parent.getItemAtPosition(position));
            UsbDevice device = usbManager.getDeviceList().get(bridgeIdentifier);
            if (device == null) {
                requestDevicesRefresh();
                parent.setSelection(0);
                return;
            }

            // If necessary, ask for permission to access the device and connect if granted, otherwise connecting
            if (!usbManager.hasPermission(device)) {
                showProgressBar();
                int deviceIndex = permissionStatuses.size();
                permissionStatuses.put(deviceIndex, false);
                statusDeviceIds.put(deviceIndex, bridgeIdentifier);
                requestPermission(device, deviceIndex);
            }
            else { openBridge(bridgeIdentifier); }
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
        if (!(intent.getExtras().get(EXTRA_DEVICE_INDEX) instanceof Integer)) { return; }

        // Retrieve the current device index from the intent and exit processing if it is null
        Integer pos = (Integer) intent.getExtras().get(EXTRA_DEVICE_INDEX);
        if (pos == null) { return; }

        // Ensure permission was granted
        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            // Mark that the permission for this device has been granted
            permissionStatuses.replace(pos, true);
            openBridge(statusDeviceIds.get(pos));
        } // End if(EXTRA_PERMISSION_GRANTED)
        else {
            deviceBox.setSelection(0);
            showMessageDialog("USB permission is required to connect.", null);
        }
        closeProgressBar();
    } // End onUsbPermissionIntent method

    // Method triggered when a USB device is attached
    // Note: If multiple devices are connected at once (e.g. USB hub connected) this method is called
    //      multiple times and may impact performance
    private void onUsbDeviceAttachedIntent() {
        requestDevicesRefresh();
    } // End onUsbDeviceAttachedIntent method

    // Method triggered when a USB device is detached
    private void onUsbDeviceDetachedIntent() {
        // Refresh the netManger device lists, waiting for the message box to be acknowledged before refreshing ours
        requestDevicesRefresh();
    } // End onUsbDeviceDetachedIntent method

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
        // If we already have storage access permission, create the MIDI library
        if (sendInitLibraryOnConnect) {
            mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_INIT_LIBRARY, null, null);
        }

        // Create the filter describing which Android system intents to process and register it
        IntentFilter globalIntentFilter = new IntentFilter();
        globalIntentFilter.addAction(ACTION_USB_PERMISSION);
        globalIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        globalIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, globalIntentFilter);

        // Initialize BridgeSerial
        BridgeSerial.init(this);
        MidiLibrary.requestStoragePermission(this, REQUEST_READ_STORAGE);

        usbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        if (usbManager == null) {
            Log.wtf(MainActivity.class.getName() + "->init", "Unable to get USB manager");
            showMessageDialog("Unable to get USB manager", (dialog, which) -> MainActivity.this.finish());
            // Fall through since finish is non-blocking and we don't want to uninitialized variables
        }

        // Request permission to access all attached USB devices (also initializes Moppy on completion)
        requestDevicesRefresh();

        Timer songProgressTimer = new Timer();
        songTimerTask = new SongTimerTask();
        songTimerTask.pause();
        // Note: The timer tick is 500 so that if the timer's ticks are slightly offset from
        // the sequencer's ticks the progress bar will still be pretty accurate
        songProgressTimer.schedule(songTimerTask, 0, 500);

        // Display the root elements
        String mediaRoot = mediaBrowser.getRoot();
        mediaBrowser.subscribe(mediaRoot, new MediaBrowserCompat.SubscriptionCallback() {
            @Override
            public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
                super.onChildrenLoaded(parentId, children);
                libraryRecycler.setAdapter(new LibraryAdapter(children, (item) -> {
                    if (item.getMediaId() == null) { return; }

                    // Check if the item is a folder, loading contents and opening a browser if so
                    if (item.isBrowsable()) {
                        // Construct an intent to launch a browser with the loaded children
                        // and the path without mediaRoot as extras
                        Intent startIntent = new Intent(MainActivity.this, BrowserActivity.class);
                        startIntent.putExtra(BrowserActivity.EXTRA_INITIAL_ID, item.getMediaId());

                        // Start the browser activity and unsubscribe from the loaded item
                        MainActivity.this.startActivityForResult(startIntent, REQUEST_BROWSE_ACTIVITY);
                    } // End if(item.isBrowsable)
                    else { loadItem(item); } // Request to load the item if it isn't a folder
                })); // End LibraryAdapter.ClickListener lambda
            } // End subscribe(ROOT)->onChildrenLoaded method
        }); // End SubscriptionCallback implementation

        initialized = true;
    } // End init method

    // Requests the MoppyMediaService to refresh its device lists and updates ours upon completion
    private void requestDevicesRefresh() {
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_REFRESH_DEVICES, null, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            @SuppressWarnings("unchecked cast")
            public void onResult(String action, Bundle extras, Bundle resultData) {
                updateDevicesUI((ArrayList<ArrayList<String>>) resultData.getSerializable(MoppyMediaService.EXTRA_DEVICE_INFOS));
                super.onResult(action, extras, resultData);
            }
        }); // End ACTION_REFRESH_DEVICES callback
    } // End requestDevicesRefresh method

    // Requests the device lists from the MoppyMediaService and updates our lists upon completion
    private void updateDevicesUI() {
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_GET_DEVICES, null, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            @SuppressWarnings("unchecked cast")
            public void onResult(String action, Bundle extras, Bundle resultData) {
                updateDevicesUI((ArrayList<ArrayList<String>>) resultData.getSerializable(MoppyMediaService.EXTRA_DEVICE_INFOS));
                super.onResult(action, extras, resultData);
            }
        }); // End ACTION_GET_DEVICES callback
    } // End updateDevicesUI(void) method

    // Refresh the device box and related lists
    private void updateDevicesUI(ArrayList<ArrayList<String>> deviceInfos) {
        if (deviceInfos == null) { return; }

        // Note: If this was called as a result of a device detachment then the service would have
        // handled the device disconnection

        // Retrieve the device box spinner, save the current selection, and clear the hashmap
        String previousSelection = (String) deviceBox.getSelectedItem();
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
        deviceBox.setAdapter(adapter);

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
            deviceBox.setSelection(0); // Manual selection triggers onItemSelected and the service gets informed about disconnection
            return;
        } // End if(index == -1)
        deviceBox.setSelection(index);
    } // End updateDevicesUI(ArrayList<ArrayList<String>>) method

    // Requests the MoppyMediaService to load a MediaItem
    private void loadItem(MediaBrowserCompat.MediaItem item) {
        if (item == null) { return; }

        // Request the load action
        Bundle loadExtras = new Bundle();
        loadExtras.putString(MoppyMediaService.EXTRA_MEDIA_ID, item.getMediaId());
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_LOAD_ITEM, loadExtras, new MediaBrowserCompat.CustomActionCallback() {
            // After-load processing handled through the media controller metadata change listener

            @Override
            public void onError(String action, Bundle extras, Bundle data) {
                showMessageDialog("Unable to load '" + item.getDescription().getTitle() + "'", null);
                super.onError(action, extras, data);
            } // End ACTION_LOAD_ITEM.onError method
        }); // End CustomActionCallback implementation
    } // End onLoadFile method

    // Opens a specific bridge
    private void openBridge(String bridgeIdentifier) {
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
                deviceBox.setSelection(0); // Select "NONE"
                Log.e(
                        MainActivity.class.getName() + "->onItemSelected",
                        "Unable to connect to device",
                        (Throwable) (data.getSerializable(MoppyMediaService.EXTRA_EXCEPTION)));
                showMessageDialog("Unable to connect to " + data.getString(MoppyMediaService.EXTRA_PORT_NAME), null);

                super.onError(action, extras, data);
            } // End ACTION_ADD_DEVICE.onError method
        }); // End ACTION_ADD_DEVICE callback
    } // End openBridge method

    // Closes the currently connected bridge, resetting currentBridgeIdentifier to null if passed true
    private void closeBridge(boolean resetIdentifier) {
        // Return if there isn't a bridge to close
        if (currentBridgeIdentifier == null) { return; }

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
        intent.putExtra(EXTRA_DEVICE_INDEX, index);
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

    private void showProgressBar() {
        if (progressBarRequests++ == 0) {
            //getWindow().setFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            loadingBar.show();
        }
    } // End showProgressBar method

    private void closeProgressBar() {
        if (--progressBarRequests == 0) {
            loadingBar.dismiss();
            //getWindow().clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
        }
    } // End closeProgressBar method

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

    // Receives callbacks about mediaBrowser.connect
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
            } // End try {new MediaControllerCompat}
            catch (RemoteException e) {
                Log.wtf(MainActivity.class.getName() + "->onConnected", "Unable to connect to MediaControllerCompat", e);
                showMessageDialog("Unable to connect to media controller", (dialog, which) -> MainActivity.this.finish());
                super.onConnected();
                return;
            } // End try {new MediaControllerCompat} catch(RemoteException)

            // Register this to receive callbacks from the controller and retrieve the current state
            MediaControllerCompat.setMediaController(MainActivity.this, mediaController);
            mediaController.registerCallback(mediaControllerCallback);
            playbackState = mediaController.getPlaybackState();
            metadata = mediaController.getMetadata();
            transportControls = mediaController.getTransportControls();
            setControlState(false);

            super.onConnected();
        } // End onConnected method

        @Override
        public void onConnectionSuspended() { // Server crashed, awaiting restart
            setControlState(true);
            super.onConnectionSuspended();
        } // End onConnectionSuspended method

        @Override
        public void onConnectionFailed() { // Connection refused
            // Log what (shouldn't have) happened and close the app
            Log.wtf(MainActivity.class.getName() + "->onConnected", "Unable to connect to MoppyMediaService");
            showMessageDialog("Unable to connect to Media Service", (dialog, which) -> MainActivity.this.finish());
            super.onConnectionFailed();
        } // End onConnectionFailed method
    } // End BrowserConnectionCallback class

    // Receives callbacks when the media session loads a file or plays/pauses/stops/etc.
    private class MediaControllerCallback extends MediaControllerCompat.Callback {
        @Override
        public void onPlaybackStateChanged(PlaybackStateCompat state) {
            playbackState = state;
            switch (playbackState.getState()) {
                case PlaybackStateCompat.STATE_PLAYING: {
                    songTimerTask.unpause();
                    setControlState(false);
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_pause);
                    break;
                } // End STATE_PLAYING case
                case PlaybackStateCompat.STATE_PAUSED: {
                    songTimerTask.pause();
                    setControlState(false);
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
                    break;
                } // End STATE_PAUSED case
                case PlaybackStateCompat.STATE_STOPPED: {
                    songTimerTask.pause();
                    setControlState(false);
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
                    // Move slider to where sequencer stopped (0 if reset, otherwise song end)
                    updateSongProgress();
                    break;
                } // End STATE_STOPPED case
                case PlaybackStateCompat.STATE_BUFFERING:
                case PlaybackStateCompat.STATE_CONNECTING:
                case PlaybackStateCompat.STATE_ERROR:
                case PlaybackStateCompat.STATE_FAST_FORWARDING:
                case PlaybackStateCompat.STATE_NONE:
                case PlaybackStateCompat.STATE_REWINDING:
                case PlaybackStateCompat.STATE_SKIPPING_TO_NEXT:
                case PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS:
                case PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM: {
                    songTimerTask.pause();
                    setControlState(true);
                    break;
                } // End !(STATE_PLAYING | STATE_PAUSED | STATE_STOPPED) case
            } // End switch(playbackState)
            super.onPlaybackStateChanged(state);
        } // End onPlaybackStateChanged method

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

            setSongName(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));

            super.onMetadataChanged(metadata);
        } // End onMetadataChanged method
    } // End MediaControllerCallback class
} // End MainActivity class

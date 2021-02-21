/*
 * Copyright (C) 2021 Noah Reeder
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

package com.moppyandroid.main;

/*
Author: Noah Reeder, noahreederatc@gmail.com

Known bugs:
TODO: "W/ActivityThread: handleWindowVisibilty: no activity for token" in log when starting BrowserActivity, unable to find reason
TODO: Issue with Moppy device not appearing if powered before plugging into Android
 ^ Caused by Android not detecting USB device - Only occurs in some devices, may be hardware/Android issue. Unaffected by switching Arduino boards
TODO: If current song in queue is switched back and forth quickly sometimes the incorrect song length will be displayed
 ^ Likely due to metadata change events being received in wrong order, nothing we can do

Known problems:
    - Hard to use track slider in slide menu (adjust slide menu sensitivity?)
    - Must connect to device, disconnect, and connect again for connection to work... sometimes
    - Sheet music shading is a little weird sometimes, particularly during seeks or while scrolling music
    - When the same song is added twice a playing icon appears for both in the queue view, no reliable way to differentiate them available


Features to implement:
    - Playlists
    - Save last folder viewed in each category


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


import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.SearchManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.RemoteException;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.flexbox.FlexDirection;
import com.google.android.flexbox.FlexboxLayoutManager;
import com.google.android.flexbox.JustifyContent;
import com.midisheetmusic.FileUri;
import com.midisheetmusic.MidiOptions;
import com.midisheetmusic.SheetMusic;
import com.midisheetmusic.TimeSigSymbol;
import com.midisheetmusic.sheets.ClefSymbol;
import com.moppy.core.comms.bridge.BridgeSerial;
import com.moppyandroid.main.service.MidiLibrary;
import com.moppyandroid.main.service.MidiLibrary.MidiFile;
import com.moppyandroid.main.service.MoppyMediaService;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.sothree.slidinguppanel.SlidingUpPanelLayout.PanelState;

import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Main activity for MoppyAndroid.
 */
public class MainActivity extends AppCompatActivity implements SeekBar.OnSeekBarChangeListener {
    /**
     * Tag for the {@link DeviceSelectorDialog} fragment used.
     */
    public static final String TAG_DEVICE_DIALOG = "MOPPY_DEVICE_SELECTOR_DIALOG";
    /**
     * Action used with {@link UsbManager#requestPermission(UsbDevice, PendingIntent)}.
     */
    public static final String ACTION_USB_PERMISSION = "com.moppyandroid.USB_PERMISSION";
    /**
     * Request code for {@link #ACTION_USB_PERMISSION}.
     */
    public static final int REQUEST_DEVICE_PERMISSION = 0;
    /**
     * Request code for {@link android.Manifest.permission#READ_EXTERNAL_STORAGE}.
     */
    public static final int REQUEST_READ_STORAGE = 1;
    /**
     * Request code for {@link BrowserActivity}.
     */
    public static final int REQUEST_BROWSE_ACTIVITY = 2;

    private SlidingUpPanelLayout panelLayout;
    private SeekBar songSlider;
    private RecyclerView libraryRecycler;
    private RecyclerView queueRecycler;
    private SongTimerTask songTimerTask;
    private Handler uiHandler;
    private boolean movingSlider;
    private boolean initialized;
    private boolean sendInitLibraryOnConnect;
    private boolean shadeSheetMusic;
    private double pulsesPerMs;
    private double currentPulseTime;
    private MediaBrowserCompat mediaBrowser;
    private MediaControllerCompat mediaController;
    private MediaControllerCallback mediaControllerCallback;
    private MediaControllerCompat.TransportControls transportControls;
    private PlaybackStateCompat playbackState;
    private MidiFile midiFile;
    private DeviceSelectorDialogManager deviceDialogManager;
    private LinearLayout queuePanel;
    private LinearLayout sheetPanel;
    private SheetMusic sheetMusic;

    // Define the receiver to process relevant intent messages
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) throws NullPointerException {
            // Ensure intent action is not null, exiting processing if so
            if (intent.getAction() == null) { return; }

            // Determine action and process accordingly
            switch (intent.getAction()) {
                case MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH: {
                    if (transportControls != null) {
                        transportControls.playFromSearch(intent.getStringExtra(SearchManager.QUERY), intent.getExtras());
                    }
                    break;
                } // End case ACTION_MEDIA_PLAY_FROM_SEARCH
                case ACTION_USB_PERMISSION: {
                    requestDevicesRefresh();
                    if (deviceDialogManager != null) {
                        deviceDialogManager.onUsbPermissionIntent(intent);
                    }
                    break;
                } // End case ACTION_USB_PERMISSION
                case UsbManager.ACTION_USB_DEVICE_ATTACHED: // Fall through
                case UsbManager.ACTION_USB_DEVICE_DETACHED: {
                    // Note: If multiple devices are connected at once (e.g. USB hub connected) this broadcast
                    //       is sent for each device
                    if (deviceDialogManager != null) {
                        deviceDialogManager.onDeviceConnectionStateChanged();
                    }
                    break;
                } // End case ACTION_USB_DEVICE_DETACHED
            } // End switch(intent.action)
        } // End onReceive method
    }; // End new BroadcastReceiver

    /**
     * Triggered when the activity is created.
     */
    @SuppressLint("InflateParams") // Not applicable to inflation for AlertDialog
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        uiHandler = new Handler();
        movingSlider = false;
        initialized = false;
        sendInitLibraryOnConnect = false;
        shadeSheetMusic = false;

        // Set the initial view and disable the pause and play buttons
        setContentView(R.layout.activity_main);
        setSupportActionBar(findViewById(R.id.main_toolbar));
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
        ((Switch) findViewById(R.id.playlist_switch)).setOnCheckedChangeListener((CompoundButton v, boolean isChecked) -> onPlaylistSwitchChanged(isChecked));

        // Get the panels for swapping between sheet music and queue views
        queuePanel = findViewById(R.id.queue_panel);
        sheetPanel = findViewById(R.id.sheet_panel);

        // Set this activity to be called when the song slider is moved
        songSlider.setOnSeekBarChangeListener(this);

        // Define the listener lambdas for the play and pause/stop buttons
        findViewById(R.id.play_button).setOnClickListener((View v) -> onPlayButton());
        findViewById(R.id.pause_button).setOnClickListener((View v) -> onPauseButton());

        // Set up the library recycler
        libraryRecycler = findViewById(R.id.library_recycler);
        FlexboxLayoutManager libraryLayout = new FlexboxLayoutManager(this);
        libraryLayout.setFlexDirection(FlexDirection.ROW_REVERSE);
        libraryLayout.setJustifyContent(JustifyContent.SPACE_AROUND);
        libraryRecycler.setHasFixedSize(true);
        libraryRecycler.setLayoutManager(libraryLayout);
        libraryRecycler.setAdapter(new LibraryAdapter(null, null)); // Dummy adapter until items loaded

        // Enable file name/time marquees
        findViewById(R.id.toolbar_song_title).setSelected(true);
        findViewById(R.id.song_time_text).setSelected(true);
        findViewById(R.id.song_title).setSelected(true);

        // Connect to the service
        mediaBrowser.connect();

        // Set up the devices dialog
        deviceDialogManager = new DeviceSelectorDialogManager(this, getSupportFragmentManager(), TAG_DEVICE_DIALOG);
        findViewById(R.id.devices_button).setOnClickListener((view) -> deviceDialogManager.show());

        // Set up the queue
        queueRecycler = findViewById(R.id.queue_recycler);
        queueRecycler.setLayoutManager(new LinearLayoutManager(this));
        queueRecycler.setAdapter(new QueueAdapter(null, null, null)); // Dummy adapter until connected to service
    } // End onCreate method

    /**
     * Triggered when the app is destroyed (e.g. force killed, finalize called, killed to reclaim memory).
     */
    @Override
    protected void onDestroy() {
        if (MediaControllerCompat.getMediaController(this) != null) {
            MediaControllerCompat.getMediaController(this).unregisterCallback(mediaControllerCallback);
        }
        if (mediaBrowser.isConnected()) {
            mediaBrowser.unsubscribe(mediaBrowser.getRoot());
        }
        mediaBrowser.disconnect();
        if (initialized) { unregisterReceiver(broadcastReceiver); }
        if (deviceDialogManager != null) { deviceDialogManager.close(); }
        super.onDestroy();
    } // End onDestroy method


    /**
     * Triggered when the back event is raised (e.g. back button pressed). Taken from AndroidSlidingUpPanel
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

    /**
     * Triggered when a spawned activity exits.
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        super.onActivityResult(requestCode, resultCode, resultData);

        // Check if the request was a file load made by us
        if (requestCode == REQUEST_BROWSE_ACTIVITY && resultCode == RESULT_OK) {
            loadItem(resultData.getParcelableExtra(BrowserActivity.EXTRA_SELECTED_FILE));
        }
    } // End onActivityResult method

    /**
     * Triggered when a permission request is responded to.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_READ_STORAGE) {
            if (initialized) {
                mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_INIT_LIBRARY, null, null);
            }
            else { sendInitLibraryOnConnect = true; }
            TimeSigSymbol.LoadImages(this);
            ClefSymbol.LoadImages(this);
        } // End if(READ_STORAGE)
    } // End onRequestPermissionsResult method

    /**
     * Triggered when the song slider has been used.
     */
    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
        if (seekBar == songSlider) {
            if (fromUser && !movingSlider) {
                // Seek to the new position, which will update the playback state and therefore the text
                transportControls.seekTo(progress);
            } // End if(fromUser)
        } // End if(seekBar == songSlider)
    } // End onProgressChanged method

    /**
     * Triggered when the song slider begins to be moved.
     */
    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        if (seekBar == songSlider) {
            movingSlider = true;
            songTimerTask.pause();
        } // End if(seekBar == songSlider)
    } // End onStartTrackingTouch method

    /**
     * Triggered when the song slider finishes being moved.
     */
    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        if (seekBar == songSlider) {
            movingSlider = false;
            onProgressChanged(seekBar, seekBar.getProgress(), true);
            songTimerTask.unpause(); // After onProgressChanged so time calculation doesn't overwrite seek
        } // End if(seekBar == songSlider)
    } // End onStopTrackingTouch method

    // Method triggered when play button pressed
    private void onPlayButton() { transportControls.play(); }

    // Method triggered when pause/stop button pressed
    private void onPauseButton() {
        if (playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
            transportControls.pause();
        }
        else { transportControls.stop(); }
    } // End onPauseButton method

    // Method triggered when an item in the queue is clicked
    private void onQueueItemClicked(MediaSessionCompat.QueueItem song) {
        transportControls.skipToQueueItem(song.getQueueId());
    } // End onQueueItemClicked method

    // Method triggered when music queue/sheet music switch changed
    private void onPlaylistSwitchChanged(boolean isChecked) {
        if (isChecked) {
            queuePanel.setVisibility(View.GONE);
            sheetPanel.setVisibility(View.VISIBLE);
        }
        else {
            sheetPanel.setVisibility(View.GONE);
            queuePanel.setVisibility(View.VISIBLE);
        }
    } // End onPlaylistSwitchChanged method

    // Initialize non-MoppyLib objects of the class and start the Moppy initialization chain
    @SuppressLint("UseSparseArrays")
    private void init() {
        // If we already have storage access permission, create the MIDI library
        if (sendInitLibraryOnConnect) {
            mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_INIT_LIBRARY, null, null);
        }

        // Create the filter describing which Android system intents to process and register it
        IntentFilter globalIntentFilter = new IntentFilter();
        globalIntentFilter.addAction(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH);
        globalIntentFilter.addAction(ACTION_USB_PERMISSION);
        globalIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        globalIntentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, globalIntentFilter);

        // Initialize BridgeSerial
        BridgeSerial.init(this);
        MidiLibrary.requestStoragePermission(this, REQUEST_READ_STORAGE);

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

    // Refreshes the device lists of the MoppyMediaService
    private void requestDevicesRefresh() {
        if (mediaBrowser != null && mediaBrowser.isConnected()) {
            mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_REFRESH_DEVICES, null, null);
        }
    } // End requestDevicesRefresh method

    // Requests the MoppyMediaService to load a MediaItem
    private void loadItem(MediaBrowserCompat.MediaItem item) {
        if (item == null) { return; }
        boolean setToPlaying = false;
        if (mediaController != null && mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING) {
            setToPlaying = true;
        }

        // Request the load action
        Bundle loadExtras = new Bundle();
        loadExtras.putString(MoppyMediaService.EXTRA_MEDIA_ID, item.getMediaId());
        loadExtras.putBoolean(MoppyMediaService.EXTRA_PLAY, setToPlaying);
        mediaBrowser.sendCustomAction(MoppyMediaService.ACTION_LOAD_ITEM, loadExtras, new MediaBrowserCompat.CustomActionCallback() {
            @Override
            public void onResult(String action, Bundle extras, Bundle resultData) {
                // Most after-load processing handled through the media controller metadata change listener
                midiFile = resultData.getParcelable(MoppyMediaService.EXTRA_MEDIA_MIDI_FILE);
                createSheetMusic();
                super.onResult(action, extras, resultData);
            }

            @Override
            public void onError(String action, Bundle extras, Bundle data) {
                showMessageDialog("Unable to load '" + item.getDescription().getTitle() + "'", null);
                super.onError(action, extras, data);
            } // End ACTION_LOAD_ITEM.onError method
        }); // End ACTION_LOAD_ITEM callback
    } // End onLoadFile method

    // Creates sheet music in the slider based on the current MidiFile
    private void createSheetMusic() {
        if (midiFile == null) { return; }
        shadeSheetMusic = false;

        FileUri sheetFileUri = new FileUri(midiFile.getUri(), midiFile.getPath());
        byte[] bytes = sheetFileUri.getData(MainActivity.this);
        com.midisheetmusic.MidiFile sheetFile = new com.midisheetmusic.MidiFile(bytes, midiFile.getName());
        MidiOptions options = new MidiOptions(sheetFile);
        options.showPiano = false;
        options.twoStaffs = false;
        pulsesPerMs = sheetFile.getTime().getQuarter() * (1000.0 / options.tempo);

        LinearLayout sheetLayout = findViewById(R.id.sheet_layout);
        if (sheetMusic != null) {
            sheetLayout.removeView(sheetMusic);
        }
        sheetMusic = new SheetMusic(MainActivity.this);
        sheetMusic.init(sheetFile, options);
        sheetLayout.addView(sheetMusic);
        shadeSheetMusic = true;
    } // End createSheetMusic method

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
            if (mediaController != null && mediaController.getMetadata() != null) {
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
    private void updateSongProgress(boolean overrideDelta) {
        long delta = (SystemClock.elapsedRealtime() - playbackState.getLastPositionUpdateTime());
        long time = playbackState.getPosition();
        if (!overrideDelta) { time += playbackState.getPlaybackSpeed() * delta; }

        songSlider.setProgress((int) Math.min(time, Integer.MAX_VALUE));
        updateSongPositionText(time);

        double prevPulseTime = currentPulseTime;
        currentPulseTime = time * pulsesPerMs;
        if (sheetMusic != null && shadeSheetMusic) {
            if (time == 0) {
                sheetMusic.ShadeNotes(-10, (int) prevPulseTime, SheetMusic.DontScroll);
                sheetMusic.ShadeNotes(-10, (int) currentPulseTime, SheetMusic.DontScroll);
            }
            else {
                sheetMusic.ShadeNotes((int) currentPulseTime, (int) prevPulseTime, SheetMusic.GradualScroll);
            }
        }
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
        public void run() { if (notPaused) { updateSongProgress(false); } }

        public void pause() { notPaused = false; }

        public void unpause() { notPaused = true; }
    } // End SongTimerTask class

    // Receives callbacks about mediaBrowser.connect
    private class BrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
        @Override
        public void onConnected() { // Successful connection
            // Run the initializer
            if (!initialized) { init(); }

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
            transportControls = mediaController.getTransportControls();

            // Load in the current metadata and enable controls accordingly
            mediaControllerCallback.onMetadataChanged(mediaController.getMetadata());

            // Load in the current music queue
            String currentMediaId;
            if (mediaController.getMetadata() != null && mediaController.getMetadata().getDescription() != null) {
                currentMediaId = mediaController.getMetadata().getDescription().getMediaId();
            }
            else { currentMediaId = null; }
            queueRecycler.setAdapter(new QueueAdapter(mediaController.getQueue(), currentMediaId, MainActivity.this::onQueueItemClicked));

            super.onConnected();
        } // End onConnected method

        @Override
        public void onConnectionSuspended() { // Server crashed, awaiting restart
            setControlState(true);
            transportControls = null;
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
                    updateSongProgress(true); // See below note on delta
                    break;
                } // End STATE_PLAYING case
                case PlaybackStateCompat.STATE_PAUSED: // Fall through to STATE_STOPPED case
                case PlaybackStateCompat.STATE_STOPPED: {
                    songTimerTask.pause();
                    setControlState(false); // Accounts for whether STATE_PAUSED or STATE_STOPPED
                    ((ImageButton) findViewById(R.id.pause_button)).setImageResource(R.drawable.ic_stop);
                    // Move slider to where sequencer stopped, overriding calculations
                    // Note: Calculations overridden because updateSongProgress uses clock calculations
                    //      to compute the time delta for how far into the song it is, which makes it slightly
                    //      inaccurate and means that the position wouldn't be correct if playback stopped
                    updateSongProgress(true);
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
            setControlState(false);

            long duration;
            if (metadata == null) { duration = 0; }
            else { duration = metadata.getLong(MediaMetadataCompat.METADATA_KEY_DURATION); }

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

            // Re-enable the song slider ticks if applicable
            if (playbackState != null && playbackState.getState() == PlaybackStateCompat.STATE_PLAYING) {
                songTimerTask.unpause();
            }

            // Set the song name to "No song loaded" if metadata is empty, otherwise the title
            if (metadata == null) { setSongName(getString(R.string.song_title)); }
            else { setSongName(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE)); }

            // Update the queue
            // Note: If a song is added to the end of the queue the adapter will be recreated here and in onQueueChanged, but c'est la vie it's only up to 50 songs
            String currentMediaId;
            if (metadata == null || metadata.getDescription() == null) { currentMediaId = null; }
            else { currentMediaId = metadata.getDescription().getMediaId(); }
            List<MediaSessionCompat.QueueItem> queue = new ArrayList<>();
            if (mediaController != null) { queue = mediaController.getQueue(); }
            queueRecycler.setAdapter(new QueueAdapter(queue, currentMediaId, MainActivity.this::onQueueItemClicked));

            super.onMetadataChanged(metadata);
        } // End onMetadataChanged method

        /**
         * Override to handle changes to items in the queue.
         *
         * @param queue A list of items in the current play queue. It should
         *              include the currently playing item as well as previous and
         * @see MediaSessionCompat.QueueItem
         */
        @Override
        public void onQueueChanged(List<MediaSessionCompat.QueueItem> queue) {
            // Load in the current music queue
            String currentMediaId = null;
            if (mediaController != null) {
                MediaMetadataCompat metadata = mediaController.getMetadata();
                if (metadata != null && metadata.getDescription() != null) {
                    currentMediaId = metadata.getDescription().getMediaId();
                }
            }
            queueRecycler.setAdapter(new QueueAdapter(queue, currentMediaId, MainActivity.this::onQueueItemClicked));

            super.onQueueChanged(queue);
        }
    } // End MediaControllerCallback class
} // End MainActivity class

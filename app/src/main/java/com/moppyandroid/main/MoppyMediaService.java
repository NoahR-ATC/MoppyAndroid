package com.moppyandroid.main;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import com.moppy.core.comms.bridge.BridgeSerial;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;

public class MoppyMediaService extends MediaBrowserServiceCompat {
    /**
     * The ID for the media service notification channel.
     */
    public static final String CHANNEL_ID = "MoppyMediaServiceChannel";
    /**
     * The intent name for adding a Moppy device.
     */
    public static final String ACTION_ADD_DEVICE = "com.moppyandroid.main.MoppyMediaService.ADD_DEVICE";
    /**
     * The intent name for removing a Moppy device.
     */
    public static final String ACTION_REMOVE_DEVICE = "com.moppyandroid.main.MoppyMediaService.REMOVE_DEVICE";
    /**
     * The intent name for removing a Moppy device.
     */
    public static final String ACTION_INIT_LIBRARY = "com.moppyandroid.main.MoppyMediaService.INIT_LIBRARY";
    /**
     * The intent name for refreshing the available USB devices.
     */
    public static final String ACTION_REFRESH_DEVICES = "com.moppyandroid.main.MoppyMediaService.REFRESH_DEVICES";
    /**
     * The intent name for informing clients about a failed {@link #ACTION_ADD_DEVICE} intent.
     */
    public static final String RESPONSE_CONNECTION_FAILED = "com.moppyandroid.main.MoppyMediaService.CONNECTION_FAILED";
    /**
     * The intent name for informing clients about a failed loading action.
     */
    public static final String RESPONSE_LOAD_FAILED = "com.moppyandroid.main.MoppyMediaService.LOAD_FAILED";
    /**
     * {@link String} extra field for the port name associated with a {@code ACTION_ADD_DEVICE} or {@code ACTION_REMOVE_DEVICE} event.
     */
    public static final String EXTRA_PORT_NAME = "MOPPY_EXTRA_DEVICE_PORT_NAME";
    /**
     * {@link MediaMetadataCompat} extra field for the metadata associated with a song.
     */
    public static final String EXTRA_MEDIA_METADATA = "MOPPY_EXTRA_MEDIA_METADATA";
    /**
     * {@link Exception} extra field for a parceled exception.
     */
    public static final String EXTRA_EXCEPTION = "MOPPY_EXTRA_EXCEPTION";

    private static final int NOTIFICATION_ID = 1;               // ID of the service notification
    private static final int NOTIFICATION_PLAY_PAUSE_INDEX = 2; // Index of play/pause button in notification actions

    private LocalBroadcastManager localBroadcastManager;
    private NotificationManager notificationManager;
    private MediaControllerCompat mediaController;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder playbackStateBuilder;
    private NotificationCompat.Builder notificationBuilder;
    private MidiLibrary midiLibrary;
    private MoppyManager moppyManager;
    private long currentSequencerLength;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { // TODO: Replace with custom Browser actions
            if (intent.getAction() == null) { return; }
            switch (intent.getAction()) {
                case ACTION_ADD_DEVICE: {
                    onAddDevice(intent);
                    break;
                } // End ACTION_ADD_DEVICE case
                case ACTION_REMOVE_DEVICE: {
                    onRemoveDevice(intent);
                    break;
                } // End ACTION_REMOVE_DEVICE case
                case ACTION_INIT_LIBRARY: {
                    initializeMIDILibrary();
                    break;
                } // End ACTION_INIT_LIBRARY case
                case ACTION_REFRESH_DEVICES: {
                    moppyManager.getUsbManager().refreshDeviceList();
                    break;
                }
            } // End switch(getAction)
        } // End onReceive method
    }; // End BroadcastReceiver implementation

    /**
     * Triggered when the service is first created, usually by the first {@link Context#startService(Intent)}
     * call. Always triggered before {@link #onStartCommand(Intent, int, int)}.
     */
    @Override
    public void onCreate() {
        super.onCreate();

        // Register to receive ACTION_ADD_DEVICE, ACTION_REMOVE_DEVICE, and ACTION_INIT_LIBRARY intents
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_ADD_DEVICE);
        f.addAction(ACTION_REMOVE_DEVICE);
        f.addAction(ACTION_INIT_LIBRARY);
        localBroadcastManager.registerReceiver(broadcastReceiver, f);

        // Create the media session and register it with the superclass
        mediaSession = new MediaSessionCompat(this, "MoppyMediaService");
        mediaSession.setCallback(new MediaCallback());
        mediaSession.setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS |
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
        ); // End mediaSession flags
        playbackStateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY |
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_STOP
                ) // End setActions call
                .setState(
                        PlaybackStateCompat.STATE_NONE,
                        PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                        0
                ); // End setState call
        mediaSession.setPlaybackState(playbackStateBuilder.build());
        mediaSession.setActive(true);
        mediaController = mediaSession.getController();
        setSessionToken(mediaSession.getSessionToken());

        // Create the notification channel
        // Don't know why the WrongConstant warning is appearing, I'm using the constant it suggests
        @SuppressLint("WrongConstant")
        NotificationChannel notificationChannel = new NotificationChannel(
                CHANNEL_ID,
                "Media Service Notification",
                NotificationManager.IMPORTANCE_LOW
        );
        notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            throw new RuntimeException("No notification manager found");
        } // End if(notificationManager == null)
        notificationManager.createNotificationChannel(notificationChannel);

        // Create the initial notification and start this service in the foreground
        notificationBuilder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("No song loaded")
                .setContentText("0 devices connected")
                .setSmallIcon(R.drawable.ic_notification)
                .setContentIntent(mediaController.getSessionActivity())
                .addAction(R.drawable.ic_prev, "Previous", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS))
                .addAction(new NotificationCompat.Action(R.drawable.ic_stop, "Stop", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_STOP)))
                // **** IMPORTANT **** If order is changed, make sure NOTIFICATION_PLAY_PAUSE_INDEX constant is updated
                .addAction(new NotificationCompat.Action(R.drawable.ic_play, "Play", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_PLAY_PAUSE)))
                .addAction(R.drawable.ic_next, "Next", MediaButtonReceiver.buildMediaButtonPendingIntent(this, PlaybackStateCompat.ACTION_SKIP_TO_NEXT))
                .setStyle(new androidx.media.app.NotificationCompat.MediaStyle()
                        .setMediaSession(mediaSession.getSessionToken())
                        .setShowCancelButton(false)
                        .setShowActionsInCompactView(1, NOTIFICATION_PLAY_PAUSE_INDEX)
                ) // End setStyle call
                .setColor(getColor(R.color.colorPrimaryDark))
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);

        // Create the MoppyManager, crashing if it cannot be created. If an exception is raised, it
        // is an unrecoverable runtime error. The exception is logged during MoppyManager with Log.wtf,
        // so all we need to do is ensure the process is killed by raising a runtime exception
        try { moppyManager = new MoppyManager(this); }
        catch (MidiUnavailableException e) { throw new RuntimeException(e); }
        moppyManager.registerCallback(new MoppyCallback());

        startForeground(NOTIFICATION_ID, notificationBuilder.build());

        // Attempt to create the MIDI library. Probably will fail due to permissions not having been
        // granted yet, but hopefully the user will grant them so the next attempt is successful
        midiLibrary = MidiLibrary.getMIDILibrary(this);
    } // End onCreate method

    /**
     * Triggered when this service is started with a call to {@link Context#startService}.
     *
     * @param intent  the intent used in the {@code Context.startService call}
     * @param flags   either 0, {@link Service#START_FLAG_REDELIVERY}, or {@link Service#START_FLAG_RETRY}
     * @param startId unique identifier for this start instance, used with {@link Service#stopSelfResult(int)}
     * @return flag for how the system should handle restarting the service, one of the
     * {@link Service#START_CONTINUATION_MASK} flags
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        return START_REDELIVER_INTENT;
    } // End onStartCommand method

    /**
     * Triggered when the activity is killed (e.g. swipe up in recent apps, G.C.).
     * Not called if the app is force killed.
     *
     * @param rootIntent the intent used to launch the task being removed
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Triggered when the service has completed and is being destroyed by the garbage collector.
     */
    @Override
    public void onDestroy() {
        if (moppyManager != null) { moppyManager.getUsbManager().closeAllBridges(); }
        stopForeground(true);
        if (mediaSession != null) { mediaSession.release(); }
        if (localBroadcastManager != null) {
            localBroadcastManager.unregisterReceiver(broadcastReceiver);
        }
        super.onDestroy();
    }

    /**
     * Retrieves the node that allows a connecting client to browse the content library's root folder.
     *
     * @param clientPackageName package name of the connecting client
     * @param clientUid         identifier of the connecting client
     * @param rootHints         hints about what content to return; ignored
     * @return the root node browser
     */
    @Override
    public BrowserRoot onGetRoot(String clientPackageName, int clientUid, Bundle rootHints) {
        return new BrowserRoot(MidiLibrary.ROOT_ID, null);
    } // End onGetRoot method

    /**
     * Uses the provided media ID to retrieve the node's children, which are either browsable
     * {@link android.support.v4.media.MediaBrowserCompat.MediaItem}s (folders) or playable {@code MediaItem}s.
     *
     * @param parentMediaId the ID of the node to retrieve items from
     * @param result        the list of {@code MediaItem}s contained in the parent node
     */
    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        // If the MIDI library isn't loaded yet, Start another thread to load it and recall this method
        if (midiLibrary == null) {
            result.detach();
            MidiLibrary.getMIDILibraryAsync(
                    MoppyMediaService.this,
                    midiLibraryResult -> {
                        // Send that an error occurred if we don't have permission to access storage
                        if (midiLibraryResult == null) { result.sendResult(null); }
                        else {
                            this.midiLibrary = midiLibraryResult;
                            onLoadChildren(parentMediaId, result);
                        }
                    } // End MidiLibrary.Callback lambda
            ); // End getMIDILibraryAsync call
            return;
        } // End if(midiLibrary == null)

        // Attempt to retrieve the provided ID's children from the MIDI library and convert them to MediaItems
        MidiLibrary.MapNode node = midiLibrary.get(parentMediaId);
        if (node == null) { // ID doesn't exist
            result.sendResult(null);
            return;
        } // End if(node == null)
        if (node instanceof MidiLibrary.Folder) {
            Set<MidiLibrary.MapNode> children = node.getChildren();
            for (MidiLibrary.MapNode child : children) {
                mediaItems.add(new MediaBrowserCompat.MediaItem(
                        child.getMetadata().getDescription(),
                        (child instanceof MidiLibrary.MidiFile) ?
                                MediaBrowserCompat.MediaItem.FLAG_PLAYABLE :
                                MediaBrowserCompat.MediaItem.FLAG_BROWSABLE
                )); // End add call
            } // End for(child : children)
        } // End if(node ∈ Folder)

        // If node is a MidiFile then mediaItems will be an empty list

        result.sendResult(mediaItems);
    } // End onLoadChildren method

    /**
     * Creates the {@link MidiLibrary} object this {@code MoppyMediaService} uses for controlling files.
     * Strongly suggested to call this method as soon as the {@link android.app.Activity} has been granted
     * the {@link Manifest.permission#READ_EXTERNAL_STORAGE} permission by the user.
     */
    public void initializeMIDILibrary() {
        if (midiLibrary == null) { midiLibrary = MidiLibrary.getMIDILibrary(this); }
    } // End initializeMIDILibrary method

    // Method to toggle the notification between playing (pause icon) and not-playing (play icon) modes.
    //
    // Set metadata to null to put the notification in not-playing mode
    // Set changeText to true to update the notification text to "No song loaded" or the song name
    //
    // Note: Recommended to call playbackStateBuilder.setState before this method to avoid, having to
    //      call mediaSession.setPlaybackState twice
    @SuppressLint("RestrictedApi") // IDE doesn't want us to access notificationBuilder.mActions
    private void togglePlayPauseMediaButton(boolean changeText, MediaMetadataCompat metadata) {
        // Calculate flags and update session
        long currentActionFlags = PlaybackStateCompat.ACTION_PLAY_PAUSE |
                                  PlaybackStateCompat.ACTION_STOP |
                                  PlaybackStateCompat.ACTION_SEEK_TO |
                                  ((metadata == null) ?
                                          PlaybackStateCompat.ACTION_PLAY :
                                          PlaybackStateCompat.ACTION_PAUSE
                                  );
        playbackStateBuilder.setActions(currentActionFlags);
        mediaSession.setPlaybackState(playbackStateBuilder.build());

        // Replace button icon
        notificationBuilder.mActions.remove(NOTIFICATION_PLAY_PAUSE_INDEX);
        if (metadata == null) { // Not-playing mode
            notificationBuilder.setOngoing(false);
            notificationBuilder.mActions.add(
                    NOTIFICATION_PLAY_PAUSE_INDEX,
                    new NotificationCompat.Action(
                            R.drawable.ic_play,
                            "Play",
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                    MoppyMediaService.this,
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                            )
                    ) // End NotificationCompat.Action constructor call
            ); // End mActions.add call
            if (changeText) { notificationBuilder.setContentTitle("No file loaded"); }
        } // End if(metadata == null)
        else { // Playing mode
            notificationBuilder.setOngoing(true);
            notificationBuilder.mActions.add(
                    NOTIFICATION_PLAY_PAUSE_INDEX,
                    new NotificationCompat.Action(
                            R.drawable.ic_pause,
                            "Pause",
                            MediaButtonReceiver.buildMediaButtonPendingIntent(
                                    MoppyMediaService.this,
                                    PlaybackStateCompat.ACTION_PLAY_PAUSE
                            )
                    ) // End NotificationCompat.Action constructor call
            ); // End mActions.add call
            if (changeText) {
                notificationBuilder.setContentTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
            } // End if(changeText)
        } // End if(metadata == null) {} else

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    } // End togglePlayPauseMediaButton method

    // Triggered by an ACTION_ADD_DEVICE intent
    private void onAddDevice(Intent intent) {
        if (intent.getExtras() == null || intent.getExtras().getString(EXTRA_PORT_NAME) == null) {
            Log.w(MoppyMediaService.class.getName() + "->onAddDevice", "No port name supplied");
            return;
        }
        String portName = intent.getExtras().getString(EXTRA_PORT_NAME);

        try { moppyManager.getUsbManager().connectBridge(portName); }
        catch (IllegalArgumentException | BridgeSerial.UnableToObtainDeviceException | IOException e) {
            Log.e(MoppyMediaService.class.getName() + "->onAddDevice", "Unable to connect bridge", e);
            Intent failureIntent = new Intent(RESPONSE_CONNECTION_FAILED);
            failureIntent.putExtra(EXTRA_PORT_NAME, portName);
            failureIntent.putExtra(EXTRA_EXCEPTION, e);
            localBroadcastManager.sendBroadcast(failureIntent);
            return;
        }

        int numConnected = moppyManager.getUsbManager().getNumberConnected();
        notificationBuilder.setContentText(numConnected + " device" + ((numConnected == 1) ? "" : "s") + " connected");
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    } // End onAddDevice method

    // Triggered by an ACTION_REMOVE_DEVICE intent
    private void onRemoveDevice(Intent intent) {
        if (intent.getExtras() == null || intent.getExtras().getString(EXTRA_PORT_NAME) == null) {
            Log.w(MoppyMediaService.class.getName() + "->onRemoveDevice", "No port name supplied");
        }
        String portName = intent.getExtras().getString(EXTRA_PORT_NAME);

        if (!moppyManager.getUsbManager().isConnected(portName)) {
            Log.w(MoppyMediaService.class.getName() + "->onRemoveDevice", "Attempted to close unconnected bridge");
            return;
        }

        try {moppyManager.getUsbManager().closeBridge(portName);}
        catch (IOException e) {
            // In the words of MoppyLib's author when they deal with this exception, "There's not
            // much we can do if it fails to close (it's probably already closed). Just log it and move on"
            Log.e(MoppyMediaService.class.getName() + "->onRemoveDevice", "Unable to close bridge", e);
            return;
        } // End try {closeBridge} catch(IOException e)

        int numConnected = moppyManager.getUsbManager().getNumberConnected();
        notificationBuilder.setContentText(numConnected + " device" + ((numConnected == 1) ? "" : "s") + " connected");
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    } // End onRemoveDevice method

    // Callbacks for media button events. All callbacks are disabled if their associated action is not supported
    private class MediaCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY) == 0) { return; }
            if (mediaController.getMetadata() == null) { return; } // Do nothing unless file loaded

            // Update notification
            playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    mediaController.getPlaybackState().getPosition(),
                    1
            );
            togglePlayPauseMediaButton(false, mediaController.getMetadata());

            moppyManager.play();
            super.onPlay();
        } // End onPlay method

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY) == 0) { return; }

            // Create the MIDI library if needed and retrieve the requested file
            if (midiLibrary == null) {
                // Don't want to hold up UI thread creating a MidiLibrary that may not be created successfully,
                // so we will just trigger creation and skip the play event
                MidiLibrary.getMIDILibraryAsync(
                        MoppyMediaService.this,
                        midiLibraryResult -> MoppyMediaService.this.midiLibrary = midiLibraryResult
                );
                return;
            }

            MidiLibrary.MapNode node = midiLibrary.get(mediaId);
            if (!(node instanceof MidiLibrary.MidiFile)) { return; }

            // Load in the file's metadata, and update the notification and playback state
            mediaSession.setMetadata(node.getMetadata());
            playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    0,
                    1
            );
            togglePlayPauseMediaButton(true, node.getMetadata());

            try {
                moppyManager.load((MidiLibrary.MidiFile) node, MoppyMediaService.this);
                currentSequencerLength = moppyManager.getMillisecondsLength();
                moppyManager.play();
            }
            catch (IOException | InvalidMidiDataException e) {
                Log.e(MediaCallback.class.getName() + "->onPlayFromMediaID", "Unable to load file", e);
                Intent failureIntent = new Intent(RESPONSE_LOAD_FAILED);
                failureIntent.putExtra(EXTRA_MEDIA_METADATA, node.getMetadata());
                failureIntent.putExtra(EXTRA_EXCEPTION, e);
                localBroadcastManager.sendBroadcast(failureIntent);
            }
            super.onPlayFromMediaId(mediaId, extras);
        } // End onPlayFromMediaId method

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY) == 0) { return; }

            MidiLibrary.MidiFile file;
            String mediaFocus = extras.getString(MediaStore.EXTRA_MEDIA_FOCUS);

            // Create the MIDI library if needed and retrieve the requested file
            if (midiLibrary == null) {
                // Don't want to hold up UI thread creating a MidiLibrary that may not be created successfully,
                // so we will just trigger creation and skip the play event
                MidiLibrary.getMIDILibraryAsync(
                        MoppyMediaService.this,
                        midiLibraryResult -> MoppyMediaService.this.midiLibrary = midiLibraryResult
                );
                return;
            }

            // Interpret the search parameters to select the file to load
            if (query != null && mediaFocus == null) { mediaFocus = ""; } // Set to unstructured
            if (query == null) { // 'Any' request
                // TODO: Play last played song here
                return;
            }
            switch (mediaFocus) { // https://developer.android.com/guide/components/intents-common#PlaySearch
                case MediaStore.Audio.Genres.ENTRY_CONTENT_TYPE: // 'Genre' request
                    // Unsupported
                    return;
                case MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE: // 'Artist' request
                    // TODO: Construct queue of songs by the artist
                    return;
                case MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE: // 'Album' request
                    // TODO: Construct queue of songs in the album
                    return;
                case MediaStore.Audio.Media.ENTRY_CONTENT_TYPE: // 'Song' request
                    file = midiLibrary.searchFileFuzzy(extras.getString(MediaStore.EXTRA_MEDIA_TITLE));
                    break;
                case MediaStore.Audio.Playlists.ENTRY_CONTENT_TYPE: // 'Playlist' request
                    // Unsupported as of yet
                    return;
                default: // 'Unstructured' request
                    // Assume query is the song name, if not then we simply fail to load
                    file = midiLibrary.searchFileFuzzy(query);
                    break;
            }
            if (file == null) {
                Log.i(MediaCallback.class.getName() + "->onPlayFromSearch", "Query not found");
                return;
            }

            // Load in the file's metadata, and update the notification and playback state
            mediaSession.setMetadata(file.getMetadata());
            playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    0,
                    1
            );
            togglePlayPauseMediaButton(true, file.getMetadata());

            try {
                moppyManager.load(file, MoppyMediaService.this);
                currentSequencerLength = moppyManager.getMillisecondsLength();
                moppyManager.play();
            }
            catch (IOException | InvalidMidiDataException e) {
                Log.e(MediaCallback.class.getName() + "->onPlayFromSearch", "Unable to load file", e);
                Intent failureIntent = new Intent(RESPONSE_LOAD_FAILED);
                failureIntent.putExtra(EXTRA_MEDIA_METADATA, file.getMetadata());
                failureIntent.putExtra(EXTRA_EXCEPTION, e);
                localBroadcastManager.sendBroadcast(failureIntent);
            }
            super.onPlayFromSearch(query, extras);
        }

        @Override
        public void onPause() {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PAUSE) == 0) { return; }

            // Update notification and playback state
            playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PAUSED,
                    mediaController.getPlaybackState().getPosition(),
                    1
            );
            togglePlayPauseMediaButton(false, null);

            moppyManager.pause();
            super.onPause();
        } // End onPause method

        @Override
        public void onStop() {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_STOP) == 0) { return; }

            // Update notification and playback state
            if (mediaController.getMetadata() != null) {
                playbackStateBuilder.setState(
                        PlaybackStateCompat.STATE_STOPPED,
                        0,
                        1
                );
                togglePlayPauseMediaButton(false, null);
            }

            moppyManager.stop();
            super.onStop();
        } // End onStop method

        @Override
        public void onSeekTo(long position) { // in milliseconds
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_SEEK_TO) == 0) { return; }

            // Update the playback state. Raw position value not used, it is instead mapped to within
            // the set { 0 ≤ position ≤ duration }
            long duration = mediaController.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
            playbackStateBuilder.setState(
                    playbackState.getState(),
                    Math.max(0, Math.min(position, duration)),
                    playbackState.getPlaybackSpeed()
            );
            mediaSession.setPlaybackState(playbackStateBuilder.build());

            // Seek the sequencer to position if it is lower than the length, otherwise seek to the end
            moppyManager.seekTo(position, playbackState.getState() == PlaybackStateCompat.STATE_PLAYING);

            super.onSeekTo(position);
        } // End onSeekTo method

        /**
         * Called when a {@link MediaControllerCompat} wants a
         * {@link PlaybackStateCompat.CustomAction} to be performed.
         *
         * @param action The action that was originally sent in the
         *               {@link PlaybackStateCompat.CustomAction}.
         * @param extras Optional extras specified by the
         *               {@link MediaControllerCompat}.
         */
        @Override
        public void onCustomAction(String action, Bundle extras) {
            /* Custom media session actions currently unused
            switch (action) {

            }*/
            super.onCustomAction(action, extras);
        } // End onCustomAction method
    } // End MediaCallback class

    // Callback for MoppyManager events
    private class MoppyCallback extends MoppyManager.Callback {
        @Override
        void onSongEnd(boolean reset) {
            PlaybackStateCompat currentState = mediaController.getPlaybackState();
            if (
                    mediaController.getMetadata() != null &&
                    (currentState.getState() == PlaybackStateCompat.STATE_PLAYING ||
                     currentState.getState() == PlaybackStateCompat.STATE_PAUSED)
            ) {
                // TODO: Advance to next song if available
                playbackStateBuilder.setState(
                        PlaybackStateCompat.STATE_STOPPED,
                        (reset ? 0 : mediaController.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION)),
                        1
                );
                mediaSession.setPlaybackState(playbackStateBuilder.build());
                togglePlayPauseMediaButton(false, null);
            } // End if(fileLoaded && currentState == PLAYING | PAUSED)
            super.onSongEnd(reset);
        } // End onSongEnd method
    } // End MoppyCallback class
} // End MoppyMediaService class

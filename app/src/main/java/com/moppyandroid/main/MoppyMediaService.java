package com.moppyandroid.main;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import com.moppy.core.comms.bridge.BridgeSerial;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;

/**
 * The media service that controls Moppy playback.
 * <p>
 * Control of Moppy is offered through
 * {@link #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction},
 * while playback control is offered through {@link android.media.session.MediaController}. As with any
 * {@link androidx.media.MediaBrowserServiceCompat}, content is available through
 * {@link #onLoadChildren(String, androidx.media.MediaBrowserServiceCompat.Result) onLoadChildren},
 * and loading can be done either through {@link #ACTION_LOAD_ITEM} or the {@code MediaController}, with the
 * main advantage of {@code ACTION_LOAD_ITEM} being that the user can supply callbacks for success and failure,
 * while loading a file through the {@code MediaController} is fire-and-forget.
 * </p>
 */
public class MoppyMediaService extends MediaBrowserServiceCompat {
    /**
     * The ID for the media service notification channel.
     */
    public static final String CHANNEL_ID = "MoppyMediaServiceChannel";

    /**
     * The custom action for adding a Moppy device.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr><td>{@link #EXTRA_PORT_NAME}</td><td>{@link String}</td><td>The provided port name</td></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_NUM_CONNECTED}</td>
     *             <td>{@code int}</td>
     *             <td>The number of currently connected devices</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_ADD_DEVICE = "com.moppyandroid.main.MoppyMediaService.ADD_DEVICE";
    /**
     * The custom action for removing a Moppy device.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr><td>{@link #EXTRA_PORT_NAME}</td><td>{@link String}</td><td>The provided port name</td></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_NUM_CONNECTED}</td>
     *             <td>{@code int}</td>
     *             <td>The number of currently connected devices</td>
     *         </tr>
     *     </table>
     * {@link #EXTRA_ERROR_INFORMATIONAL} may be {@code true} if the device wasn't connected or an
     * {@link IOException} was raised while trying to disconnect.
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_REMOVE_DEVICE = "com.moppyandroid.main.MoppyMediaService.REMOVE_DEVICE";
    /**
     * The custom action for triggering media library creation if not done so already. Strongly recommended to send
     * this action after {@link Manifest.permission#READ_EXTERNAL_STORAGE} has been granted to the {@link android.app.Activity}.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_LIBRARY_CREATED}</td>
     *             <td>{@code boolean}</td>
     *             <td>{@code true} if the library already exists or was created, or {@code false} if creation failed</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_INIT_LIBRARY = "com.moppyandroid.main.MoppyMediaService.INIT_LIBRARY";
    /**
     * The custom action for refreshing the available USB devices. Replicates {@link #ACTION_GET_DEVICES} upon completion.
     * Guaranteed to not send an error.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_DEVICE_NAMES}</td>
     *             <td>{@link ArrayList}{@literal <}{@link String}{@literal >}</td>
     *             <td>The list of available port/device names</td>
     *         </tr>
     *         <tr><td>(Result only) {@link #EXTRA_NUM_CONNECTED}</td><td>{@code int}</td><td>The number of currently connected devices</td></tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_REFRESH_DEVICES = "com.moppyandroid.main.MoppyMediaService.REFRESH_DEVICES";
    /**
     * The custom action for refreshing the available USB devices. Guaranteed to not send an error.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_DEVICE_NAMES}</td>
     *             <td>{@link ArrayList}{@code {@literal <}{@link String}{@literal >}}</td>
     *             <td>The list of available port/device names</td>
     *         </tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_DEVICE_INFOS}</td>
     *             <td>{@link ArrayList}{@code {@literal <ArrayList<}{@link String}{@literal>>}}</td>
     *             <td>The list of device information lists</td>
     *         </tr>
     *         <tr><td>(Result only) {@link #EXTRA_NUM_CONNECTED}</td><td>{@code int}</td><td>The number of currently connected devices</td></tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_GET_DEVICES = "com.moppyandroid.main.MoppyMediaService.GET_DEVICES";
    /**
     * The custom action for loading a file.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr><td>{@link #EXTRA_MEDIA_ID}</td><td>{@link String}</td><td>The media ID to load</td></tr>
     *         <tr><td>(Optional) {@link #EXTRA_PLAY}</td><td>{@code boolean}</td><td>Start playing after loading finished</td></tr>
     *     </table>
     * </p>
     *
     * @see #onLoadChildren(String, androidx.media.MediaBrowserServiceCompat.Result) onLoadChildren
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_LOAD_ITEM = "com.moppyandroid.main.MoppyMediaService.LOAD_FILE";

    /**
     * {@link String} extra field for the port name associated with a {@link #ACTION_ADD_DEVICE} or {@link #ACTION_REMOVE_DEVICE} event.
     */
    public static final String EXTRA_PORT_NAME = "MOPPY_EXTRA_DEVICE_PORT_NAME";
    /**
     * {@code boolean} extra field for if the {@link MidiLibrary} was created successfully.
     */
    public static final String EXTRA_LIBRARY_CREATED = "MOPPY_LIBRARY_CREATED";
    /**
     * {@link ArrayList} extra field for the available device names.
     */
    public static final String EXTRA_DEVICE_NAMES = "MOPPY_DEVICE_NAMES";
    /**
     * {@link ArrayList}{@code {@literal <ArrayList<String>}} extra field for the information available for each device.
     *
     * @see MoppyUsbManager#getDeviceInfoForAll()
     */
    public static final String EXTRA_DEVICE_INFOS = "MOPPY_DEVICE_INFO_FOR_ALL";
    /**
     * {@code int} extra field for the number of connected devices.
     */
    public static final String EXTRA_NUM_CONNECTED = "MOPPY_NUMBER_CONNECTED";
    /**
     * {@link String} extra field for the media ID of the file to load.
     */
    public static final String EXTRA_MEDIA_ID = "MOPPY_MEDIA_ITEM";
    /**
     * {@code boolean} extra field for if the sequencer should be started if file loaded successfully.
     * If an action reads this extra, {@code null} represents {@code false}.
     */
    public static final String EXTRA_PLAY = "MOPPY_PLAY";
    /**
     * {@link Exception} extra field for a bundled exception.
     */
    public static final String EXTRA_EXCEPTION = "MOPPY_EXTRA_EXCEPTION";
    /**
     * {@link String} extra field for the reason an error was raised.
     */
    public static final String EXTRA_ERROR_REASON = "MOPPY_ERROR_REASON";
    /**
     * {@code boolean} extra field for if the error is informational and can be ignored.
     */
    public static final String EXTRA_ERROR_INFORMATIONAL = "MOPPY_ERROR_INFORMATIONAL";

    private static final int NOTIFICATION_ID = 1;               // ID of the service notification
    private static final int NOTIFICATION_PLAY_PAUSE_INDEX = 2; // Index of play/pause button in notification actions

    private NotificationManager notificationManager;
    private MediaControllerCompat mediaController;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder playbackStateBuilder;
    private NotificationCompat.Builder notificationBuilder;
    private MidiLibrary midiLibrary;
    private MoppyManager moppyManager;

    /**
     * Triggered when the service is first created, usually by the first {@link Context#startService(Intent)}
     * call. Always triggered before {@link #onStartCommand(Intent, int, int)}.
     */
    @Override
    public void onCreate() {
        super.onCreate();

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
                .addCustomAction("initLibrary", "initLibrary", R.drawable.ic_folder)
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
        midiLibrary = MidiLibrary.getMidiLibrary(this);
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
     * @param result        where the list of {@link android.support.v4.media.MediaBrowserCompat.MediaItem}s
     *                      contained in the parent node is sent to
     * @see #onGetRoot(String, int, Bundle)
     */
    @Override
    public void onLoadChildren(final String parentMediaId, final Result<List<MediaBrowserCompat.MediaItem>> result) {
        List<MediaBrowserCompat.MediaItem> mediaItems = new ArrayList<>();

        // If the MIDI library isn't loaded yet, Start another thread to load it and recall this method
        if (midiLibrary == null) {
            result.detach();
            MidiLibrary.getMidiLibraryAsync(
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
     * Called to request a custom action to this service. Primarily invoked with
     * {@link MediaBrowserCompat#sendCustomAction(String, Bundle, MediaBrowserCompat.CustomActionCallback)}.
     * <p>
     * Available actions:
     * <ul>
     *     <li>{@link #ACTION_ADD_DEVICE}</li>
     *     <li>{@link #ACTION_GET_DEVICES}</li>
     *     <li>{@link #ACTION_INIT_LIBRARY}</li>
     *     <li>{@link #ACTION_LOAD_ITEM}</li>
     *     <li>{@link #ACTION_REFRESH_DEVICES}</li>
     *     <li>{@link #ACTION_REMOVE_DEVICE}</li>
     * </ul>
     * </p>
     * <p>
     * Standard {@link Bundle} fields sent upon encountering an error and calling {@code result.sendError}:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr><td>{@link #EXTRA_ERROR_REASON}</td><td>{@link String}</td><td>The reason for the error</td></tr>
     *         <tr>
     *             <td>{@link #EXTRA_ERROR_INFORMATIONAL}</td>
     *             <td>{@code boolean}</td>
     *             <td>If the error is informational and can safely be ignored</td>
     *         </tr>
     *         <tr>
     *             <td>(Optional) {@link #EXTRA_EXCEPTION}</td>
     *             <td>{@link Exception}</td>
     *             <td>{@code Exception} that is associated with how the error occurred</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @param action The custom action sent from the media browser
     * @param extras The bundle of arguments sent from the media browser
     * @param result The {@link androidx.media.MediaBrowserServiceCompat.Result} to send the result of the requested custom action
     */
    @Override
    public void onCustomAction(@NonNull String action, Bundle extras, @NonNull Result<Bundle> result) {
        switch (action) {
            case ACTION_ADD_DEVICE: {
                onAddDevice(extras, result);
                break;
            } // End ACTION_ADD_DEVICE case
            case ACTION_REMOVE_DEVICE: {
                onRemoveDevice(extras, result);
                break;
            } // End ACTION_REMOVE_DEVICE case
            case ACTION_INIT_LIBRARY: {
                onInitLibrary(result);
                break;
            } // End ACTION_INIT_LIBRARY case
            case ACTION_REFRESH_DEVICES: {
                onRefreshDevices(result);
                break;
            }
            case ACTION_GET_DEVICES: {
                onGetDevices(result);
                break;
            }
            case ACTION_LOAD_ITEM: {
                onLoadAction(extras, result);
            }
            default: {
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "No matching action");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                result.sendError(errorBundle);
                break;
            }
        }
        //super.onCustomAction(action, extras, result); // Just calls result.sendError(null);
    }

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

    // Triggered by ACTION_ADD_DEVICE
    private void onAddDevice(Bundle extras, Result<Bundle> result) {
        if (extras == null || extras.getString(EXTRA_PORT_NAME) == null) {
            Log.w(MoppyMediaService.class.getName() + "->onAddDevice", "No port name supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No EXTRA_PORT_NAME supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            result.sendError(errorBundle);
            return;
        } // End if(EXTRA_PORT_NAME == null)
        String portName = extras.getString(EXTRA_PORT_NAME);

        try { moppyManager.getUsbManager().connectBridge(portName); }
        catch (IllegalArgumentException | BridgeSerial.UnableToObtainDeviceException | IOException e) {
            Log.e(MoppyMediaService.class.getName() + "->onAddDevice", "Unable to connect bridge", e);
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "Unable to connect to bridge " + portName);
            errorBundle.putSerializable(EXTRA_EXCEPTION, e);
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false); // BridgeSerial not working or device list de-synced?
            errorBundle.putString(EXTRA_PORT_NAME, portName);
            result.sendError(errorBundle);
            return;
        } // End try {connectBridge} catch(IllegalArgument|UnableToObtainDevice|IO Exception)

        int numConnected = moppyManager.getUsbManager().getNumberConnected();
        notificationBuilder.setContentText(numConnected + " device" + ((numConnected == 1) ? "" : "s") + " connected");
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        Bundle resultBundle = new Bundle();
        resultBundle.putString(EXTRA_PORT_NAME, portName);
        resultBundle.putInt(EXTRA_NUM_CONNECTED, numConnected);
        result.sendResult(resultBundle);
    } // End onAddDevice method

    // Triggered by ACTION_REMOVE_DEVICE
    private void onRemoveDevice(Bundle extras, Result<Bundle> result) {
        if (extras == null || extras.getString(EXTRA_PORT_NAME) == null) {
            Log.w(MoppyMediaService.class.getName() + "->onRemoveDevice", "No port name supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No EXTRA_PORT_NAME supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            result.sendError(errorBundle);
            return;
        } // End if(EXTRA_PORT_NAME == null)
        String portName = extras.getString(EXTRA_PORT_NAME);

        if (!moppyManager.getUsbManager().isConnected(portName)) {
            Log.w(MoppyMediaService.class.getName() + "->onRemoveDevice", "Attempted to close unconnected bridge");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "Not connected to " + portName);
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, true); // Browser wanted to disconnect device and it isn't connected, no real problem here
            errorBundle.putString(EXTRA_PORT_NAME, portName);
            result.sendError(errorBundle);
            return;
        } // End if(!connected(portName))

        try {moppyManager.getUsbManager().closeBridge(portName);}
        catch (IOException e) {
            // In the words of MoppyLib's author when they deal with this exception, "There's not
            // much we can do if it fails to close (it's probably already closed). Just log it and move on"
            Log.w(MoppyMediaService.class.getName() + "->onRemoveDevice", "Unable to close bridge", e);
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "Unable to close bridge");
            errorBundle.putSerializable(EXTRA_EXCEPTION, e);
            // Device likely couldn't be disconnected because it isn't physically connected, nothing we can do
            // It has been removed from the connected devices lists already so there isn't any lasting problems either
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, true);
            errorBundle.putString(EXTRA_PORT_NAME, portName);
            result.sendError(errorBundle);
            return;
        } // End try {closeBridge} catch(IOException e)

        int numConnected = moppyManager.getUsbManager().getNumberConnected();
        notificationBuilder.setContentText(numConnected + " device" + ((numConnected == 1) ? "" : "s") + " connected");
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        Bundle resultBundle = new Bundle();
        resultBundle.putString(EXTRA_PORT_NAME, portName);
        resultBundle.putInt(EXTRA_NUM_CONNECTED, numConnected);
        result.sendResult(resultBundle);
    } // End onRemoveDevice method

    // Triggered by ACTION_INIT_LIBRARY
    private void onInitLibrary(Result<Bundle> result) {
        if (midiLibrary == null) {
            result.detach();
            MidiLibrary.getMidiLibraryAsync(this, (midiLibrary) -> {
                this.midiLibrary = midiLibrary;
                Bundle resultBundle = new Bundle();
                resultBundle.putBoolean(EXTRA_LIBRARY_CREATED, midiLibrary != null);
                if (midiLibrary == null) {
                    resultBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false); // Permission not available?
                    result.sendError(resultBundle);
                }
                else { result.sendResult(resultBundle); }
            }); // End MidiLibrary.Callback lambda
        } // End if(midiLibrary == null)
        else {
            Bundle resultBundle = new Bundle();
            resultBundle.putBoolean(EXTRA_LIBRARY_CREATED, true);
            result.sendResult(resultBundle);
        } // End if(midiLibrary == null) {} else
    } // End onInitLibrary method

    // Triggered by ACTION_REFRESH_DEVICES
    private void onRefreshDevices(Result<Bundle> result) {
        moppyManager.getUsbManager().refreshDeviceList();
        int numConnected = moppyManager.getUsbManager().getNumberConnected();
        notificationBuilder.setContentText(numConnected + " device" + ((numConnected == 1) ? "" : "s") + " connected");
        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        onGetDevices(result); // Add the new device list and send the result
    }

    // Triggered by ACTION_GET_DEVICES
    private void onGetDevices(Result<Bundle> result) {
        Bundle resultBundle = new Bundle();

        // Get the device names from the device infos just in case a race de-syncs the results of
        // MoppyUsbManager.getDeviceInfoForAll() and MoppyUsbManager.getDevices()
        ArrayList<ArrayList<String>> deviceInfos = moppyManager.getUsbManager().getDeviceInfoForAll();
        ArrayList<String> deviceNames = new ArrayList<>();
        for (ArrayList<String> deviceInfo : deviceInfos) { deviceNames.add(deviceInfo.get(0)); }

        resultBundle.putStringArrayList(EXTRA_DEVICE_NAMES, deviceNames);
        resultBundle.putSerializable(EXTRA_DEVICE_INFOS, moppyManager.getUsbManager().getDeviceInfoForAll());
        resultBundle.putInt(EXTRA_NUM_CONNECTED, moppyManager.getUsbManager().getNumberConnected());
        result.sendResult(resultBundle);
    }

    // Triggered by ACTION_LOAD_ITEM. Essentially MediaCallback.onPlayFromMediaId with callbacks to result.
    private void onLoadAction(Bundle extras, Result<Bundle> result) {
        if (extras == null || extras.getString(EXTRA_MEDIA_ID) == null) {
            Log.w(MoppyMediaService.class.getName() + "->onLoadAction", "No media item supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No EXTRA_MEDIA_ITEM supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            result.sendError(errorBundle);
            return;
        }
        String mediaId = extras.getString(EXTRA_MEDIA_ID);

        PlaybackStateCompat playbackState = mediaController.getPlaybackState();
        if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY) == 0) { return; }

        // Create the MIDI library if needed and retrieve the requested file
        if (midiLibrary == null) {
            // Don't want to hold up UI thread creating a MidiLibrary that may not be created successfully,
            // so we will just trigger creation and if failed send an error
            result.detach();
            MidiLibrary.getMidiLibraryAsync(MoppyMediaService.this, (midiLibraryResult) -> {
                if (midiLibraryResult == null) {
                    Bundle errorBundle = new Bundle();
                    errorBundle.putString(EXTRA_ERROR_REASON, "Unable to create MIDI Library");
                    // How did the Browser get a media ID if no library exists and can't be created? Something wacky here
                    errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                    errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
                    result.sendError(errorBundle);
                    return;
                } // End if(midiLibraryResult == null)
                MoppyMediaService.this.midiLibrary = midiLibraryResult;
                onLoadAction(extras, result); // Retry
            }); // End MidiLibrary.Callback lambda
            return;
        } // End if(midiLibrary == null)
        MidiLibrary.MapNode node = midiLibrary.get(mediaId);
        if (!(node instanceof MidiLibrary.MidiFile)) {
            Log.w(MoppyMediaService.class.getName() + "->onLoadAction", "File " + mediaId + " doesn't exist");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "The media ID " + mediaId + " does not correspond to a MidiLibray.MidiFile");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false); // Browser item list bad?
            errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
            result.sendError(errorBundle);
            return;
        } // End if(node ∉ MidiFile)

        // Sequence loading done before media session loading in case the file is invalid or can't be read
        try {
            moppyManager.load((MidiLibrary.MidiFile) node, MoppyMediaService.this);
            moppyManager.play();
        } // End try {load(node); play}
        catch (InvalidMidiDataException | IOException e) {
            Log.e(MoppyMediaService.class.getName() + "->onLoadAction", "Unable to load file" + mediaId, e);
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "Loading failed for media ID " + mediaId);
            errorBundle.putSerializable(EXTRA_EXCEPTION, e);
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false); // File bad or no read access?
            errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
            result.sendError(errorBundle);
            return;
        } // End try {load(node); play} catch(InvalidMidiData|IO Exception)

        // Load in the file's metadata, and update the notification and playback state
        mediaSession.setMetadata(node.getMetadata());
        playbackStateBuilder.setState(
                PlaybackStateCompat.STATE_PLAYING,
                0,
                1
        );
        togglePlayPauseMediaButton(true, node.getMetadata());

        Bundle resultBundle = new Bundle();
        resultBundle.putString(EXTRA_MEDIA_ID, mediaId);
        result.sendResult(resultBundle);
    }

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
                MidiLibrary.getMidiLibraryAsync(
                        MoppyMediaService.this,
                        midiLibraryResult -> MoppyMediaService.this.midiLibrary = midiLibraryResult
                );
                return;
            } // End if(midiLibrary == null)
            MidiLibrary.MapNode node = midiLibrary.get(mediaId);
            if (!(node instanceof MidiLibrary.MidiFile)) { return; }

            // Sequence loading done before media session loading in case the file is invalid or can't be read
            try {
                moppyManager.load((MidiLibrary.MidiFile) node, MoppyMediaService.this);
                moppyManager.play();
            } // End try {load(node); play}
            catch (InvalidMidiDataException | IOException e) {
                Log.e(MediaCallback.class.getName() + "->onPlayFromMediaID", "Unable to load file", e);
                return;
            } // End try {load(node); play} catch(InvalidMidiData|IO Exception)

            // Load in the file's metadata, and update the notification and playback state
            mediaSession.setMetadata(node.getMetadata());
            playbackStateBuilder.setState(
                    PlaybackStateCompat.STATE_PLAYING,
                    0,
                    1
            );
            togglePlayPauseMediaButton(true, node.getMetadata());

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
                MidiLibrary.getMidiLibraryAsync(
                        MoppyMediaService.this,
                        midiLibraryResult -> MoppyMediaService.this.midiLibrary = midiLibraryResult
                );
                return;
            } // End if(midiLibrary == null)

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
            } // End switch(mediaFocus)
            if (file == null) {
                Log.i(MediaCallback.class.getName() + "->onPlayFromSearch", "Query not found");
                return;
            }

            // Load the file by its media ID
            onPlayFromMediaId(file.getMetadata().getDescription().getMediaId(), extras);
            super.onPlayFromSearch(query, extras);
        } // End onPlayFromSearch method

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
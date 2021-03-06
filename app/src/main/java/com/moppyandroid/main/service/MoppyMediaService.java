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

package com.moppyandroid.main.service;

import android.annotation.SuppressLint;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.hardware.usb.UsbDevice;
import android.media.midi.MidiDevice;
import android.media.midi.MidiDeviceInfo;
import android.media.midi.MidiInputPort;
import android.media.midi.MidiManager;
import android.media.midi.MidiOutputPort;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.MediaStore;
import android.support.v4.media.MediaBrowserCompat;
import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaControllerCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.MediaSessionCompat.QueueItem;
import android.support.v4.media.session.PlaybackStateCompat;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.media.MediaBrowserServiceCompat;
import androidx.media.session.MediaButtonReceiver;

import com.github.noahr_atc.midisplitter.MidiProcessor;
import com.moppy.core.comms.bridge.BridgeSerial;
import com.moppyandroid.main.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;
import jp.kshoji.javax.sound.midi.Receiver;
import jp.kshoji.javax.sound.midi.Sequence;

/**
 * The media service that controls Moppy playback.
 * <p>
 * Control of Moppy is offered through
 * {@link #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction},
 * while playback control is offered through {@link android.media.session.MediaController}. As with any
 * {@link androidx.media.MediaBrowserServiceCompat}, content is available through
 * {@link #onLoadChildren(String, androidx.media.MediaBrowserServiceCompat.Result) onLoadChildren},
 * and loading can be done either through {@link #ACTION_LOAD_ITEM} or the {@link android.support.v4.media.session.MediaControllerCompat}, with the
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
    public static final String ACTION_ADD_DEVICE = "com.moppyandroid.main.service.MoppyMediaService.ADD_DEVICE";
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
    public static final String ACTION_REMOVE_DEVICE = "com.moppyandroid.main.service.MoppyMediaService.REMOVE_DEVICE";
    /**
     * The custom action for setting the MIDI device to receive input from.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr>
     *             <td>{@link #EXTRA_MIDI_IN_DEVICE}</td>
     *             <td>{@link MidiPortInfoWrapper} ({@link android.os.Parcelable})</td>
     *             <td>The MIDI port to connect to</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_SET_MIDI_IN = "com.moppyandroid.main.service.MoppyMediaService.ACTION_SET_MIDI_IN_DEVICE";
    /**
     * The custom action for setting the MIDI device to send output to.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr>
     *             <td>{@link #EXTRA_MIDI_OUT_DEVICE}</td>
     *             <td>{@link MidiPortInfoWrapper} ({@link android.os.Parcelable})</td>
     *             <td>The MIDI port to connect to</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_SET_MIDI_OUT = "com.moppyandroid.main.service.MoppyMediaService.ACTION_SET_MIDI_OUT_DEVICE";
    /**
     * The custom action for setting the service to intercept MIDI messages from the MIDI input devices
     * and split up chords to distribute their notes across available MIDI channels.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr>
     *             <td>{@link #EXTRA_MIDI_SPLIT_ENABLE}</td>
     *             <td>{@code boolean}</td>
     *             <td>Whether or not to enable MIDI note splitting on the MIDI input devices</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_SET_MIDI_SPLIT = "com.moppyandroid.main.service.MoppyMediaService.ACTION_SET_MIDI_SPLIT";
    /**
     * The custom action for triggering media library creation if not done so already. Strongly recommended to send
     * this action after {@link android.Manifest.permission#READ_EXTERNAL_STORAGE} has been granted to the {@link android.app.Activity}.
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
    public static final String ACTION_INIT_LIBRARY = "com.moppyandroid.main.service.MoppyMediaService.INIT_LIBRARY";
    /**
     * The custom action for refreshing the available USB devices. Replicates {@link #ACTION_GET_DEVICES} upon completion.
     * Guaranteed to not send an error.
     * <p>
     * Non-standard {@link Bundle} fields: see {@link #ACTION_GET_DEVICES}
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_REFRESH_DEVICES = "com.moppyandroid.main.service.MoppyMediaService.REFRESH_DEVICES";
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
     *             <td>{@link ArrayList}{@code <}{@link UsbDevice}{@code >}</td>
     *             <td>The list of device information lists</td>
     *         </tr>
     *         <tr><td>(Result only) {@link #EXTRA_NUM_CONNECTED}</td><td>{@code int}</td><td>The number of currently connected devices</td></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_DEVICES_CONNECTED}</td>
     *             <td>{@link ArrayList}{@code <}{@link String}{@code >}</td>
     *             <td>The list of connected device identifiers</td>
     *         </tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_MIDI_IN_DEVICE}</td>
     *             <td>{@link MidiPortInfoWrapper} ({@link android.os.Parcelable})</td>
     *             <td>The currently connected MIDI in device, or null if none connected</td>
     *         </tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_MIDI_OUT_DEVICE}</td>
     *             <td>{@link MidiPortInfoWrapper} ({@link android.os.Parcelable})</td>
     *             <td>The currently connected MIDI out device, or null if none connected</td>
     *         </tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_MIDI_SPLIT_ENABLE}</td>
     *             <td>{@code boolean}</td>
     *             <td>Whether or not MIDI note splitting on the MIDI input devices are enabled</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_GET_DEVICES = "com.moppyandroid.main.service.MoppyMediaService.GET_DEVICES";
    /**
     * The custom action for loading a file.
     * <p>
     * Non-standard {@link Bundle} fields required:
     *     <table border="1">
     *         <tr><th>KEY</th><th>TYPE</th><th>VALUE</th></tr>
     *         <tr><td>{@link #EXTRA_MEDIA_ID}</td><td>{@link String}</td><td>The media ID to load</td></tr>
     *         <tr><td>(Optional) {@link #EXTRA_PLAY}</td><td>{@code boolean}</td><td>Start playing after loading finished</td></tr>
     *         <tr>
     *             <td>(Result only) {@link #EXTRA_MEDIA_MIDI_FILE}</td>
     *             <td>{@link com.moppyandroid.main.service.MidiLibrary.MidiFile} ({@link android.os.Parcelable})</td>
     *             <td>Information about the MIDI file that was loaded</td>
     *         </tr>
     *     </table>
     * </p>
     *
     * @see #onLoadChildren(String, androidx.media.MediaBrowserServiceCompat.Result) onLoadChildren
     * @see #onCustomAction(String, Bundle, androidx.media.MediaBrowserServiceCompat.Result) onCustomAction
     */
    public static final String ACTION_LOAD_ITEM = "com.moppyandroid.main.service.MoppyMediaService.LOAD_FILE";

    /**
     * {@code boolean} extra used to allow custom binding without using {@link MediaBrowserServiceCompat}.
     */
    public static final String EXTRA_BIND_NORMAL = "MOPPY_BIND_NORMAL";
    /**
     * {@link String} extra field for the port name associated with a {@link #ACTION_ADD_DEVICE} or {@link #ACTION_REMOVE_DEVICE} event.
     */
    public static final String EXTRA_PORT_NAME = "MOPPY_EXTRA_MOPPY_DEVICE_PORT_NAME";
    /**
     * {@link MidiPortInfoWrapper} extra field (stored as {@link android.os.Parcelable}) for the MIDI
     * device associated with an {@link #ACTION_SET_MIDI_IN} event.
     */
    public static final String EXTRA_MIDI_IN_DEVICE = "MOPPY_EXTRA_MIDI_IN_DEVICE";
    /**
     * {@link MidiPortInfoWrapper} extra field (stored as {@link android.os.Parcelable}) for the MIDI
     * device associated with an {@link #ACTION_SET_MIDI_OUT} event.
     */
    public static final String EXTRA_MIDI_OUT_DEVICE = "MOPPY_EXTRA_MIDI_OUT_DEVICE";
    /**
     * {@code boolean} extra field for whether or not to split incoming MIDI notes.
     */
    public static final String EXTRA_MIDI_SPLIT_ENABLE = "MOPPY_EXTRA_SPLIT_MIDI";
    /**
     * {@code boolean} extra field for if the {@link MidiLibrary} was created successfully.
     */
    public static final String EXTRA_LIBRARY_CREATED = "MOPPY_LIBRARY_CREATED";
    /**
     * {@link ArrayList}{@code <}{@link String}{@code >} extra field for the available device names.
     */
    public static final String EXTRA_DEVICE_NAMES = "MOPPY_DEVICE_NAMES";
    /**
     * {@link ArrayList}{@code <}{@link UsbDevice}{@code >} (stored as
     * {@code ArrayList<}{@link android.os.Parcelable}){@code >}) extra field containing all available devices.
     *
     * @see MoppyUsbManager#getDeviceInfoForAll()
     */
    public static final String EXTRA_DEVICE_INFOS = "MOPPY_DEVICE_INFO_FOR_ALL";
    /**
     * {@code int} extra field for the number of connected devices.
     */
    public static final String EXTRA_NUM_CONNECTED = "MOPPY_NUMBER_CONNECTED";
    /**
     * {@link String} extra field for the identifiers of connected devices.
     */
    public static final String EXTRA_DEVICES_CONNECTED = "MOPPY_NUMBER_CONNECTED";
    /**
     * {@link String} extra field for the media ID of the file to load.
     */
    public static final String EXTRA_MEDIA_ID = "MOPPY_MEDIA_ITEM";
    /**
     * {@link com.moppyandroid.main.service.MidiLibrary.MidiFile} extra field (stored as {@link android.os.Parcelable})
     * for the MIDI file loaded in an {@link #ACTION_LOAD_ITEM} event.
     */
    public static final String EXTRA_MEDIA_MIDI_FILE = "MOPPY_EXTRA_MIDI_OUT_DEVICE";
    /**
     * {@code boolean} extra field for if the sequencer should be started if file loaded successfully.
     * If an action reads this extra, {@code null} represents {@code false}.
     *
     * @see #load(String, boolean)
     */
    public static final String EXTRA_PLAY = "MOPPY_PLAY";
    /**
     * {@link Exception} extra field (stored as {@link Serializable}) for a bundled exception.
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

    private static final String TAG = MoppyMediaService.class.getName();
    private static final int NOTIFICATION_ID = 1;               // ID of the service notification
    private static final int NOTIFICATION_PLAY_PAUSE_INDEX = 2; // Index of play/pause button in notification actions

    private boolean splittingMidi = false;
    private MidiManager midiManager;
    private NotificationManager notificationManager;
    private MediaControllerCompat mediaController;
    private MediaSessionCompat mediaSession;
    private PlaybackStateCompat.Builder playbackStateBuilder;
    private NotificationCompat.Builder notificationBuilder;
    private MidiLibrary midiLibrary;
    private MoppyManager moppyManager;
    private MidiPortInfoWrapper currentMidiInInfo;
    private MidiDeviceInfo requestedMidiInDeviceInfo;
    private MidiDevice midiInDevice;
    private MidiOutputPort midiInDevicePort;
    private MidiForwarder midiInForwarder;
    private MidiProcessor midiSplitter;
    private MidiPortInfoWrapper currentMidiOutInfo;
    private MidiDeviceInfo requestedMidiOutDeviceInfo;
    private MidiDevice midiOutDevice;
    private MidiInputPort midiOutDevicePort;
    private MidiReceiverAdapter midiOutDeviceAdapter;
    private MusicQueue musicQueue;

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
                MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS |
                MediaSessionCompat.FLAG_HANDLES_QUEUE_COMMANDS
        ); // End mediaSession flags
        playbackStateBuilder = new PlaybackStateCompat.Builder()
                .setActions(
                        PlaybackStateCompat.ACTION_PLAY_PAUSE |
                        PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                        PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
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

        musicQueue = new MusicQueue(mediaSession, this::onQueueActionsChanged);
        midiManager = (MidiManager) getSystemService(Context.MIDI_SERVICE);

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

        midiInForwarder = new MidiForwarder();
        midiSplitter = new MidiProcessor(moppyManager.getInputReceiver());
        midiInForwarder.setReceiver(moppyManager.getInputReceiver()); // splitingMidi false by default
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
     * Triggered when this service is bound to with {@link Context#bindService(Intent, ServiceConnection, int)}.
     *
     * @param intent the {@link Intent} send with the {@code bindService} call
     * @return a {@link Binder} if {@link #EXTRA_BIND_NORMAL} is specified and true, otherwise the result
     * of {@link MediaBrowserServiceCompat#onBind(Intent)}
     */
    @Override
    public IBinder onBind(Intent intent) {
        if (intent.getBooleanExtra(EXTRA_BIND_NORMAL, false)) { return new Binder(); }
        else { return super.onBind(intent); }
    } // End onBind method

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
    } // End onTaskRemoved method

    /**
     * Triggered when the service has completed and is being destroyed by the garbage collector.
     */
    @Override
    public void onDestroy() {
        // Disconnect from the current MIDI devices
        disconnectMidiIn();
        disconnectMidiOut();

        // Disconnect from all Moppy devices, stop running in the foreground, and shutdown the media session
        if (moppyManager != null) { moppyManager.close(); }
        if (midiSplitter != null) { midiSplitter.close(); }
        stopForeground(true);
        if (mediaSession != null) { mediaSession.release(); }

        super.onDestroy();
    } // End onDestroy method

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
                        // Send that an empty list if we don't have permission to access storage
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
     *     <li>{@link #ACTION_SET_MIDI_IN}</li>
     *     <li>{@link #ACTION_SET_MIDI_OUT}</li>
     *     <li>{@link #ACTION_SET_MIDI_SPLIT}</li>
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
            case ACTION_SET_MIDI_IN: {
                onSetMidiIn(extras, result);
                break;
            } // End ACTION_SET_MIDI_IN case
            case ACTION_SET_MIDI_OUT: {
                onSetMidiOut(extras, result);
                break;
            } // End ACTION_SET_MIDI_OUT case
            case ACTION_SET_MIDI_SPLIT: {
                onSetMidiSplit(extras, result);
                break;
            } // End ACTION_SET_MIDI_OUT case
            case ACTION_INIT_LIBRARY: {
                onInitLibrary(result);
                break;
            } // End ACTION_INIT_LIBRARY case
            case ACTION_REFRESH_DEVICES: {
                onRefreshDevices(result);
                break;
            } // End ACTION_REFRESH_DEVICES case
            case ACTION_GET_DEVICES: {
                onGetDevices(result);
                break;
            } // End ACTION_GET_DEVICES case
            case ACTION_LOAD_ITEM: {
                onLoadAction(extras, result);
                break;
            } // End ACTION_LOAD_ITEM case
            default: {
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "No matching action");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                result.sendError(errorBundle);
                break;
            } // End default case
        } // End switch(action)
        //super.onCustomAction(action, extras, result); // Just calls result.sendError(null);
    } // End onCustomAction method

    /**
     * Gets the {@link Receiver} that can be used to send MIDI messages to Moppy.
     *
     * @return the {@link Receiver} to send messages to
     */
    public Receiver getInputReceiver() { return midiInForwarder; }

    /**
     * Gets the {@link Receiver} that messages are forwarded to.
     *
     * @return the {@link Receiver} or null if not set
     * @see #addReceiver(Receiver)
     * @see #removeReceiver(Receiver)
     */
    public Receiver getReceiver() { return moppyManager.getReceiver(); }

    /**
     * Adds a {@link Receiver} to forward all MIDI messages to, regardless of if they originated on
     * the MIDI wire input or the MIDI file input. See {@link ReceiverDispatcher#add(Receiver)} for
     * more information.
     *
     * @param receiver the {@link Receiver} to add
     * @return {@code true} if {@code receiver} was added, {@code false} if {@code receiver} was null or duplicate
     * @see #removeReceiver(Receiver)
     */
    public boolean addReceiver(Receiver receiver) { return moppyManager.addReceiver(receiver); }

    /**
     * Removes a {@link Receiver} from the list to forward messages to.
     *
     * @param receiver the {@link Receiver} to remove
     * @return {@code true} if {@code receiver} was removed, {@code false} if it was {@code null} or not added
     * @see #addReceiver(Receiver)
     */
    public boolean removeReceiver(Receiver receiver) { return moppyManager.removeReceiver(receiver); }

    /**
     * Loads a {@link com.moppyandroid.main.service.MidiLibrary.MidiFile} by its media ID into Moppy. If loading is unsuccessful {@code false} will be returned.
     * Under expected conditions the sequencer state will not be affected unless the load action is valid. The only event that may jeopardize the sequencer state
     * is if and {@link InvalidMidiDataException} is thrown when calliing {@link jp.kshoji.javax.sound.midi.Sequencer#setSequence(Sequence)} but is not thrown
     * when calling {@link jp.kshoji.javax.sound.midi.spi.MidiFileReader#getSequence(InputStream)}.
     *
     * @param mediaId      the media ID of the song to load
     * @param setToPlaying {@code true} if the song is to be immediately played, {@code false} if the sequencer is to load the song and pause
     * @return {@code false} if there was an error loading, otherwise {@code true} (unless mediaId is invalid, it's likely a dev issue - check Logcat for details)
     */
    public boolean load(String mediaId, boolean setToPlaying) {
        // Create the MIDI library if needed and retrieve the requested file
        if (midiLibrary == null) {
            Log.w(MoppyMediaService.class.getName() + "->load (public)", "midiLibrary uninitialized");
            // We don't want to hold up UI thread creating a MidiLibrary that may not be created successfully,
            // so instead just trigger creation asynchronously and send a failure
            MidiLibrary.getMidiLibraryAsync(MoppyMediaService.this, null);
            return false;
        } // End if(midiLibrary == null)

        // Verify valid media ID
        if (!(midiLibrary.get(mediaId) instanceof MidiLibrary.MidiFile)) {
            Log.w(MoppyMediaService.class.getName() + "->load (public)", "File '" + mediaId + "' doesn't exist");
            return false;
        }

        return load(mediaId, null, setToPlaying, true);
    } // End load(String, boolean) method

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

        try { moppyManager.getUsbManager().closeBridge(portName); }
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

    // Triggered by ACTION_SET_MIDI_IN
    private void onSetMidiIn(Bundle extras, Result<Bundle> result) {
        // Retrieve arguments and validate them
        if (extras == null) {
            Log.w(TAG + "->onSetMidiIn", "No extras supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No extras supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            // Can't put EXTRA_MIDI_IN_DEVICE
            result.sendError(errorBundle);
            return;
        } // End if(extras == null)

        MidiPortInfoWrapper portInfo = extras.getParcelable(EXTRA_MIDI_IN_DEVICE);

        // Handle setting MIDI in device to null
        if (portInfo == null) {
            disconnectMidiIn();
            requestedMidiInDeviceInfo = null;
            currentMidiInInfo = null;

            // Return the result
            Bundle resultBundle = new Bundle();
            resultBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, null);
            result.sendResult(resultBundle);
            return;
        } // End if(portInfo == null)

        // Resume checking validity of arguments
        if (portInfo.getType() != MidiDeviceInfo.PortInfo.TYPE_OUTPUT) {
            Log.w(TAG + "->onSetMidiIn", "Missing EXTRA_MIDI_DEVICE or isn't an output port");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "EXTRA_MIDI_DEVICE missing or isn't an output port");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            errorBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, portInfo);
            result.sendError(errorBundle);
            return;
        } // End if(deviceInfo == null || !deviceInfo.hasOutputPorts)
        if (portInfo.getParent() == null) {
            Log.w(TAG + "->onSetMidiIn", "Parent of provided MidiPortInfoWrapper is null");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "Parent of provided MidiPortInfoWrapper is null");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            errorBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
            result.sendError(errorBundle);
            return;
        } // End if(parent == null)

        disconnectMidiIn();

        // Update the current device info and (asynchronously) open the device
        requestedMidiInDeviceInfo = portInfo.getParent();
        result.detach();
        midiManager.openDevice(requestedMidiInDeviceInfo, (device) -> {
            if (device == null) {
                Log.e(TAG + "->onSetMidiIn->openDevice", "Unable to open the requested MidiDevice");
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "Unable to open the requested MidiDevice");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, portInfo);
                result.sendError(errorBundle);
                return;
            } // End if(device == null)

            // Check if this is still the requested device
            if (device.getInfo() != requestedMidiInDeviceInfo) {
                Log.i(TAG + "->onSetMidiIn->openDevice",
                        "midiInDeviceInfo not the same as opened device info, ACTION_SET_MIDI " +
                        "called again before connection completed"
                );
                try { device.close(); }
                catch (IOException e) {
                    Log.e(TAG + "->onSetMidiIn->openDevice", "Unable to close device", e);
                }
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "ACTION_SET_MIDI_IN called again before connection completed");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, portInfo);
                result.sendError(errorBundle);
                return;
            } // End if(device.info == requestedMidiInDeviceInfo)

            // Attempt to open the output port
            midiInDevice = device;
            midiInDevicePort = device.openOutputPort(portInfo.getPortNumber());
            if (midiInDevicePort == null) {
                Log.e(TAG + "->onSetMidiIn->openDevice",
                        "Unable to access the requested MIDI output port; device name: '" +
                        portInfo.getParentName() + "', port: " + portInfo.getPortNumber()
                );
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "Unable to open the requested MidiOutputPort");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, portInfo);
                result.sendError(errorBundle);
                return;
            } // End if(midiInDevicePort == null)

            // Construct the adapter and connect the port to the service
            MidiTransmitterAdapter adapter = new MidiTransmitterAdapter();
            midiInDevicePort.connect(adapter);
            adapter.setReceiver(midiInForwarder);
            currentMidiInInfo = portInfo;

            // Return the result
            Bundle resultBundle = new Bundle();
            resultBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, portInfo);
            result.sendResult(resultBundle);
        }, null); // End midiManager.openDevice call
    } // End onSetMidiIn method

    // Triggered by ACTION_SET_MIDI_OUT
    private void onSetMidiOut(Bundle extras, Result<Bundle> result) {
        // Retrieve arguments and validate them
        if (extras == null) {
            Log.w(TAG + "->onSetMidiOut", "No extras supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No extras supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            // Can't put EXTRA_MIDI_OUT_DEVICE
            result.sendError(errorBundle);
            return;
        } // End if(extras == null)

        MidiPortInfoWrapper portInfo = extras.getParcelable(EXTRA_MIDI_OUT_DEVICE);

        // Handle setting MIDI in device to null
        if (portInfo == null) {
            disconnectMidiOut();
            requestedMidiOutDeviceInfo = null;
            currentMidiOutInfo = null;

            // Return the result
            Bundle resultBundle = new Bundle();
            resultBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, null);
            result.sendResult(resultBundle);
            return;
        } // End if(portInfo == null)

        // Resume checking validity of arguments
        if (portInfo.getType() != MidiDeviceInfo.PortInfo.TYPE_INPUT) {
            Log.w(TAG + "->onSetMidiOut", "Missing EXTRA_MIDI_DEVICE or isn't an input port");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "EXTRA_MIDI_DEVICE missing or isn't an input port");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            errorBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
            result.sendError(errorBundle);
            return;
        } // End if(portInfo == null || portInfo != TYPE_INPUT)
        if (portInfo.getParent() == null) {
            Log.w(TAG + "->onSetMidiOut", "Parent of provided MidiPortInfoWrapper is null");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "Parent of provided MidiPortInfoWrapper is null");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            errorBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
            result.sendError(errorBundle);
            return;
        } // End if(parent == null)

        disconnectMidiOut();

        // Update the current requested device info and (asynchronously) open the device
        requestedMidiOutDeviceInfo = portInfo.getParent();
        result.detach();
        midiManager.openDevice(requestedMidiOutDeviceInfo, (device) -> {
            if (device == null) {
                Log.e(TAG + "->onSetMidiOut->openDevice", "Unable to open the requested MidiDevice");
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "Unable to open the requested MidiDevice");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
                result.sendError(errorBundle);
                return;
            } // End if(device == null)

            // Check if this is still the requested device
            if (device.getInfo() != requestedMidiOutDeviceInfo) {
                Log.i(TAG + "->onSetMidiOut->openDevice",
                        "midiInDeviceInfo not the same as opened device info, ACTION_SET_MIDI " +
                        "called again before connection completed"
                );
                try { device.close(); }
                catch (IOException e) {
                    Log.e(TAG + "->onSetMidiOut->openDevice", "Unable to close device", e);
                }
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "ACTION_SET_MIDI_OUT called again before connection completed");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
                result.sendError(errorBundle);
                return;
            } // End if(device.info != requestedMidiOutDeviceInfo)

            // Attempt to open the input port
            midiOutDevice = device;
            midiOutDevicePort = device.openInputPort(portInfo.getPortNumber());
            if (midiOutDevicePort == null) {
                Log.e(TAG + "->onSetMidiOut->openDevice",
                        "Unable to access the requested MIDI input port, another application" +
                        "probably has a lock on it; device name: '" +
                        portInfo.getParentName() + "', port: " + portInfo.getPortNumber()
                );
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "Unable to open the requested MidiInputPort");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
                result.sendError(errorBundle);
                return;
            } // End if(midiOutDevicePort == null)

            // Construct the adapter and connect the port to the service
            midiOutDeviceAdapter = new MidiReceiverAdapter(midiOutDevicePort);
            addReceiver(midiOutDeviceAdapter);
            currentMidiOutInfo = portInfo;

            // Return the result
            Bundle resultBundle = new Bundle();
            resultBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, portInfo);
            result.sendResult(resultBundle);
        }, null); // End midiManager.openDevice call
    } // End onSetMidiOut method

    // Triggered by ACTION_SET_MIDI_SPLIT
    private void onSetMidiSplit(Bundle extras, Result<Bundle> result) {
        // Retrieve arguments
        if (extras == null) {
            Log.w(TAG + "->onSetMidiSplit", "No extras supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No extras supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            // Can't put EXTRA_MIDI_OUT_DEVICE
            result.sendError(errorBundle);
            return;
        } // End if(extras == null)

        // Set the forwarder to the splitter or direct depending on EXTRA_MIDI_SPLIT_ENABLE
        splittingMidi = extras.getBoolean(EXTRA_MIDI_SPLIT_ENABLE, false);
        if (splittingMidi) {
            midiInForwarder.setReceiver(midiSplitter);
        }
        else { midiInForwarder.setReceiver(moppyManager.getInputReceiver()); }

        // Send the result
        Bundle resultBundle = new Bundle();
        resultBundle.putBoolean(EXTRA_MIDI_SPLIT_ENABLE, splittingMidi);
        result.sendResult(resultBundle);
    } // End onSetMidiSplit method

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
                else {
                    notifyChildrenChanged(MidiLibrary.ROOT_ID);
                    result.sendResult(resultBundle);
                }
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
    } // End onRefreshDevice method

    // Triggered by ACTION_GET_DEVICES
    private void onGetDevices(Result<Bundle> result) {
        Bundle resultBundle = new Bundle();

        // Get the device names from the device infos just in case a race de-syncs the results of
        // MoppyUsbManager.getDeviceInfoForAll() and MoppyUsbManager.getDevices()
        ArrayList<UsbDevice> deviceInfos = moppyManager.getUsbManager().getDeviceInfoForAll();
        ArrayList<String> deviceNames = new ArrayList<>();
        for (UsbDevice deviceInfo : deviceInfos) { deviceNames.add(deviceInfo.getDeviceName()); }

        resultBundle.putStringArrayList(EXTRA_DEVICE_NAMES, deviceNames);
        resultBundle.putParcelableArrayList(EXTRA_DEVICE_INFOS, deviceInfos);
        resultBundle.putInt(EXTRA_NUM_CONNECTED, moppyManager.getUsbManager().getNumberConnected());
        resultBundle.putStringArrayList(EXTRA_DEVICES_CONNECTED,
                new ArrayList<>(moppyManager.getUsbManager().getConnectedIdentifiers())
        );
        resultBundle.putParcelable(EXTRA_MIDI_IN_DEVICE, currentMidiInInfo);
        resultBundle.putParcelable(EXTRA_MIDI_OUT_DEVICE, currentMidiOutInfo);
        resultBundle.putBoolean(EXTRA_MIDI_SPLIT_ENABLE, splittingMidi);
        result.sendResult(resultBundle);
    } // End onGetDevices method

    // Triggered by ACTION_LOAD_ITEM. Essentially MediaCallback.onPlayFromMediaId with callbacks to result.
    private void onLoadAction(Bundle extras, Result<Bundle> result) {
        if (extras == null || extras.getString(EXTRA_MEDIA_ID) == null) {
            Log.w(MoppyMediaService.class.getName() + "->onLoadAction", "No media item supplied");
            Bundle errorBundle = new Bundle();
            errorBundle.putString(EXTRA_ERROR_REASON, "No EXTRA_MEDIA_ITEM supplied");
            errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
            result.sendError(errorBundle);
            return;
        } // End if(extras == null || EXTRA_MEDIA_ID == null)
        String mediaId = extras.getString(EXTRA_MEDIA_ID);
        boolean setToPlaying = extras.getBoolean(EXTRA_PLAY, false);

        // Attempt to load the file and call result.sendError/result.sendResult appropriately
        load(mediaId, result, setToPlaying, true);
    } // End onLoadAction method

    // Disconnects the current MIDI in device
    private void disconnectMidiIn() {
        // Disconnect from the current MIDI in device
        if (midiInDevicePort != null) {
            try { midiInDevicePort.close(); }
            catch (IOException e) { // Nothing we can do except log and move on
                Log.e(TAG + "->onSetMidiIn", "Unable to close midiInDevicePort", e);
            }
            midiInDevicePort = null;
        }
        if (midiInDevice != null) {
            try { midiInDevice.close(); }
            catch (IOException e) { // Nothing we can do except log and move on
                Log.e(TAG + "->onSetMidiIn", "Unable to close midiInDevice", e);
            }
            midiInDevice = null;
        }
    } // End disconnectMidiIn method

    // Disconnects the current MIDI out device
    private void disconnectMidiOut() {
        // Disconnect from the current MIDI out device
        if (midiOutDeviceAdapter != null) {
            removeReceiver(midiOutDeviceAdapter);
            midiOutDeviceAdapter = null;
        }
        if (midiOutDevicePort != null) {
            try { midiOutDevicePort.close(); }
            catch (IOException e) { // Nothing we can do except log and move on
                Log.e(TAG + "->onSetMidiOut", "Unable to close midiOutDevicePort", e);
            }
            midiOutDevicePort = null;
        }
        if (midiOutDevice != null) {
            try { midiOutDevice.close(); }
            catch (IOException e) { // Nothing we can do except log and move on
                Log.e(TAG + "->onSetMidiOut", "Unable to close midiOutDevice", e);
            }
            midiOutDevice = null;
        }
    } // End disconnectMidiOut method

    // Finds and loads a MidiLibrary.MidiFile from its media ID. sendError/sendResult called if result isn't null
    private boolean load(String mediaId, Result<Bundle> result, boolean setToPlaying, boolean addToQueue) {
        PlaybackStateCompat playbackState = mediaController.getPlaybackState();
        int previousState = playbackState.getState();
        long previousPosition = playbackState.getPosition();
        long previousActions = playbackState.getActions();

        // Put into STATE_BUFFERING if not in a skipping state and always disable actions
        if (
                playbackState.getState() != PlaybackStateCompat.STATE_SKIPPING_TO_NEXT &&
                playbackState.getState() != PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS &&
                playbackState.getState() != PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM
        ) {
            setPlaybackState(PlaybackStateCompat.STATE_BUFFERING, 0, false);
        } // End if(playbackState != SKIPPING_TO_NEXT && SKIPPING_TO_PREVIOUS && SKIPPING_TO_QUEUE_ITEM)
        setPlaybackActions(0, true);

        // Create the MIDI library if needed
        if (midiLibrary == null) {
            // We don't want to hold up UI thread creating a MidiLibrary that may not be created successfully,
            // so instead just trigger creation asynchronously and if that fails send an error
            if (result != null) { result.detach(); }
            MidiLibrary.getMidiLibraryAsync(MoppyMediaService.this, (midiLibraryResult) -> {
                if (midiLibraryResult == null) {
                    Log.e(MoppyMediaService.class.getName() + "->load", "Unable to create MidiLibrary");
                    if (result != null) {
                        Bundle errorBundle = new Bundle();
                        errorBundle.putString(EXTRA_ERROR_REASON, "Unable to create MIDI Library");
                        // How did the Browser get a media ID if no library exists and can't be created? Something shady going on here
                        errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                        errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
                        result.sendError(errorBundle);
                    }

                    // Reset the playback state to its state prior to loading
                    setPlaybackState(previousState,
                            ((moppyManager.getLoadedFile() != null) ?
                                    moppyManager.getMillisecondsPosition() :
                                    0
                            ),
                            false
                    );
                    setPlaybackActions(previousActions, true);
                    return; // Exit lambda
                } // End if(midiLibraryResult == null)
                MoppyMediaService.this.midiLibrary = midiLibraryResult;
                load(mediaId, result, setToPlaying, addToQueue); // Retry
            }); // End MidiLibrary.Callback lambda

            return false; // Playback state still in STATE_SKIPPING/BUFFERING until callback fires
        } // End if(midiLibrary == null)

        // Retrieve the file
        MidiLibrary.MapNode node = midiLibrary.get(mediaId);
        if (!(node instanceof MidiLibrary.MidiFile)) {
            Log.w(MoppyMediaService.class.getName() + "->load", "File '" + mediaId + "' doesn't exist");
            if (result != null) {
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "The media ID " + mediaId + " does not correspond to a MidiLibray.MidiFile");
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false); // Browser item list bad?
                errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
                result.sendError(errorBundle);
            }

            // Reset the playback state to its state prior to loading
            setPlaybackState(previousState,
                    ((moppyManager.getLoadedFile() != null) ?
                            moppyManager.getMillisecondsPosition() :
                            0
                    ),
                    false
            );
            setPlaybackActions(previousActions, true);
            return false;
        } // End if(node ∉ MidiFile)

        // Sequence loading done before media session loading in case the file is invalid or can't be read
        try {
            moppyManager.load((MidiLibrary.MidiFile) node, MoppyMediaService.this);
        } // End try {load(node)}
        catch (IOException e) {
            Log.e(MoppyMediaService.class.getName() + "->load", "Unable to read file " + mediaId, e);
            if (result != null) {
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "Loading failed for media ID " + mediaId);
                errorBundle.putSerializable(EXTRA_EXCEPTION, e);
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false); // File bad or no read access?
                errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
                result.sendError(errorBundle);
            }

            // Reset the playback state to its state prior to loading
            setPlaybackState(previousState,
                    ((moppyManager.getLoadedFile() != null) ?
                            moppyManager.getMillisecondsPosition() :
                            0
                    ),
                    false
            );
            setPlaybackActions(previousActions, true);
            return false;
        } // End try {moppyManager.load(node)} catch(IO Exception)
        catch (InvalidMidiDataException e) {
            Log.e(MoppyMediaService.class.getName() + "->load", "Invalid MIDI file " + mediaId, e);
            if (result != null) {
                Bundle errorBundle = new Bundle();
                errorBundle.putString(EXTRA_ERROR_REASON, "Invalid MIDI file with media ID " + mediaId);
                errorBundle.putSerializable(EXTRA_EXCEPTION, e);
                errorBundle.putBoolean(EXTRA_ERROR_INFORMATIONAL, false);
                errorBundle.putString(EXTRA_MEDIA_ID, mediaId);
                result.sendError(errorBundle);
            }

            // Sequencer state undefined, put into STATE_ERROR and limit available actions
            setPlaybackState(PlaybackStateCompat.STATE_ERROR, 0, false);
            setPlaybackActions(
                    (
                            PlaybackStateCompat.ACTION_STOP |
                            PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                            PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                            musicQueue.getActions()
                    ),
                    true
            );
            return false;
        } // End try {moppyManager.load(node)} catch(IOException) {} catch(InvalidMidiDataException)

        if (addToQueue) { musicQueue.addToQueue(node.getMetadata().getDescription()); }

        // Load in the file's metadata, and update the notification and playback state
        mediaSession.setMetadata(node.getMetadata());
        setPlaybackActions(playbackState.getActions() | PlaybackStateCompat.ACTION_SEEK_TO, false);
        if (setToPlaying) {
            moppyManager.play();
            setPlaybackState(PlaybackStateCompat.STATE_PLAYING, moppyManager.getMillisecondsPosition(), true);
            togglePlayPauseMediaButton(true, node.getMetadata());
        } // End if(setToPlaying)
        else {
            moppyManager.pause();
            setPlaybackState(PlaybackStateCompat.STATE_PAUSED, moppyManager.getMillisecondsPosition(), true);
            togglePlayPauseMediaButton(true, null);
        } // End if(setToPlaying) {} else

        if (result != null) {
            Bundle resultBundle = new Bundle();
            resultBundle.putString(EXTRA_MEDIA_ID, mediaId);
            resultBundle.putParcelable(EXTRA_MEDIA_MIDI_FILE, (MidiLibrary.MidiFile) node);
            result.sendResult(resultBundle);
        }
        return true; // Successful playback state set with togglePlayPauseMediaButton call
    } // End load(String, Result<Bundle>, boolean, boolean) method

    // Attempts to load the first item in the queue without popping it, returning false if queue empty
    private boolean loadQueueItem(boolean setToPlaying) {
        setPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, false);
        setPlaybackActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | // Set limited action package to only loading songs
                           PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                           PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                           musicQueue.getActions(),
                true
        );

        // Load the next item if it exists
        QueueItem item = musicQueue.getCurrentSong();
        if (item != null) {
            load(item.getDescription().getMediaId(), null, setToPlaying, false);
            return true;
        }
        else { // Queue exhausted
            setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0, false);
            setPlaybackActions(calculatePlaybackActions(PlaybackStateCompat.STATE_STOPPED), true);
            return false;
        }
    } // End loadQueueItem method

    // Attempts to load the next song in the queue
    private void loadNext(boolean songEnded) {
        int previousState = mediaController.getPlaybackState().getState();
        long previousPosition = mediaController.getPlaybackState().getPosition();
        long previousActions = mediaController.getPlaybackState().getActions();
        setPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_NEXT, 0, false);
        setPlaybackActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | // Set limited action package to only loading songs
                           PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                           PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                           musicQueue.getActions(),
                true
        );

        // Load next if queue not exhausted
        if (musicQueue.checkNextSongAvailable()) {
            boolean setToPlaying = songEnded || previousState == PlaybackStateCompat.STATE_PLAYING;
            load(musicQueue.skipToNext().getDescription().getMediaId(), null, setToPlaying, false);
        }
        else {
            // If the current song just ended stop playback, otherwise continue with previous state
            if (songEnded) {
                setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0, false);
                setPlaybackActions(calculatePlaybackActions(PlaybackStateCompat.STATE_STOPPED), true);
            }
            else {
                setPlaybackState(previousState, previousPosition, false);
                setPlaybackActions(previousActions, true);
            }
        } // End if(musicQueue.checkNextSongAvailable()) {} else
    } // End loadNext method

    // Calculates which playback actions are available from on the current state and file status
    private long calculatePlaybackActions(int state) {
        // Set base actions package
        long actions = PlaybackStateCompat.ACTION_STOP |
                       PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                       PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                       ((musicQueue != null) ?
                               musicQueue.getActions() :
                               0
                       );
        if (moppyManager != null && moppyManager.getLoadedFile() != null) {
            actions |= PlaybackStateCompat.ACTION_SEEK_TO | PlaybackStateCompat.ACTION_PLAY_PAUSE;
            // Check if the playback state is applicable to a play or pause button
            if (
                    state != PlaybackStateCompat.STATE_NONE &&
                    state != PlaybackStateCompat.STATE_ERROR &&
                    state != PlaybackStateCompat.STATE_SKIPPING_TO_NEXT &&
                    state != PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS &&
                    state != PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM &&
                    state != PlaybackStateCompat.STATE_BUFFERING
            ) {
                // Enable the pause button if playing otherwise enable the play button
                actions |= ((state == PlaybackStateCompat.STATE_PLAYING) ?
                        PlaybackStateCompat.ACTION_PAUSE :
                        PlaybackStateCompat.ACTION_PLAY
                );
            }
        }
        return actions;
    } // End calculatePlaybackActions method

    // Consolidates all playback state changes to run through this method
    private int setPlaybackState(int state, long position, boolean postState) {
        int previousState = mediaController.getPlaybackState().getState();
        playbackStateBuilder.setState(state, position, 1);
        if (postState) { mediaSession.setPlaybackState(playbackStateBuilder.build()); }
        return previousState;
    } // End setPlaybackState method

    // Consolidates all playback action changes to run through this method
    private long setPlaybackActions(long actions, boolean postState) {
        long previousActions = mediaController.getPlaybackState().getActions();
        playbackStateBuilder.setActions(actions);
        if (postState) { mediaSession.setPlaybackState(playbackStateBuilder.build()); }
        return previousActions;
    } // End setPlaybackAction method

    // Method to toggle the notification between playing (pause icon) and not-playing (play icon) modes. The playback
    // state should be posted prior to calling this method. Set the playback state STATE_PAUSED or STATE_STOPPED to get a
    // play button, otherwise a pause button will appear
    //
    // Set changeText to true to update the notification text to "No song loaded" or the song name
    @SuppressLint("RestrictedApi") // IDE doesn't want us to access notificationBuilder.mActions
    private void togglePlayPauseMediaButton(boolean changeText, MediaMetadataCompat metadata) {
        //
        int state = (mediaController != null) ? mediaController.getPlaybackState().getState() : PlaybackStateCompat.STATE_NONE;
        setPlaybackActions(calculatePlaybackActions(state), true);

        // Replace button icon
        notificationBuilder.mActions.remove(NOTIFICATION_PLAY_PAUSE_INDEX);
        if (state == PlaybackStateCompat.STATE_PAUSED || state == PlaybackStateCompat.STATE_STOPPED) { // Not-playing mode
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
        } // End if(metadata == null) {} else
        if (changeText) { updateNotificationText(metadata, false); }

        notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
    } // End togglePlayPauseMediaButton method

    // Updates the text of the playing notification to "No file loaded" or the provided metadata's title.
    // If postNotification is true, the updated notification is also pushed
    private void updateNotificationText(MediaMetadataCompat metadata, boolean postNotification) {
        if (metadata == null) { notificationBuilder.setContentTitle("No file loaded"); }
        else {
            notificationBuilder.setContentTitle(metadata.getString(MediaMetadataCompat.METADATA_KEY_TITLE));
        }

        if (postNotification) {
            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build());
        }
    } // End updateNotificationText method

    private void onQueueActionsChanged(boolean skipToItem, boolean skipToNext, boolean skipToPrevious) {
        if (mediaController != null) {
            long actions = mediaController.getPlaybackState().getActions();
            setPlaybackActions(
                    calculatePlaybackActions(mediaController.getPlaybackState().getState()),
                    true
            );
        } // End if(mediaController != null)
    } // End onQueueActionsChanged method

    /**
     * Used to allow binding to this {@link MoppyMediaService}.
     */
    public class Binder extends android.os.Binder {
        public MoppyMediaService getService() { return MoppyMediaService.this; }
    } // End MoppyMediaService.Binder class

    // Callbacks for media button events. All callbacks are disabled if their associated action is not supported
    private class MediaCallback extends MediaSessionCompat.Callback {
        @Override
        public void onPlay() {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY) == 0) { return; }
            if (mediaController.getMetadata() == null) { return; } // Do nothing unless file loaded

            // Update notification
            setPlaybackState(PlaybackStateCompat.STATE_PLAYING, playbackState.getPosition(), true);
            togglePlayPauseMediaButton(true, mediaController.getMetadata());

            moppyManager.play();
            super.onPlay();
        } // End onPlay method

        @Override
        public void onPlayFromMediaId(String mediaId, Bundle extras) {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID) == 0) {
                return;
            }

            // Load in the file
            load(mediaId, null, true, true);

            super.onPlayFromMediaId(mediaId, extras);
        } // End onPlayFromMediaId method

        @Override
        public void onPlayFromSearch(String query, Bundle extras) {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH) == 0) {
                return;
            }

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
                if (musicQueue != null && musicQueue.checkPreviousSongAvailable()) {
                    musicQueue.skipToPrevious();
                    loadQueueItem(true);
                }
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
            setPlaybackState(PlaybackStateCompat.STATE_PAUSED, playbackState.getPosition(), true);
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
                setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0, true);
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
            // the range { 0 ≤ position ≤ duration }
            long duration = mediaController.getMetadata().getLong(MediaMetadataCompat.METADATA_KEY_DURATION);
            setPlaybackState(playbackState.getState(), Math.max(0, Math.min(position, duration)), true);

            // Seek the sequencer to position if it is lower than the length, otherwise seek to the end
            moppyManager.seekTo(position, playbackState.getState() == PlaybackStateCompat.STATE_PLAYING);

            super.onSeekTo(position);
        } // End onSeekTo method

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description) {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if (description == null) { return; }

            if (musicQueue != null) { musicQueue.addToQueue(description); }
            super.onAddQueueItem(description);
        } // End onAddQueueItem(MediaDescriptionCompat) method

        @Override
        public void onAddQueueItem(MediaDescriptionCompat description, int index) {
            if (description == null || index < 0) { return; }
            boolean play = mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING;

            if (musicQueue != null) {
                if (musicQueue.addToQueue(description, index)) { loadQueueItem(play); }
            }
            super.onAddQueueItem(description, index);
        } // End onAddQueueItem(MediaDescriptionCompat, int) method

        @Override
        public void onRemoveQueueItem(MediaDescriptionCompat description) {
            if (description == null) { return; }
            boolean play = mediaController.getPlaybackState().getState() == PlaybackStateCompat.STATE_PLAYING;

            if (musicQueue != null) {
                if (musicQueue.removeFromQueue(description)) { loadQueueItem(play); }
            }
            super.onRemoveQueueItem(description);
        } // End onRemoveQueueItem method

        @Override
        public void onSkipToQueueItem(long id) {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM) == 0) {
                return;
            }
            setPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_QUEUE_ITEM, 0, false);
            setPlaybackActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | // Set limited action package to only loading songs
                               PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                               PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                               musicQueue.getActions(),
                    true
            );

            if (musicQueue != null) {
                musicQueue.skipToSong(id);
                loadQueueItem(playbackState.getState() == PlaybackStateCompat.STATE_PLAYING);
            }
            super.onSkipToQueueItem(id);
        }

        @Override
        public void onSkipToNext() {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) == 0) {
                return;
            }

            if (musicQueue != null) {
                loadNext(false);
            }
            super.onSkipToNext();
        }

        @Override
        public void onSkipToPrevious() {
            PlaybackStateCompat playbackState = mediaController.getPlaybackState();
            if ((playbackState.getActions() & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) == 0) {
                return;
            }

            if (musicQueue != null) {
                setPlaybackState(PlaybackStateCompat.STATE_SKIPPING_TO_PREVIOUS, 0, false);
                setPlaybackActions(PlaybackStateCompat.ACTION_PLAY_PAUSE | // Set limited action package to only loading songs
                                   PlaybackStateCompat.ACTION_PLAY_FROM_MEDIA_ID |
                                   PlaybackStateCompat.ACTION_PLAY_FROM_SEARCH |
                                   musicQueue.getActions(),
                        true
                );
                musicQueue.skipToPrevious();
                // Reloads current song if no previous songs available as musicQueue.getCurrentSong is guaranteed to be non-null
                //if at least one song is loaded
                loadQueueItem(playbackState.getState() == PlaybackStateCompat.STATE_PLAYING);
            }
            super.onSkipToPrevious();
        }

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
        void onStop() {
            PlaybackStateCompat currentState = mediaController.getPlaybackState();
            if (
                    mediaController.getMetadata() != null &&
                    (currentState.getState() == PlaybackStateCompat.STATE_PLAYING ||
                     currentState.getState() == PlaybackStateCompat.STATE_PAUSED)
            ) {
                setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0, true);
                togglePlayPauseMediaButton(false, null);
            } // End if(fileLoaded && currentState == PLAYING | PAUSED)
            super.onStop();
        } // End onStop method

        @Override
        void onSongEnd(boolean reset) {
            PlaybackStateCompat currentState = mediaController.getPlaybackState();
            if (
                    mediaController.getMetadata() != null &&
                    (currentState.getState() == PlaybackStateCompat.STATE_PLAYING ||
                     currentState.getState() == PlaybackStateCompat.STATE_PAUSED)
            ) {
                // If auto-reset is enabled (not possible at programming), don't skip to the next song
                if (reset) {
                    setPlaybackState(PlaybackStateCompat.STATE_STOPPED, 0, true);
                    togglePlayPauseMediaButton(false, null);
                }
                else {
                    loadNext(true);
                }
            } // End if(fileLoaded && currentState == PLAYING | PAUSED)
            super.onSongEnd(reset);
        } // End onSongEnd method
    } // End MoppyCallback class
} // End MoppyMediaService class

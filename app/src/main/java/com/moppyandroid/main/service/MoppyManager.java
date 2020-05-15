package com.moppyandroid.main.service;

import android.content.Context;
import android.util.Log;

import com.moppy.core.comms.bridge.BridgeSerial;
import com.moppy.core.midi.MoppyMIDISequencer;
import com.moppy.core.events.mapper.MIDIEventMapper;
import com.moppy.core.events.mapper.MapperCollection;
import com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppy.core.status.StatusBus;
import com.moppy.core.status.StatusType;
import com.moppy.core.status.StatusUpdate;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;
import jp.kshoji.javax.sound.midi.Sequencer;
import jp.kshoji.javax.sound.midi.io.StandardMidiFileReader;
import jp.kshoji.javax.sound.midi.spi.MidiFileReader;

public class MoppyManager implements com.moppy.core.status.StatusConsumer {
    private static final String TAG = MoppyManager.class.getName();

    private boolean paused;
    private long currentSequenceLength;
    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private MoppyUsbManager netManager;
    private List<Callback> callbackList;
    private MidiLibrary.MidiFile loadedFile;

    public MoppyManager(Context context) throws MidiUnavailableException {
        paused = false;
        callbackList = new ArrayList<>();
        loadedFile = null;

        BridgeSerial.init(context);
        StatusBus statusBus = new StatusBus();
        statusBus.registerConsumer(this);
        MapperCollection<MidiMessage> mappers = new MapperCollection<>();
        mappers.addMapper(MIDIEventMapper.defaultMapper((byte) 0x01)); // Map to first device
        netManager = new MoppyUsbManager(statusBus, context);

        try {
            receiverSender = new MoppyMIDIReceiverSender(mappers, MessagePostProcessor.PASS_THROUGH, netManager.getPrimaryBridge());
        } // End try {new MoppyMIDIReceiverSender}
        catch (IOException ignored) {} // Not actually generated, method signature outdated
        try { seq = new MoppyMIDISequencer(statusBus, receiverSender); }
        catch (MidiUnavailableException e) { // Unrecoverable error: Unable to get MIDI resources for Moppy initialization
            // Log the unrecoverable error and forward the exception
            Log.wtf(TAG, "Unable to construct MoppyMIDISequencer", e);
            throw e;
        } // End try {new MoppyMIDISequencer} catch(MidiUnavailableException)
    } // End MoppyManager(Context) constructor

    /**
     * Triggered when the sequencer posts a {@link StatusUpdate}.
     *
     * @param update the posted {@code StatusUpdate}
     */
    @Override
    public void receiveUpdate(StatusUpdate update) {
        if (update.getType() == StatusType.SEQUENCE_END) {
            callbackList.forEach((callback) ->
                    callback.onSongEnd((boolean) update.getData().orElse(false))
            ); // End callbackList.forEach lambda
        } // End if(update == SEQUENCE_END)
    } // End receiveUpdate method

    /**
     * Gets the {@link MoppyUsbManager} managed by this {@code MoppyManager}.
     *
     * @return the associated {@code MoppyUsbManager}
     */
    MoppyUsbManager getUsbManager() { return netManager; }

    /**
     * Starts playback if not already playing.
     */
    public void play() {
        if (!seq.isPlaying()) {
            seq.play();
            paused = false;
            callbackList.forEach(Callback::onPlay);
        } // End if(!playing)
    } // End play method

    /**
     * Pauses playback if not already paused.
     */
    public void pause() {
        if (!paused) {
            seq.pause();
            paused = true;
            callbackList.forEach(Callback::onPause);
        } // End if(!paused)
    } // End pause method

    /**
     * Stops and resets playback. A {@link com.moppy.core.status.StatusType#SEQUENCE_END}
     * message is broadcasted to all registered {@link com.moppy.core.status.StatusConsumer}s,
     * and connected Moppy devices are sent a {@link com.moppy.core.comms.MoppyMessage#SYS_RESET}
     * message.
     */
    public void stop() {
        // Stop is always available because it also functions as reset
        seq.stop();
        paused = false;
        callbackList.forEach(Callback::onStop);
    } // End stop method

    /**
     * Loads a {@link MidiLibrary.MidiFile} into the sequencer.
     *
     * @param file    the file to load
     * @param context the {@link Context} to use to open the file
     * @throws IOException              if the file couldn't be opened
     * @throws InvalidMidiDataException if the file wasn't a valid MIDI file
     */
    public void load(MidiLibrary.MidiFile file, Context context) throws IOException, InvalidMidiDataException {
        if (file == null || file.getUri() == null || file.getName() == null) {
            Log.e(TAG + "->load:", "Provided MidiFile was null or malformed");
        }

        // Get an input stream for the file and read it, raising an exception if the stream is invalid
        // Note: Two try blocks are needed because FileNotFoundException is a subclass of IOException,
        //      but we need to log them differently
        try (InputStream stream = context.getContentResolver().openInputStream(file.getUri())) {
            try {
                MidiFileReader reader = new StandardMidiFileReader();
                if (stream == null) { throw new IOException("Unable to open file"); }
                seq.loadSequence(reader.getSequence(stream));
            } // End try {loadSequence}
            catch (IOException e) {
                // Show a message box and exit method
                Log.e(TAG + "->load:", "Unable to load file '" + file.getName() + "'", e);
                throw e;
            } // End try {loadSequence} catch(IOException)
            catch (InvalidMidiDataException e) {
                Log.e(TAG + "->load:", "File '" + file.getName() + "' is not a valid MIDI file", e);
                throw e;
            } // End try {loadSequence} catch(IOException)
        } // End try(stream = open(uri))
        catch (FileNotFoundException e) {
            // Show a message box and exit method
            Log.e(TAG + "->load:", "File to load '" + file.getName() + "' not found", e);
            throw e; // Implicitly upcasted to IOException
        } // End try(stream = open(uri)) {} catch(FileNotFoundException)
        loadedFile = file;
        currentSequenceLength = seq.getMillisecondsLength();
        callbackList.forEach((callback) -> callback.onLoad(file));
    } // End load method

    /**
     * Uses the calculations of the loaded {@link jp.kshoji.javax.sound.midi.Sequence} to get the song length.
     *
     * @return the length in milliseconds
     */
    public long getMillisecondsLength() { return seq.getMillisecondsLength(); }

    /**
     * Gets the current position within the loaded song.
     *
     * @return the position in milliseconds
     */
    public long getMillisecondsPosition() { return seq.getMillisecondsPosition(); }

    /**
     * Seeks to the provided millisecond position within the loaded song. If {@code millis} is greater
     * than the length of the song, the {@link Sequencer} is advanced to the end of the song. Since
     * reaching the end of the song automatically pauses the {@code Sequencer}, the parameter
     * {@code callPlay} is provided to allow the caller to automatically restart playback after the seek.
     *
     * @param millis   the position to seek to
     * @param callPlay {@code true} to ensure the {@code Sequencer} is playing, {@code false} to not alter the playback state
     */
    public void seekTo(long millis, boolean callPlay) {
        seq.setMillisecondsPosition(Math.min(millis, currentSequenceLength));
        if (callPlay) { play(); }
    }

    /**
     * Checks if the {@link Sequencer} is playing.
     *
     * @return {@code true} if playing, otherwise {@code false}
     */
    public boolean isPlaying() { return seq.isPlaying(); }

    /**
     * Gets the currently loaded {@link MidiLibrary.MidiFile}.
     *
     * @return the loaded {@code MidiFile}
     */
    public MidiLibrary.MidiFile getLoadedFile() { return loadedFile; }

    /**
     * Registers a {@link Callback} with this {@code MoppyManager} to receive calls upon method completion.
     *
     * @param callback the {@code Callback} instance to register
     */
    public void registerCallback(Callback callback) { callbackList.add(callback); }

    /**
     * Removes a {@link Callback} registered with this {@code MoppManager}.
     *
     * @param callback the {@code Callback} instance to unregister
     * @return {@code true} if {@code callback} was formerly registered, otherwise {@code false}
     * @see #registerCallback(Callback)
     */
    public boolean unregisterCallback(Callback callback) { return callbackList.remove(callback); }

    /**
     * Callbacks fired when each method has completed successfully.
     */
    public static abstract class Callback {
        /**
         * Triggered after a successful {@link #play()} call.
         */
        void onPlay() {}

        /**
         * Triggered after a successful {@link #pause()} call.
         */
        void onPause() {}

        /**
         * Triggered after a successful {@link #stop()} call. Also triggers {@link #onSongEnd(boolean)},
         * see the linked javadoc for an explanation. Not guaranteed to be called before nor after
         * {@code onSongEnd(boolean)}.
         */
        void onStop() {}

        /**
         * Triggered after a successful {@link #load(MidiLibrary.MidiFile, Context)} call.
         *
         * @param file the file that was loaded
         */
        void onLoad(MidiLibrary.MidiFile file) {}

        /**
         * <p>
         * Triggered when a song ends or {@link #stop()} is called. This is due to {@code stop()} internally
         * being handled an early end to the song.
         * </p>
         * <p>
         * The parameter {@code reset} can sometimes be used to tell the difference between a natural
         * song ending and a {@code stop()} call. If the sequencer is not configured to automatically
         * reset with {@link MoppyMIDISequencer#setAutoReset(boolean)}, then if {@code reset} is
         * {@code true} {@code stop()} was called, otherwise the ending is natural. If the sequencer
         * has been set to automatically reset, then detecting if {@code stop()} triggered the sequence
         * end is non-trivial and out-of-scope for a {@link MoppyManager}. Neither {@code onStop}
         * nor this method are guaranteed to be called in a predictable order.
         * </p>
         *
         * @param reset {@code true} if the song progress is to be reset, otherwise {@code false}
         */
        void onSongEnd(boolean reset) {}
    }
}

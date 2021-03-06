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

import android.content.Context;
import android.util.Log;

import com.moppy.core.comms.bridge.BridgeSerial;
import com.moppy.core.events.mapper.MIDIEventMapper;
import com.moppy.core.events.mapper.MapperCollection;
import com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppy.core.midi.MoppyMIDISequencer;
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
import jp.kshoji.javax.sound.midi.Receiver;
import jp.kshoji.javax.sound.midi.Sequencer;
import jp.kshoji.javax.sound.midi.io.StandardMidiFileReader;
import jp.kshoji.javax.sound.midi.spi.MidiFileReader;

/**
 * Manages all objects necessary to operate Moppy and provides methods to control them.
 */
public class MoppyManager implements com.moppy.core.status.StatusConsumer, AutoCloseable {
    private static final String TAG = MoppyManager.class.getName();

    private boolean paused;
    private long currentSequenceLength;
    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private ReceiverDispatcher outputReceiverDispatcher;
    private MoppyUsbManager netManager;
    private List<Callback> callbackList;
    private MidiLibrary.MidiFile loadedFile;

    /**
     * Constructs a new {@code MoppyManager}.
     *
     * @param context the context to create the {@code MoppyManager} in
     * @throws MidiUnavailableException if a {@link MoppyMIDISequencer} cannot be created
     */
    public MoppyManager(Context context) throws MidiUnavailableException {
        paused = false;
        callbackList = new ArrayList<>();
        outputReceiverDispatcher = new ReceiverDispatcher();
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

        receiverSender.setMidiThru(outputReceiverDispatcher);
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
        else if (update.getType() == StatusType.SEQUENCE_STOPPED) {
            callbackList.forEach(Callback::onStop);
        }
    } // End receiveUpdate method

    /**
     * Releases held resources. <b>MUST</b> be called before this {@code MoppyManager}'s destruction.
     */
    @Override
    public void close() {
        netManager.closeAllBridges();
        try { seq.close(); } catch (IOException ignored) { } // Outdated method signature
        receiverSender.close();
    } // End close method

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
     * Stops and resets playback. A {@link com.moppy.core.status.StatusType#SEQUENCE_STOPPED}
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
            return;
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
     * Gets the {@link Receiver} that can be used to send MIDI messages to Moppy.
     *
     * @return the {@link Receiver} to send messages to
     */
    public Receiver getInputReceiver() { return receiverSender; }

    /**
     * Gets the {@link ReceiverDispatcher} that messages are forwarded to.
     *
     * @return the {@link ReceiverDispatcher} as a {@link Receiver}
     * @see #addReceiver(Receiver)
     * @see #removeReceiver(Receiver)
     */
    public Receiver getReceiver() { return outputReceiverDispatcher; }

    /**
     * Adds a {@link Receiver} to forward all MIDI messages to, regardless of if they originated on
     * the MIDI wire input or the MIDI file input. See {@link ReceiverDispatcher#add(Receiver)} for
     * more information.
     *
     * @param receiver the {@link Receiver} to add
     * @return {@code true} if {@code receiver} was added, {@code false} if {@code receiver} was null or duplicate
     * @see #removeReceiver(Receiver)
     */
    public boolean addReceiver(Receiver receiver) { return outputReceiverDispatcher.add(receiver); }

    /**
     * Removes a {@link Receiver} from the list to forward messages to.
     *
     * @param receiver the {@link Receiver} to remove
     * @return {@code true} if {@code receiver} was removed, {@code false} if it was {@code null} or not added
     * @see #addReceiver(Receiver)
     */
    public boolean removeReceiver(Receiver receiver) { return outputReceiverDispatcher.remove(receiver); }

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
         * Triggered after a successful {@link #stop()} call.
         */
        void onStop() {}

        /**
         * Triggered after a successful {@link #load(MidiLibrary.MidiFile, Context)} call.
         *
         * @param file the file that was loaded
         */
        void onLoad(MidiLibrary.MidiFile file) {}

        /**
         * Triggered when a song ends.
         *
         * @param reset {@code true} if the song progress is to be reset, otherwise {@code false}
         */
        void onSongEnd(boolean reset) {}
    } // End MidiManager.Callback class
} // End MidiManager class

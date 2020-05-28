// Forked from https://github.com/NoahR-ATC/MidiSplitterJava version 1.1.0 on 2020-05-27

package com.github.noahr_atc.midisplitter;

/*
Copyright 2020 Noah Reeder

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
 */

import jp.kshoji.javax.sound.midi.*;
import java.io.Serializable;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.google.common.collect.ArrayListMultimap;

/**
 * A {@link Receiver} that splits MIDI chords into notes distributed across all MIDI channels before
 * forwarding the new {@link MidiMessage}s to another Receiver.
 *
 * @author Noah Reeder
 * @version 1.0
 * @since 2020-03-08
 */
public class MidiProcessor implements Receiver {
    private MidiDevice midiReceiver;         // The MIDI device that owns the receiver represented by midiOut
    private Receiver midiOut;                // The MIDI receiver to forward processed messages to
    private ChannelStatus[] channelStatuses; // The array containing the status of all MIDI channels
    private ArrayListMultimap<NoteMapping, NoteMapping> noteTranslations;
    //                                       // ^ The duplicate-value-supporting map containing the active message translations
    private boolean isOpen;                  // Boolean for whether or not the MidiProcessor has been closed
    private boolean debugMode;

    /**
     * Constructs a {@code MidiProcessor} using a {@link MidiDevice} with the option to run in debugging mode. All MIDI
     * channels are set to available.
     *
     * @param midiReceiver the MIDI device to send processed messages to
     * @param debugMode    specifies whether to enable debugging messages
     * @throws MidiUnavailableException if the provided {@link MidiDevice} won't supply a receiver
     */
    public MidiProcessor(MidiDevice midiReceiver, boolean debugMode) throws MidiUnavailableException {
        channelStatuses = new ChannelStatus[]{
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus()
        }; // End ChannelStatus[] initialization
        noteTranslations = ArrayListMultimap.create();
        this.midiReceiver = midiReceiver;
        try {
            this.midiReceiver.open();
            midiOut = this.midiReceiver.getReceiver();
        } catch (MidiUnavailableException e) {
            // Ensure that we close the device if we have opened it, then abort construction and forward the exception
            if (this.midiReceiver.isOpen()) { this.midiReceiver.close(); }
            throw e;
        }
        isOpen = true;
        this.debugMode = debugMode;

        if (debugMode) {
            Logger.getLogger("MidiProcessor").log(Level.INFO, "Sending to: " + midiReceiver.getDeviceInfo().getName());
        }
    } // End MidiProcessor(MidiDevice, boolean) constructor

    /**
     * Constructs a {@code MidiProcessor} using a {@link MidiDevice} without debugging output. All MIDI channels are set to available.
     *
     * @param midiReceiver the MIDI device to send processed messages to
     * @throws MidiUnavailableException if the provided {@link MidiDevice} won't supply a receiver
     */
    public MidiProcessor(MidiDevice midiReceiver) throws MidiUnavailableException { this(midiReceiver, false); }

    /**
     * Constructs a {@code MidiProcessor} using a {@link Receiver} with the option to run in debugging mode. All MIDI
     * channels are set to available.
     *
     * @param receiver  the MIDI receiver to send processed messages to
     * @param debugMode specifies whether to enable debugging messages
     */
    public MidiProcessor(Receiver receiver, boolean debugMode) {
        channelStatuses = new ChannelStatus[]{
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus()
        }; // End ChannelStatus[] initialization
        noteTranslations = ArrayListMultimap.create();
        this.midiOut = receiver;
        this.debugMode = debugMode;
        isOpen = true;
        if (debugMode) {
            Logger.getLogger("MidiProcessor").log(Level.INFO, "Sending to Receiver: " + midiOut.toString());
        }
    } // End MidiProcessor(Receiver, boolean) constructor

    /**
     * Constructs a {@code MidiProcessor} using a {@link Receiver} without debugging output. All MIDI channels are set to available.
     *
     * @param receiver the MIDI receiver to send processed messages to
     */
    public MidiProcessor(Receiver receiver) { this(receiver, false); }

    /**
     * Sends a MIDI message to this receiver, along with an optional timestamp. Set timestamp to -1 if not used.
     *
     * @param message   the message to be received and processed by this {@code MidiProcessor}
     * @param timeStamp the timestamp (in microseconds) of the message
     */
    @Override
    public void send(MidiMessage message, long timeStamp) {
        // Drop the message if closing
        if (!isOpen) { return; }

        // Ensure that the midi receiver objects are valid, aborting the send operation if unavailable since we can't throw
        // an exception in the overridden method
        if (midiOut == null) { return; }

        // If the message is a ShortMessage send it to the translator for processing, regardless forwarding the message to the receiver
        // If the receiver is closed, log it and continue
        if (message instanceof ShortMessage) { message = translateMessage((ShortMessage) message); }
        try { midiOut.send(message, timeStamp); } catch (IllegalStateException e) {
            Logger.getLogger("MidiProcessor").log(
                    Level.SEVERE,
                    "Receiver " + midiOut.toString() + "closed",
                    e
            ); // End Logger.log call
        } // End try {} catch(IllegalStateException)
    } // End send method

    /**
     * Closes this {@code MidiProcessor} and releases its resources, specifically the MIDI device assigned with setReceiver or the constructor.
     */
    @Override
    public void close() {
        isOpen = false; // Stop advertising as available to process messages
        if (midiReceiver != null && midiReceiver.isOpen()) { midiReceiver.close(); }
        midiReceiver = null;
        midiOut = null;
    } // End close method

    /**
     * Checks whether or not {@code close} has been called on this {@code MidiProcessor}.
     *
     * @return {@code false} if {@code close} has been called; {@code true} otherwise
     */
    public boolean isRunning() { return isOpen; }

    /**
     * Opens a MIDI device and sets it as the {@link Receiver} that processed messages are sent to.
     *
     * @param midiReceiver the desired MIDI device to receive messages
     * @throws MidiUnavailableException if the provided {@link MidiDevice} won't supply a receiver
     * @throws NullPointerException     if the provided {@link MidiDevice} is null
     * @see #setReceiver(Receiver)
     */
    public void setReceiver(MidiDevice midiReceiver) throws MidiUnavailableException, NullPointerException {
        if (midiReceiver == null) { throw new IllegalArgumentException(); }

        Receiver receiver; // The new MIDI receiver to use

        // Ensure that if MidiUnavailableException is raised that it happens before any changes to the MidiProcessor object
        try {
            midiReceiver.open();
            receiver = midiReceiver.getReceiver();
        } catch (MidiUnavailableException e) {
            // Ensure that we close the device if we have opened it, then abort the method and forward the exception
            if (midiReceiver.isOpen()) { midiReceiver.close(); }
            throw e;
        }

        // Update the objects
        if (this.midiReceiver != null && this.midiReceiver.isOpen()) { this.midiReceiver.close(); }
        this.midiReceiver = midiReceiver;
        this.midiOut = receiver;
        if (debugMode) {
            Logger.getLogger("com.noahr_atc.midisplitter").log(Level.INFO, "Sending to: " + midiReceiver.getDeviceInfo().getName());
        }
    } // End setReceiver(MidiDevice) method

    /**
     * Sets the {@link Receiver} that processed messages are sent to.
     *
     * @param receiver the desired MIDI receiver
     * @see #setReceiver(MidiDevice)
     */
    public void setReceiver(Receiver receiver) {
        if (midiReceiver != null) {
            if (midiReceiver.isOpen()) { midiReceiver.close(); }
            midiReceiver = null;
        }
        this.midiOut = receiver;
        if (debugMode) {
            Logger.getLogger("MidiProcessor").log(Level.INFO, "Sending to Receiver: " + midiOut.toString());
        }
    } // End setReceiver(Receiver) method

    /**
     * Reports whether or not a MIDI channel is currently in use.
     *
     * @param channel the channel to check for availability
     * @return {@code true} if {@code channel} is available; {@code false} if {@code channel} is out of range or in use
     */
    public boolean channelAvailable(int channel) { return (!channelStatuses[channel].inUse()); }

    /**
     * Finds the first MIDI channel from 0 that a message hasn't currently been translated to.
     *
     * @return the MIDI channel number of the available channel
     * @throws ExceededMidiChannelsException if all MIDI channels are in use
     */
    public int firstAvailableChannel() throws ExceededMidiChannelsException {
        // Iterate over the array returning as soon as an available channel is found, throwing an exception if array exhausted
        for (byte i = 0; i < channelStatuses.length; i++) { if (!channelStatuses[i].inUse()) { return i; }}
        throw new ExceededMidiChannelsException();
    } // End firstAvailableChannel method

    /**
     * Finds the MIDI channel with the least amount of uses, returning the lower channel if multiple have an equal number of uses.
     *
     * @return the MIDI channel number of the least used channel
     */
    public int leastUsedChannel() {
        int indexOfLeastUsed = 0;
        for (byte i = 1; i < channelStatuses.length; i++) {
            if (channelStatuses[i].getUses() < channelStatuses[indexOfLeastUsed].getUses()) { indexOfLeastUsed = i; }
        }
        return indexOfLeastUsed;
    } // End leastUsedChannel method

    /**
     * Translates the provided message onto the correct MIDI channel. If it is available, the original channel of {@code message} is chosen,
     * otherwise the next available channel from 0 is chosen.
     *
     * @param message the message to be translated
     * @return the translated version of {@code message}
     */
    public ShortMessage translateMessage(ShortMessage message) {
        if (message == null) { return null; } // Null check

        // Interpret the command contained in the message
        switch (message.getCommand()) {
            case ShortMessage.NOTE_ON: { // MIDI NOTE-ON event
                int newChannel;     // The new channel to assign to the MIDI message
                NoteMapping key;    // The hashmap key for the MIDI message

                // If the original channel isn't available, attempt to assign the first available channel. If no channels
                // are available, lazily distribute the message and any that come before a channel becomes available across
                // all of the MIDI channels
                // Note: This distribution is done so that if a channel opens up a new note immediately starts playing,
                //      therefore even if a burst of messages come through it is more unlikely for one channel to be empty
                //      when there are multiple notes stacked on another
                if (!channelAvailable(message.getChannel())) {
                    try { newChannel = firstAvailableChannel(); } catch (ExceededMidiChannelsException e) {
                        newChannel = leastUsedChannel();
                    } // End try {} catch (ExceededMidiChannelsException)
                } // End if(!channelAvailable(message.channel))
                else { newChannel = message.getChannel(); }

                // Create the translation table entry, add a usage to the channel status, and update the message with the
                // new channel, ignoring the possibility of an InvalidMidiDataException.
                // Rationale: I know newChannel is valid, and that is the only value I'm changing in the message that is
                //      currently guaranteed to be valid, so there should not be any issues. Additionally, in the *extremely*
                //      unlikely case the exception does get raised, the message will simply be untranslated with no side effects
                // Note: In order to avoid any side effects upon an exception, we must introduce 'key' in order to support having
                //      the exception-producing method call before the translation is created
                try {
                    key = new NoteMapping(message.getChannel(), message.getData1());
                    message.setMessage(message.getCommand(), message.getChannel(), message.getData1(), message.getData2());
                    noteTranslations.put(
                            key,
                            new NoteMapping(newChannel, message.getData1())
                    ); // End put call
                    channelStatuses[newChannel].addUse();

                    // If in debug mode, construct and output the translation debugging message
                    if (debugMode) {
                        StringBuilder debugMessage = new StringBuilder();
                        debugMessage.append("NOTE-ON [").append(key.getChannel()).append("] --> [").append(newChannel).append("]; ");
                        for (ChannelStatus c : channelStatuses) { debugMessage.append(c.getUses()).append(","); }
                        System.out.println(debugMessage.toString());
                    } // End if(debugMode)
                } catch (InvalidMidiDataException ignored) {}

                // Return the reconstructed message
                return message;
            } // End message == NOTE_ON case
            case ShortMessage.NOTE_OFF: { // MIDI NOTE-OFF event
                NoteMapping originalMessage;     // The NoteMapping of the original message
                NoteMapping translatedMessage;   // The NoteMapping of the translated message
                List<NoteMapping> multimapEntry; // The List entry from the noteTranslations multimap

                // Make a NoteMapping for the original message
                originalMessage = new NoteMapping(message.getChannel(), message.getData1());

                // Retrieve the corresponding translated message, returning the original message if an entry isn't found
                // Note: noteTranslations.get returns empty list if not found
                // Note 2: If an entry isn't found, the NOTE-ON event was probably sent before we started splitting notes,
                //      so it's probably a good idea to send the unmodified NOTE-OFF anyways
                multimapEntry = noteTranslations.get(originalMessage);
                if (multimapEntry.isEmpty() || multimapEntry.get(0) == null) {
                    if (debugMode) {
                        StringBuilder debugMessage = new StringBuilder();
                        debugMessage.append("NOTE-OFF [").append(message.getChannel()).append("] <X>; ");
                        for (ChannelStatus c : channelStatuses) { debugMessage.append(c.getUses()).append(","); }
                        System.out.println(debugMessage.toString());
                    } // End if(debugMode)
                    return message;
                }
                translatedMessage = noteTranslations.get(originalMessage).get(0);

                // Remove the hashmap entry and channel usage, and attempt to recreate the message with the translated channel
                // Note: See above for related rationale about ignoring the InvalidMidiDataException, but in this case we
                //      will always remove the entry so it does not get orphaned
                NoteMapping key = new NoteMapping(message.getChannel(), message.getData1());
                noteTranslations.remove(key, translatedMessage);
                channelStatuses[translatedMessage.getChannel()].removeUse();
                try {
                    message.setMessage(
                            message.getCommand(),
                            translatedMessage.getChannel(),
                            message.getData1(),
                            message.getData2()
                    ); // End setMessage call
                } catch (InvalidMidiDataException ignored) {}

                // If in debug mode, construct and output the translation debugging message
                if (debugMode) {
                    StringBuilder debugMessage = new StringBuilder();
                    debugMessage.append("NOTE-OFF [").append(key.getChannel()).append("] --> [").append(translatedMessage.getChannel()).append("]; ");
                    for (ChannelStatus c : channelStatuses) { debugMessage.append(c.getUses()).append(","); }
                    System.out.println(debugMessage.toString());
                } // End if(debugMode)

                // Return the reconstructed message
                return message;
            } // End message == NOTE_OFF case
            case ShortMessage.PROGRAM_CHANGE: { // Triggered by loading/seeking/stopping
                // Clear all note mappings to prevent orphaning notes
                noteTranslations.clear();
                channelStatuses = new ChannelStatus[]{
                        new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                        new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                        new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus(),
                        new ChannelStatus(), new ChannelStatus(), new ChannelStatus(), new ChannelStatus()
                }; // End ChannelStatus[] initialization
                if (debugMode) {
                    StringBuilder debugMessage = new StringBuilder();
                    debugMessage.append("RESET-TRANSLATIONS; ");
                    for (ChannelStatus c : channelStatuses) { debugMessage.append(c.getUses()).append(","); }
                    System.out.println(debugMessage.toString());
                } // End if(debugMode)
                return message;
            } // End message == PROGRAM_CHANGE case
            default: { // Message is of unknown type, do nothing to it
                return message;
            } // End default case
        } // End switch(message)
    } // End translateMessage method

    /**
     * Indicates that all 16 MIDI channels are in use and the current note cannot be distributed to a unique channel.
     *
     * @author Noah Reeder
     * @version 1.0
     * @since 2020-03-08
     */
    public static class ExceededMidiChannelsException extends Exception implements Serializable {
        /**
         * Constructs a {@code ExceededMidiChannelsException} with a detailed message of {@code null}.
         */
        public ExceededMidiChannelsException() { super(); }

        /**
         * Constructs an {@code ExceededMidiChannelsException} with a specific detailed error message.
         *
         * @param message the detailed error message to be contained
         */
        public ExceededMidiChannelsException(String message) { super(message); }

        /**
         * Constructs an {@code ExceededMidiChannelsException} containing the cause of the exception.
         *
         * @param cause the throwable that caused the {@code ExceededMidiChannelsException} to be raised
         */
        public ExceededMidiChannelsException(Throwable cause) { super(cause); }

        /**
         * Constructs an {@code ExceededMidiChannelsException} containing an error message and the cause of the exception.
         *
         * @param message the detailed error message to be contained
         * @param cause   the throwable that caused the {@code ExceededMidiChannelsException} to be raised
         */
        public ExceededMidiChannelsException(String message, Throwable cause) { super(message, cause); }
    } // End ExceededMidiChannelsException class

    /**
     * Represents an entry in a {@link MidiProcessor}'s note translation map.
     * <br><br>
     * Note: The Java implementation of MIDI uses {@code int} to represent all MIDI values, however they should still reflect the
     * 7/4 bit values specified in the MIDI standard.<br>
     * Note 2: MIDI note velocity is not stored because the velocity of the NOTE-OFF message is always 0 and thus won't match the NOTE-ON.
     *
     * @author Noah Reeder
     * @version 1.0
     * @see <a href=https://www.midi.org/specifications-old/item/table-1-summary-of-midi-message>Summary of MIDI Messages</a>
     * @since 2020-03-08
     */
    protected static class NoteMapping {
        private int channel;    // The MIDI channel of the note
        private int noteNumber; // The MIDI note/key number of the note

        /**
         * Constructs a {@code NoteMapping} with the channel and note set to 0.
         */
        public NoteMapping() { noteNumber = 0; channel = 0; }

        /**
         * Constructs a {@code NoteMapping} verbosely using the provided information.
         *
         * @param channel    the note's channel
         * @param noteNumber the MIDI key/note that the message concerns
         */
        public NoteMapping(int channel, int noteNumber) {
            this.channel = channel;
            this.noteNumber = noteNumber;
        } // End NoteMapping(byte, byte, byte) constructor

        /**
         * Determines whether or not an object is equal to this {@code NoteMapping}.
         *
         * @param o the object to compare
         * @return {@code true} if {@code o} is equal to this {@code NoteMapping}, otherwise {@code false}
         */
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            NoteMapping that = (NoteMapping) o;
            return channel == that.channel &&
                    noteNumber == that.noteNumber;
        } // End equals method

        /**
         * Calculates the hash code of this NoteMapping.
         *
         * @return the hash code
         */
        @Override
        public int hashCode() { return Objects.hash(channel, noteNumber); }

        /**
         * Retrieves the MIDI channel of the note contained in this mapping entry.
         *
         * @return the 4-bit number of the MIDI channel
         */
        public int getChannel() { return channel; }

        /**
         * Assigns the MIDI channel of the note contained in this mapping entry.
         *
         * @param channel the 4-bit number of the MIDI channel to set
         */
        public void setChannel(int channel) { this.channel = channel; }

        /**
         * Retrieves the MIDI number for the note/key contained in this mapping entry.
         *
         * @return the 7-bit MIDI number of the note
         */
        public int getNoteNumber() { return noteNumber; }

        /**
         * Assigns the MIDI number for the note/key contained in this mapping entry.
         *
         * @param noteNumber the 7-bit MIDI number of the note to set
         */
        public void setNoteNumber(int noteNumber) { this.noteNumber = noteNumber; }
    } // End NoteMapping class

    /**
     * Represents whether or not a MIDI channel is available, as well as how many notes need to be turned off before the
     * will be available.
     *
     * @author Noah Reeder
     * @version 1.0
     * @since 2020-03-08
     */
    protected static class ChannelStatus {
        private int uses; // The counter for the current number of uses/note-on events on this channel

        /**
         * Constructs a {@code ChannelStatus} with the current number of uses at 0.
         */
        public ChannelStatus() { uses = 0; }

        /**
         * Constructs a {@code ChannelStatus} with a specific number of current uses.
         *
         * @param initialUses the number of times this channel is currently being used
         */
        public ChannelStatus(int initialUses) {uses = initialUses;}

        /**
         * Adds one to the number of times this channel is being used.
         */
        public void addUse() { uses += 1; }

        /**
         * Subtracts one from the number of times this channel is being used. When all uses are subtracted, the channel is
         * considered available.
         */
        public void removeUse() {if (uses > 0) {uses -= 1;}}

        /**
         * Checks whether or not the channel is currently in use.
         *
         * @return {@code true} if uses is greater than 0, otherwise {@code false}
         */
        public boolean inUse() { return uses > 0; }

        /**
         * Retrieves the number of times this channel is in use.
         *
         * @return number of uses
         */
        public int getUses() { return uses; }
    } // End ChannelStatus class
} // End MidiProcessor class

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

import android.media.midi.MidiReceiver;
import android.util.Log;

import androidx.annotation.Nullable;

import java.util.Arrays;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MetaMessage;
import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.Receiver;
import jp.kshoji.javax.sound.midi.ShortMessage;
import jp.kshoji.javax.sound.midi.SysexMessage;
import jp.kshoji.javax.sound.midi.Transmitter;

/**
 * A {@link MidiReceiver} that also acts as a {@link Transmitter} in order to bridge Android and Java MIDI APIs.
 */
public class MidiTransmitterAdapter extends MidiReceiver implements Transmitter {
    private Receiver javaReceiver;

    /**
     * Constructs a new {@code MidiTransmitterAdapter}.
     */
    public MidiTransmitterAdapter() { }

    /**
     * Set the {@link Receiver} for this {@link Transmitter}
     *
     * @param receiver the Receiver
     */
    @Override
    public void setReceiver(@Nullable Receiver receiver) { javaReceiver = receiver; }

    /**
     * Get the {@link Receiver} for this {@link Transmitter}
     *
     * @return the Receiver
     */
    @Nullable
    @Override
    public Receiver getReceiver() { return javaReceiver; }

    /**
     * Releases held resources. <b>MUST</b> be called before this {@code MidiTransmitterAdapter}'s destruction.
     */
    @Override
    public void close() {if (javaReceiver != null) { javaReceiver.close(); } }

    /**
     * Triggered whenever the receiver is passed new MIDI data. May fail if count exceeds {@link #getMaxMessageSize()}.
     *
     * @param msg       a byte array containing the MIDI data
     * @param offset    the offset of the first byte of the data in the array to be processed
     * @param count     the number of bytes of MIDI data in the array to be processed
     * @param timestamp the timestamp of the message (based on {@link System#nanoTime}
     */
    @Override
    public void onSend(byte[] msg, int offset, int count, long timestamp) {
        if (javaReceiver != null && msg != null) {
            // Construct the MidiMessage to send. Based off of the implSend method of the OpenJDK 8
            // implementation of com.sun.media.sound.MidiOutDevice.MidiOutReceiver
            // Reminder: count is the length of the message
            MidiMessage outMessage;
            final byte[] messageBytes = Arrays.copyOfRange(msg, offset, offset + count);
            final int status = (count > 1) ? messageBytes[0] & 0xFF : 0;

            // Convert the raw parameters to a MidiMessage implementation instance
            try {
                // Check if the message is a normal ShortMessage or MetaMessage
                if (count <= 3 && status != 0xF0 && status != 0xF7) {
                    if (count > 1 && status == 0xFF) { // MetaMessage, sys-reset (also 0xFF) has length of 1
                        outMessage = new MetaMessage(messageBytes[1], messageBytes, count);
                    }
                    else { // ShortMessage
                        outMessage = new ShortMessage(status,
                                (count > 1) ? messageBytes[1] : 0,
                                (count > 2) ? messageBytes[2] : 0
                        );
                    } // End if(outMessage ∈ MetaMessage) {} else
                } // End if(outMessage ∈ {MetaMessage, ShortMessage})

                // Check if the message is a malformed NOTE-ON or NOTE-OFF.
                // Due to what appears to be an OS bug, sometimes when multiple NOTE-ON/NOTE-OFF messages
                // are sent at the same time instead of appearing as two messages with length 3 they
                // get sent as one message with a length of 6. Therefore we have a catch here for
                // NOTE-ON/NOTE-OFF messages with a length greater than 3 and split them up
                else if (status >= 0x80 && status <= 0x9F) { // NOTE-ON channel 1 to NOTE-OFF channel 16
                    outMessage = new ShortMessage(status, messageBytes[1], messageBytes[2]);
                    javaReceiver.send(outMessage, timestamp);
                    onSend(messageBytes, 3, count - 3, timestamp);
                    return;
                } // End if(outMessage ∈ {MetaMessage, ShortMessage}) {} else if(mashedNote)

                // If neither of the above clauses caught this message then it must be a system exclusive message
                else {  // SysexMessage
                    outMessage = new SysexMessage(messageBytes, count);
                } // End if(outMessage ∈ {MetaMessage, ShortMessage}) {} else if(mashedNote) {} else

            } // End try {new MidiMessage}
            catch (InvalidMidiDataException e) {
                Log.e(MidiTransmitterAdapter.class.getName() + "->onSend", "Invalid MIDI message encountered", e);
                return;
            } // End try {new MidiMessage} catch(InvalidMidiDataException)

            // Send the created message to the receiver
            javaReceiver.send(outMessage, timestamp);
        } // End if(javaReceiver != null && msg != null)
    } // End onSend method
} // End MidiTransmitterAdapter class

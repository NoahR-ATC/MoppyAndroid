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

public class MidiTransmitterAdapter extends MidiReceiver implements Transmitter {
    private Receiver javaReceiver;

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
    public void close() { javaReceiver.close(); }

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
            final int status = (count > 1) ? messageBytes[offset] & 0xFF : 0;

            // Convert the raw parameters to a MidiMessage implementation instance
            try {
                if (count <= 3 && status != 0xF0 && status != 0xF7) { // MetaMessage or ShortMessage
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
                else { // SysexMessage
                    outMessage = new SysexMessage(messageBytes, count);
                } // End if(outMessage ∈ {MetaMessage, ShortMessage}) {} else
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

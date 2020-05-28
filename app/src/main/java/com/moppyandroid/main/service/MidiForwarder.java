package com.moppyandroid.main.service;

import androidx.annotation.NonNull;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.Receiver;

/**
 * {@link Receiver} that simply forwards received messages to another {@link Receiver} set with {@link #setReceiver(Receiver)}.
 */
public class MidiForwarder implements Receiver {
    private Receiver receiver;

    /**
     * Constructs a new {@code MidiForwarder}.
     */
    public MidiForwarder() {}

    /**
     * Triggered when a {@link MidiMessage} is received.
     */
    @Override
    public void send(@NonNull MidiMessage message, long timeStamp) {
        if (receiver != null) {
            receiver.send(message, timeStamp);
        }
    } // End send method

    /**
     * Does nothing. Part of the {@link Receiver} interface so it needs to be implemented.
     */
    @Override
    public void close() { /* Nothing to close here */ }

    /**
     * Gets the current {@link Receiver} that {@link MidiMessage}s are forwarded to.
     *
     * @return the current receiver
     */
    public Receiver getReceiver() { return receiver; }

    /**
     * Sets the {@link Receiver} to forward {@link MidiMessage}s to.
     *
     * @param receiver the receiver to send messages to
     */
    public void setReceiver(Receiver receiver) { this.receiver = receiver; }
} // End MidiForwarder class

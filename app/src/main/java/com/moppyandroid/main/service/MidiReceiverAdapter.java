package com.moppyandroid.main.service;

import android.media.midi.MidiReceiver;
import android.util.Log;

import androidx.annotation.NonNull;

import java.io.IOException;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.Receiver;

/**
 * A {@link jp.kshoji.javax.sound.midi.Receiver} that streams all messages to a {@link android.media.midi.MidiReceiver}.
 */
public class MidiReceiverAdapter implements Receiver {
    private MidiReceiver androidReceiver;
    private IOExceptionCallback callback;

    /**
     * Constructs a {@code MidiReceiverAdapter} that simply logs {@link IOException}s in {@link #send(MidiMessage, long)}.
     *
     * @param receiver the {@link MidiReceiver} to send messages to
     * @see #MidiReceiverAdapter(MidiReceiver, IOExceptionCallback)
     */
    public MidiReceiverAdapter(MidiReceiver receiver) { androidReceiver = receiver; }

    /**
     * Constructs a {@code MidiReceiverAdapter} that logs {@link IOException}s in {@link #send} as well as triggers
     * the {@link IOExceptionCallback#onIOException(IOException)} method of the provided {@link IOExceptionCallback}.
     *
     * @param receiver the {@link MidiReceiver} to send messages to
     * @param callback the {@link IOExceptionCallback} to callback {@link IOException}s to
     */
    public MidiReceiverAdapter(MidiReceiver receiver, IOExceptionCallback callback) {
        this(receiver);
        this.callback = callback;
    } // End MidiReceiverAdapter(MidiReceiver, IOExceptionCallback) constructor

    /**
     * Receives a {@link MidiMessage} and forwards it to the associated {@link MidiReceiver}.
     *
     * @param message   the received message
     * @param timeStamp -1 if the timeStamp information is not available
     */
    @Override
    public void send(@NonNull MidiMessage message, long timeStamp) {
        try { androidReceiver.send(message.getMessage(), 0, message.getLength(), timeStamp); }
        catch (IOException e) {
            // Can't rethrow because that would break the method signature
            Log.e(MidiReceiverAdapter.class.getName() + "->send", "Unable to send message", e);
            if (callback != null) { callback.onIOException(e); }
        } // End try {androidReceiver.send} catch(IOException)
    } // End send method

    /**
     * Does nothing. Part of the {@link Receiver} interface so it needs to be implemented.
     */
    @Override
    public void close() { /* Nothing to close here */ }

    /**
     * Callback triggered when an {@link IOException} is raised.
     */
    public interface IOExceptionCallback {
        void onIOException(IOException e);
    } // End IOExceptionCallback interface
} // End MidiReceiverAdapter class

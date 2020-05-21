package com.moppyandroid.main.service;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.Receiver;

/**
 * {@link Receiver} used to dispatch received {@link MidiMessage}s to a list of {@code Receiver}s.
 */
public class ReceiverDispatcher implements Receiver {
    private List<Receiver> receiverList;

    /**
     * Constructs a new {@code ReceiverDispatcher}.
     */
    public ReceiverDispatcher() { receiverList = new ArrayList<>(); }

    /**
     * Adds a {@link Receiver} to the list to forward messages to. Ignores {@code null} {@code Receiver}s and duplicates.
     *
     * @param receiver the {@link Receiver} to add
     * @return {@code true} if {@code receiver} was added, {@code false} if {@code receiver} was null or duplicate
     * @see #remove(Receiver)
     */
    public boolean add(Receiver receiver) {
        if (receiver == null || receiverList.contains(receiver)) { return false; }
        return receiverList.add(receiver);
    } // End add method

    /**
     * Removes a {@link Receiver} from the list to forward messages to.
     *
     * @param receiver the {@link Receiver} to remove
     * @return {@code true} if {@code receiver} was removed, {@code false} if it was {@code null} or not added
     * @see #add(Receiver)
     */
    public boolean remove(Receiver receiver) {
        if (receiver == null || !receiverList.contains(receiver)) { return false; }
        return receiverList.remove(receiver);
    } // End remove method

    /**
     * Sends a {@link MidiMessage} to all registered {@link Receiver}s.
     *
     * @param message   the received message
     * @param timeStamp -1 if the timeStamp information is not available
     */
    @Override
    public void send(@NonNull MidiMessage message, long timeStamp) {
        receiverList.forEach((r -> r.send(message, timeStamp)));
    } // End send method

    /**
     * Closes all registered {@link Receiver}s.
     */
    @Override
    public void close() { receiverList.forEach((Receiver::close)); }
} // End ReceiverDispatcher class

// Originally written by Sam Archer https://github.com/SammyIAm/Moppy2, modified by Noah Reeder.
// Modifications include:
//      - Explicit usage of RealTimeSequencer
//      - Adapting loadSequence to work more fluidly with Android
//      - Addition of methods:
//              getMillisecondsLength()
//              getMillisecondsPosition()
//              setMillisecondsPosition(long)
//              getTempoFactor()
//              setTempoFactor(float)
// Last merged 2021-02-21

// Generated by delombok at Thu Nov 21 19:52:46 CST 2019

package com.moppy.core.midi;

import com.moppy.core.status.StatusBus;
import com.moppy.core.status.StatusUpdate;
import com.sun.media.sound.RealTimeSequencerProvider;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

import jp.kshoji.javax.sound.midi.InvalidMidiDataException;
import jp.kshoji.javax.sound.midi.MetaEventListener;
import jp.kshoji.javax.sound.midi.MetaMessage;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;
import jp.kshoji.javax.sound.midi.Sequence;
import jp.kshoji.javax.sound.midi.Sequencer;

/**
 * Wrapper around the Java MIDI sequencer for playing MIDI files.
 * <p>
 * Additionally provides feedback to listeners about the current state of the sequencer.
 */
public class MoppyMIDISequencer implements MetaEventListener, Closeable {
    private static final Logger LOG = Logger.getLogger(MoppyMIDISequencer.class.getName());
    private final Sequencer seq;
    private final StatusBus statusBus;
    private boolean autoReset = false;

    public MoppyMIDISequencer(StatusBus statusBus, MoppyMIDIReceiverSender receiverSender) throws MidiUnavailableException {
        this.statusBus = statusBus;
        this.statusBus.registerConsumer(receiverSender); // Register receiverSender to send seq messages to network

        //seq = MidiSystem.getSequencer(false);
        seq = (Sequencer) (new RealTimeSequencerProvider().getDevice(null));
        if (seq == null) {
            throw new MidiUnavailableException("Unable to create RealTimeSequencerProvider");
        }
        seq.open();
        seq.getTransmitter().setReceiver(receiverSender);
        seq.addMetaEventListener(this);
    }

    @Override
    public void close() throws IOException {
        seq.close();
    }

    @Override
    public void meta(MetaMessage meta) {
        // Handle tempo changes
        if (meta.getType() == 81) {
            int uSecondsPerQN = 0;
            uSecondsPerQN |= meta.getData()[0] & 255;
            uSecondsPerQN <<= 8;
            uSecondsPerQN |= meta.getData()[1] & 255;
            uSecondsPerQN <<= 8;
            uSecondsPerQN |= meta.getData()[2] & 255;
            int newTempo = 60000000 / uSecondsPerQN;
            setTempo(newTempo);
        }
        else
            // Handle end-of-track events
            if (meta.getType() == 47) {
                seq.setTickPosition(0); // Reset sequencer so we can press "play" again right away
                //MrSolidSnake745: Exposing end of sequence event to status consumers
                statusBus.receiveUpdate(StatusUpdate.sequenceEnd(autoReset));
            }
    }

    public void play() {
        seq.start();
        statusBus.receiveUpdate(StatusUpdate.SEQUENCE_START);
    }

    public void pause() {
        seq.stop();
        statusBus.receiveUpdate(StatusUpdate.SEQUENCE_PAUSE);
    }

    public void stop() {
        seq.stop();
        seq.setTickPosition(0);
        statusBus.receiveUpdate(StatusUpdate.SEQUENCE_STOPPED); // Always reset when stop button is pressed
    }

    public boolean isPlaying() {
        return seq.isRunning();
    }

    public void loadSequence(Sequence sequenceToLoad) throws InvalidMidiDataException {
        seq.setSequence(sequenceToLoad);
        statusBus.receiveUpdate(StatusUpdate.sequenceLoaded(sequenceToLoad));
        statusBus.receiveUpdate(StatusUpdate.tempoChange(seq.getTempoInBPM()));

        // Source says "Loaded sequence with %s tracks at %s BMP", typo fixed here
        LOG.info(String.format("Loaded sequence with %s tracks at %s BPM", sequenceToLoad.getTracks().length - 1, seq.getTempoInBPM())); // -1 for system track?
    }

    public boolean isSequenceLoaded() {
        return seq.getSequence() != null;
    }

    public long getSecondsLength() {
        return TimeUnit.SECONDS.convert(seq.getMicrosecondLength(), TimeUnit.MICROSECONDS);
    }

    public long getSecondsPosition() {
        return TimeUnit.SECONDS.convert(seq.getMicrosecondPosition(), TimeUnit.MICROSECONDS);
    }

    public void setSecondsPosition(long seconds) {
        seq.setMicrosecondPosition(TimeUnit.SECONDS.toMicros(seconds));
    }

    public void setTempo(float newTempo) {
        seq.setTempoInBPM(newTempo);
        statusBus.receiveUpdate(StatusUpdate.tempoChange(newTempo));
        LOG.info(String.format("Tempo changed to %s", newTempo));
    }

    @java.lang.SuppressWarnings("all")
    public void setAutoReset(final boolean autoReset) {
        this.autoReset = autoReset;
    }

    public long getMillisecondsLength() { return TimeUnit.MILLISECONDS.convert(seq.getMicrosecondLength(), TimeUnit.MICROSECONDS); }

    public long getMillisecondsPosition() { return TimeUnit.MILLISECONDS.convert(seq.getMicrosecondPosition(), TimeUnit.MICROSECONDS); }

    public void setMillisecondsPosition(long pos) { seq.setMicrosecondPosition(TimeUnit.MILLISECONDS.toMicros(pos)); }

    public float getTempoFactor() { return seq.getTempoFactor(); }

    public void setTempoFactor(float factor) { seq.setTempoFactor(factor); }
}

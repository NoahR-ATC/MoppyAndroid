package com.moppyandroid.main.service;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;

public class MoppyMidiService extends MidiDeviceService {
    /**
     * Returns an array of {@link MidiReceiver} for the device's input ports.
     * Subclasses must override this to provide the receivers which will receive
     * data sent to the device's input ports. An empty array should be returned if
     * the device has no input ports. TODO
     *
     * @return array of MidiReceivers
     */
    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[0];
    }
}

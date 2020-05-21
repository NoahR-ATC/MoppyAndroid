package com.moppyandroid.main.service;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.NonNull;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.Receiver;

public class MoppyMidiService extends MidiDeviceService {
    private MidiTransmitterAdapter midiToService;
    private MidiReceiverAdapter midiFromService;
    private MoppyServiceConnection moppyServiceConnection;
    private MoppyMediaService mediaService;

    /**
     * Triggered when one of this {@code MoppyMidiService}'s ports is being opened and the {@code MoppyMidiService}
     * needs to be started.
     */
    @Override
    public void onCreate() {
        midiToService = new MidiTransmitterAdapter();

        // Needs to be after midiToService is created but before getOutputPortReceivers
        super.onCreate();

        midiFromService = new MidiReceiverAdapter(getOutputPortReceivers()[0]); // Gets the first (only) output port

        // TODO: Remove, tests that midiToService and midiFromService can receive input. midiToService messages
        //  are being looped through midiFromService.
        midiToService.setReceiver(new Receiver() {
            @Override
            public void send(@NonNull MidiMessage message, long timeStamp) {
                Log.i("MIDI to service", message.toString());
                midiFromService.send(message, timeStamp);
            }

            @Override
            public void close() { }
        });

        moppyServiceConnection = new MoppyServiceConnection();
        Intent bindIntent = new Intent(this, MoppyMediaService.class);
        bindIntent.putExtra(MoppyMediaService.EXTRA_BIND_NORMAL, true);
        bindService(bindIntent, moppyServiceConnection, Context.BIND_AUTO_CREATE);
    } // End onCreate method

    /**
     * Triggered when this {@code MoppyMidiService} is no longer needed and is getting destroyed.
     */
    @Override
    public void onDestroy() {
        midiToService.close();
        midiFromService.close();
        unbindService(moppyServiceConnection);
        super.onDestroy();
    } // End onDestroy method

    /**
     * Returns an array only containing the one {@link MidiReceiver} used to send MIDI messages
     * to the {@link MoppyMediaService}.
     *
     * @return array (size 1) of MidiReceiver
     */
    @Override
    public MidiReceiver[] onGetInputPortReceivers() { return new MidiReceiver[]{ midiToService }; }

    // Identifies a connection to the MoppyMediaService and provides callbacks for the connection state
    private class MoppyServiceConnection implements ServiceConnection {
        /**
         * Called when a connection to the Service has been established, with
         * the {@link IBinder} of the communication channel to the
         * Service.
         *
         * <p class="note"><b>Note:</b> If the system has started to bind your
         * client app to a service, it's possible that your app will never receive
         * this callback. Your app won't receive a callback if there's an issue with
         * the service, such as the service crashing while being created.
         *
         * @param name    The concrete component name of the service that has
         *                been connected.
         * @param service The IBinder of the Service's communication channel,
         */
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mediaService = ((MoppyMediaService.Binder) service).getService();
            midiToService.setReceiver(mediaService.getInputReceiver());
            mediaService.setReceiver(midiFromService);
        } // End onServiceConnected method

        /**
         * Called when a connection to the Service has been lost.  This typically
         * happens when the process hosting the service has crashed or been killed.
         * This does <em>not</em> remove the ServiceConnection itself -- this
         * binding to the service will remain active, and you will receive a call
         * to {@link #onServiceConnected} when the Service is next running.
         *
         * @param name The concrete component name of the service whose
         *             connection has been lost.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            midiToService.setReceiver(null);
        } // End onServiceDisconnected method
    } // End MoppyServiceConnection class
} // End MoppyMidiService class

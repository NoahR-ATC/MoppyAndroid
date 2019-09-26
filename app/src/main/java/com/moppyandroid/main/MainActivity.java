package com.moppyandroid.main;

//import com.moppy.*;

import androidx.appcompat.app.AppCompatActivity;

import android.app.usage.NetworkStatsManager;
import android.widget.TextView;
import android.os.Bundle;

import com.moppy.core.events.mapper.MapperCollection;
import com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppy.core.midi.MoppyMIDISequencer;
import com.moppy.core.status.StatusBus;
import com.moppy.control.NetworkManager;
import com.moppyandriod.R;

import javax.sound.midi.MidiUnavailableException;

public class MainActivity extends AppCompatActivity {
    static { // Load the C++ library
        System.loadLibrary("MoppyAndroidLib");
    }

    private void init() throws java.io.IOException, javax.sound.midi.MidiUnavailableException
    {
        statusBus = new StatusBus();
        mappers = new MapperCollection();
        netManager = new NetworkManager(statusBus);
        netManager.start();
        receiverSender = new MoppyMIDIReceiverSender(mappers, postProcessor, netManager.getPrimaryBridge());
        seq = new MoppyMIDISequencer(statusBus, receiverSender);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView textView = (TextView)findViewById(R.id.text_view);
        textView.setText(GetStringEdited("Hello!"));

        try { init(); }
        catch (Exception e) {  }
    }

    public native String GetString();
    public native String GetStringEdited(String str);

    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private StatusBus statusBus;
    private MapperCollection mappers;
    private NetworkManager netManager;
    private MessagePostProcessor postProcessor;

}

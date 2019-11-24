package com.moppyandroid.main;

//import com.moppy.*;

import androidx.appcompat.app.AppCompatActivity;

import android.app.usage.NetworkStatsManager;
import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.ListView;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

import com.moppyandroid.BridgeSerial;
import com.moppyandroid.com.moppy.core.events.mapper.MapperCollection;
import com.moppyandroid.com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppyandroid.com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppyandroid.com.moppy.core.midi.MoppyMIDISequencer;
import com.moppyandroid.com.moppy.core.status.StatusBus;
import com.moppyandroid.com.moppy.control.NetworkManager;

import com.moppyandriod.R;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;

public class MainActivity extends AppCompatActivity {
    static { // Load the C++ library
        System.loadLibrary("MoppyAndroidLib");
    }

    private void init() throws java.io.IOException, MidiUnavailableException
    {
        BridgeSerial.init(this);
        statusBus = new StatusBus();
        mappers = new MapperCollection<>();
        netManager = new NetworkManager(statusBus);
        netManager.start();
        receiverSender = new MoppyMIDIReceiverSender(mappers, postProcessor, netManager.getPrimaryBridge());
        seq = new MoppyMIDISequencer(statusBus, receiverSender);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView textView = findViewById(R.id.text_view);
        ListView listView = findViewById(R.id.listView);
        textView.setText(GetStringEdited("Hello!"));

        try { init(); }
        catch (Exception e) {  }

        ArrayList<String> arrayList = new ArrayList<>(BridgeSerial.getAvailableSerials());
        ArrayAdapter adapter = new ArrayAdapter<>(this,android.R.layout.simple_list_item_1,arrayList);
        listView.setAdapter(adapter);
    }

    public native String GetString();
    public native String GetStringEdited(String str);

    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private StatusBus statusBus;
    private MapperCollection<MidiMessage> mappers;
    private NetworkManager netManager;
    private MessagePostProcessor postProcessor;

}

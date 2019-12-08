package com.moppyandroid.main;

//import com.moppy.*;

import androidx.appcompat.app.AppCompatActivity;

import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.view.ViewGroup;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.os.Bundle;

import com.moppyandroid.BridgeSerial;
import com.moppyandroid.com.moppy.core.events.mapper.MapperCollection;
import com.moppyandroid.com.moppy.core.events.postprocessor.MessagePostProcessor;
import com.moppyandroid.com.moppy.core.midi.MoppyMIDIReceiverSender;
import com.moppyandroid.com.moppy.core.midi.MoppyMIDISequencer;
import com.moppyandroid.com.moppy.core.status.StatusBus;
import com.moppyandroid.com.moppy.control.NetworkManager;
import com.sothree.slidinguppanel.SlidingUpPanelLayout;
import com.sothree.slidinguppanel.SlidingUpPanelLayout.PanelState;

import jp.kshoji.javax.sound.midi.MidiMessage;
import jp.kshoji.javax.sound.midi.MidiUnavailableException;

public class MainActivity extends AppCompatActivity {
    static { // Load the C++ library
        System.loadLibrary("MoppyAndroidLib");
    }

    public static final String ACTION_USB_PERMISSION = "com.moppyandroid.USB_PERMISSION";

    // Define the receiver to process relevant intent messages
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) throws NullPointerException {
            // Ensure intent action is not null
            if (intent.getAction() != null) {
                // Determine action and process accordingly
                if (intent.getAction().equals(ACTION_USB_PERMISSION)) { // Pass on to BridgeSerial
                    BridgeSerial.onActionUsbPermission(context, intent);
                } // End if(ACTION_USB_PERMISSION)
                else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                    {
                        AlertDialog.Builder b = new AlertDialog.Builder(context);
                        b.setTitle("MoppyAndroid");
                        b.setCancelable(false);
                        b.setMessage("ACTION_USB_DEVICE_DETACHED - BridgeSerial");
                        b.setPositiveButton("OK", null);
                        b.create().show();
                    }

                    /*try {
                        close();
                    } catch (IOException e) { // Ignore exception
                    }*/
                    {
                        {
                            AlertDialog.Builder b = new AlertDialog.Builder(context);
                            b.setTitle("MoppyAndroid");
                            b.setCancelable(false);
                            b.setMessage("USB device unplugged - BridgeSerial");
                            b.setPositiveButton("OK", null);
                            b.create().show();
                        }
                    }
                } // End if(ACTION_USB_DEVICE_DETACHED)
            } // End if(intent !null)
        } // End onReceive method
    }; // End new BroadcastReceiver

    private void init() throws java.io.IOException, MidiUnavailableException {
        BridgeSerial.init(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_USB_PERMISSION);
        intentFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(broadcastReceiver, intentFilter);
        statusBus = new StatusBus();
        mappers = new MapperCollection<>();
        netManager = new NetworkManager(statusBus);
        netManager.start();
        receiverSender = new MoppyMIDIReceiverSender(mappers, postProcessor, netManager.getPrimaryBridge());
        seq = new MoppyMIDISequencer(statusBus, receiverSender);
        usbManager = (UsbManager) getSystemService(USB_SERVICE);
    }

    private void RequestPermission(UsbDevice device) {
        Intent intent = new Intent(ACTION_USB_PERMISSION);
        BridgeSerial.ParcelableObject syncObject = new BridgeSerial.ParcelableObject();

        intent.putExtra("syncObject", syncObject);
        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
        usbManager.requestPermission(device, pendingIntent);
        {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("Awaiting permission");
            b.setPositiveButton("OK", null);
            b.create().show();
        }

        // Wait for the message to be processed
        synchronized (syncObject) {
            try {
                syncObject.wait();
            } catch (InterruptedException e) { // Notify called
            }
        }

        // Check if permission was granted
        if (!usbManager.hasPermission(device)) {
            AlertDialog.Builder alert = new AlertDialog.Builder(this);
            alert.setTitle("MoppyAndroid");
            alert.setCancelable(false);
            alert.setMessage("Permission is required to connect to the device");
            alert.setPositiveButton("OK", null);
            alert.setNegativeButton("Cancel", null);
            alert.create().show();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView textView = findViewById(R.id.text_view);
        textView.setText(GetStringEdited("Hello!"));
        findViewById(R.id.toolbar_song_title).setSelected(true);

        panelLayout = findViewById(R.id.sliding_panel_layout);
        RelativeLayout toolbarLayout = findViewById(R.id.toolbar_layout);
        panelLayout.setDragView(R.id.toolbar_layout);
        toolbarLayout.measure(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelLayout.setPanelHeight(toolbarLayout.getMeasuredHeight());
        textView.setText(GetStringEdited(String.valueOf(toolbarLayout.getHeight())));
        /*
        ListView listView = findViewById(R.id.listView);
        try {
            init();
        } catch (Exception e) {
        }
        UsbManager m = (UsbManager) getSystemService(USB_SERVICE);
        ArrayList<String> arrayList = new ArrayList<>(BridgeSerial.getAvailableSerials());
        for (int i = 0; i < arrayList.size(); i++) {
            String element = arrayList.get(i);
            if (m != null) {
                if (m.getDeviceList() != null) {
                    UsbDevice dev = m.getDeviceList().get(element);
                    element = element.concat(", ");
                    if (dev != null) {
                        if (dev.getProductName() != null) {
                            element = element.concat(dev.getProductName() + ", ");
                        }
                        element = element.concat(dev.getManufacturerName() + ", ");
                        element = element.concat(dev.getVendorId() + "/" + dev.getProductId() + ", ");
                    }
                    arrayList.set(i, element);
                }
            }
        }
        ArrayAdapter adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, arrayList);
        listView.setAdapter(adapter);

        {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("Creating BridgeSerial");
            b.setPositiveButton("OK", null);
            b.create().show();
        }

        {
            AlertDialog.Builder b = new AlertDialog.Builder(this);
            b.setTitle("MoppyAndroid");
            b.setCancelable(false);
            b.setMessage("App initialized");
            b.setPositiveButton("OK", null);
            b.create().show();
        }*/
    }

    // Taken from AndroidSlidingUpPanel demo application located at https://github.com/umano/AndroidSlidingUpPanel/tree/master/demo
    @Override
    public void onBackPressed() {
        if (panelLayout != null &&
                (panelLayout.getPanelState() == PanelState.EXPANDED || panelLayout.getPanelState() == PanelState.ANCHORED)) {
            panelLayout.setPanelState(SlidingUpPanelLayout.PanelState.COLLAPSED);
        } else {
            super.onBackPressed();
        }
    }

    public native String GetString();

    public native String GetStringEdited(String str);

    private MoppyMIDISequencer seq;
    private MoppyMIDIReceiverSender receiverSender;
    private StatusBus statusBus;
    private MapperCollection<MidiMessage> mappers;
    private NetworkManager netManager;
    private MessagePostProcessor postProcessor;
    private UsbManager usbManager;
    private SlidingUpPanelLayout panelLayout;

}



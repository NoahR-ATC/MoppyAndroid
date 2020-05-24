package com.moppyandroid.main;

import android.content.Context;
import android.media.midi.MidiDeviceInfo;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.moppyandroid.main.service.MidiPortInfoWrapper;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link List}{@code <}{@link MidiPortInfoWrapper}{@code >} adapter for a {@link android.widget.Spinner}
 * that displays the name of each port as a list entry.
 */
public class MidiSpinnerAdapter extends ArrayAdapter<MidiPortInfoWrapper> {
    private Context context;
    private List<MidiPortInfoWrapper> dataset;

    /**
     * Constructs a new {@code MidiSpinnerAdapter}.
     *
     * @param context       the context to create the {@code MidiSpinnerAdapter} in
     * @param infos         the {@link List} of {@link MidiPortInfoWrapper}s to display
     * @param selectInputs  set to {@code true} if input ports should appear in the list, {@code false} if not
     * @param selectOutputs set to {@code true} if output ports should appear in the list, {@code false} if not
     * @return the newly constructed {@code MidiSpinnerAdapter}
     */
    public static MidiSpinnerAdapter newInstance(Context context, List<MidiDeviceInfo> infos, boolean selectInputs, boolean selectOutputs) {
        if (context == null) { return null; }
        List<MidiPortInfoWrapper> dataset = new ArrayList<>();
        if (infos == null) { return new MidiSpinnerAdapter(context, dataset); } // Create empty

        // Add null entry to beginning to act as placeholder for "NONE"
        dataset.add(0, null);

        // Add each port info in the list of devices based on the selection criteria, keeping track of its device
        for (MidiDeviceInfo info : infos) {
            MidiDeviceInfo.PortInfo[] ports = info.getPorts();
            for (MidiDeviceInfo.PortInfo port : ports) {
                if (
                        (selectInputs && port.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT) ||
                        (selectOutputs && port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT)
                ) {
                    dataset.add(new MidiPortInfoWrapper(port, info));
                } // End if((selectInput && port.isInput) || (selectOutput && port.isOutput))
            } // End for(port : ports)
        } // End for(info : infos)

        // Since we can't call super here we need to return a new instance
        return new MidiSpinnerAdapter(context, dataset);
    } // End MidiSpinnerAdapter.newInstance factory method

    // Private constructor since we need to call super(...) but that needs to be the first call and we
    // need to do some processing of the provided list first
    private MidiSpinnerAdapter(Context context, List<MidiPortInfoWrapper> dataset) {
        super(context, android.R.layout.simple_spinner_dropdown_item, dataset);
        this.context = context;
        this.dataset = dataset;
    } // End MidiSpinnerAdapter constructor

    /**
     * Triggered when an entry is created.
     */
    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        // Construct view if necessary
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(android.R.layout.simple_spinner_item, parent, false);
        }

        // Set the text
        MidiPortInfoWrapper info = dataset.get(position);
        TextView textView = convertView.findViewById(android.R.id.text1);
        if (position == 0) { textView.setText(context.getString(R.string.none_entry)); }
        else {
            textView.setText(context.getString(R.string.midi_spinner_text, info.getParentName(), info.getName()));
        }

        return convertView;
    } // End getView method

    /**
     * Triggered when a dropdown entry is created.
     */
    @Override
    public View getDropDownView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        // Construct view if necessary
        if (convertView == null) {
            convertView = LayoutInflater.from(context).inflate(android.R.layout.simple_spinner_dropdown_item, parent, false);
        }

        // Set the text
        MidiPortInfoWrapper info = dataset.get(position);
        TextView textView = convertView.findViewById(android.R.id.text1);
        if (position == 0) { textView.setText(context.getString(R.string.none_entry)); }
        else {
            textView.setText(context.getString(R.string.midi_spinner_text, info.getParentName(), info.getName()));
        }

        return convertView;
    } // End getDropDownView method
} // End MidiSpinnerAdapter class

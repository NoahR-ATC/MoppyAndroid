/*
 * Copyright (C) 2021 Noah Reeder
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
    private boolean selectInputs;
    private boolean selectOutputs;

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
        if (infos == null) { // Create empty adapter
            return new MidiSpinnerAdapter(context, dataset, selectInputs, selectOutputs);
        }

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
        return new MidiSpinnerAdapter(context, dataset, selectInputs, selectOutputs);
    } // End MidiSpinnerAdapter.newInstance factory method

    // Private constructor since we need to call super(...) but that needs to be the first call and we
    // need to do some processing of the provided list first
    private MidiSpinnerAdapter(Context context, List<MidiPortInfoWrapper> dataset, boolean selectInputs, boolean selectOutputs) {
        super(context, android.R.layout.simple_spinner_dropdown_item, dataset);
        this.context = context;
        this.dataset = dataset;
        this.selectInputs = selectInputs;
        this.selectOutputs = selectOutputs;
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

    /**
     * Gets the index of the first entry for the specified {@link MidiPortInfoWrapper}.
     *
     * @param portInfo the port to look for
     * @return {@code -1} if not found, otherwise the index
     */
    public int getIndexOf(MidiPortInfoWrapper portInfo) { return dataset.indexOf(portInfo); }

    /**
     * Adds the relevant ports ports of a {@link MidiDeviceInfo} to the dataset if they are not already there.
     *
     * @param deviceInfo the parent of the ports to add
     */
    public void addDevice(MidiDeviceInfo deviceInfo) {
        if (deviceInfo == null) { return; }
        MidiDeviceInfo.PortInfo[] ports = deviceInfo.getPorts();
        for (MidiDeviceInfo.PortInfo port : ports) {
            if (
                    (selectInputs && port.getType() == MidiDeviceInfo.PortInfo.TYPE_INPUT) ||
                    (selectOutputs && port.getType() == MidiDeviceInfo.PortInfo.TYPE_OUTPUT)
            ) {
                MidiPortInfoWrapper portInfo = new MidiPortInfoWrapper(port, deviceInfo);
                if (!dataset.contains(portInfo)) { add(portInfo); }
            } // End if((selectInput && port.isInput) || (selectOutput && port.isOutput))
        } // End for(port : ports)
    } // End addDevice method

    /**
     * Removes all ports with a specific parent {@link MidiDeviceInfo} from the dataset.
     *
     * @param deviceInfo the parent whose children ports should be removed
     */
    public void removeDevice(MidiDeviceInfo deviceInfo) {
        List<MidiPortInfoWrapper> duplicateDataset = new ArrayList<>(dataset);
        for (MidiPortInfoWrapper portInfo : duplicateDataset) {
            if (portInfo != null && portInfo.getParent() != null && portInfo.getParent().equals(deviceInfo)) {
                remove(portInfo);
            } // End if(port.getParent().equals(deviceInfo))
        } // End for(portInfo : dataset)
    } // End removeAllFromDevice method

    /**
     * Checks whether any ports with a specific parent {@link MidiDeviceInfo} are present.
     *
     * @param deviceInfo the parent to look for
     * @return {@code true} if child ports of {@code parent} are found, otherwise {@code false}
     */
    public boolean containsDevice(MidiDeviceInfo deviceInfo) {
        for (MidiPortInfoWrapper portInfo : dataset) {
            if (portInfo != null && portInfo.getParent() != null && portInfo.getParent().equals(deviceInfo)) {
                return true;
            } // End if(portInfo.getParent.equals(deviceInfo))
        } // End for(portInfo : dataset)
        return false;
    } // End containsDevice method
} // End MidiSpinnerAdapter class

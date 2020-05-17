package com.moppyandroid.main;

import android.hardware.usb.UsbDevice;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link List}{@code <}{@link UsbDevice}{@code >} adapter for a {@link RecyclerView}
 * that displays a {@link CheckBox}, one of the provided {@code String}s, and an info icon as a list entry.
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {
    private List<UsbDevice> dataset;
    private boolean[] connectedDataset;
    private InfoClickListener infoClickListener;
    private CheckBoxListener checkboxListener;

    /**
     * Constructs a {@code DeviceAdapter} with a {@link List} of devices to display.
     *
     * @param dataset the {@link List} to show
     */
    public DeviceAdapter(List<UsbDevice> dataset, List<String> connectedIdentifiers, InfoClickListener infoClickListener, CheckBoxListener checkboxListener) {
        if (dataset == null) { this.dataset = new ArrayList<>(); }
        else { this.dataset = dataset; }

        // Construct an array of connected statuses matching the dataset
        connectedDataset = new boolean[this.dataset.size()];
        if (connectedIdentifiers != null && connectedIdentifiers.size() > 0) {
            for (int i = 0; i < this.dataset.size(); ++i) {
                connectedDataset[i] = connectedIdentifiers.contains(this.dataset.get(i).getDeviceName());
            }
        } // End if(connectedIdentifiers.size > 0)

        this.infoClickListener = infoClickListener;
        this.checkboxListener = checkboxListener;
    } // End DeviceAdapter(List<String>) constructor

    /**
     * Method triggered when a {@link androidx.recyclerview.widget.RecyclerView.ViewHolder} is created.
     */
    @Override
    public DeviceAdapter.Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        ConstraintLayout v = (ConstraintLayout) LayoutInflater.from(parent.getContext()).inflate(
                R.layout.device_entry_layout,
                parent,
                false
        );
        return new DeviceAdapter.Holder(v);
    } // End onCreateViewHolder method

    /**
     * Method triggered when a {@link com.moppyandroid.main.DeviceAdapter.Holder} is bound to the {@link RecyclerView}.
     */
    @Override
    public void onBindViewHolder(DeviceAdapter.Holder holder, int position) {
        holder.deviceNameView.setText(dataset.get(position).getDeviceName()); // Device path, e.g. /dev/bus/usb/001/004
        holder.deviceNameView.setSelected(true); // Enable marquee
        holder.checkBox.setChecked(connectedDataset[position]);
    } // End onBindViewHolder method

    /**
     * Gets the size of the data set.
     *
     * @return the number of items
     */
    @Override
    public int getItemCount() { return dataset.size(); }

    /**
     * Represents an entry in a {@link com.moppyandroid.main.DeviceAdapter}.
     */
    public class Holder extends RecyclerView.ViewHolder implements View.OnClickListener, CompoundButton.OnCheckedChangeListener {
        /**
         * The {@link CheckBox} displayed at the left of the entry
         */
        public CheckBox checkBox;
        /**
         * The {@link TextView} for the device's name
         */
        public TextView deviceNameView;

        /**
         * Constructs a {@code DeviceAdapter.Holder} using an inflated {@code device_entry_layout}.
         *
         * @param v the {@code device_entry_layout}
         */
        public Holder(ConstraintLayout v) {
            super(v);
            checkBox = v.findViewById(R.id.entry_device_checkbox);
            deviceNameView = v.findViewById(R.id.entry_device_text);
            v.findViewById(R.id.entry_name_and_icon_layout).setOnClickListener(this);
            checkBox.setOnCheckedChangeListener(this);
        } // End DeviceAdapter.Holder(ConstraintLayout) constructor

        /**
         * Method triggered when a {@code DeviceAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) {
            if (infoClickListener != null) {
                infoClickListener.onClick(dataset.get(getAdapterPosition()));
            }
        } // End onClick method

        @Override
        public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
            if (checkboxListener != null) {
                checkboxListener.onCheckChanged(dataset.get(getAdapterPosition()), (CheckBox) buttonView, isChecked);
            }
        } // End onCheckedChanged method
    } // End DeviceAdapter.Holder class

    /**
     * Used to notify clients of info button click events
     */
    interface InfoClickListener {
        /**
         * Triggered when {@code entry_name_and_icon_layout} is clicked.
         *
         * @param device the {@link UsbDevice} for the clicked entry
         */
        void onClick(UsbDevice device);
    } // End DeviceAdapter.InfoClickListener interface

    /**
     * Used to notify clients of checking events
     */
    interface CheckBoxListener {
        /**
         * Triggered when {@code entry_device_checkbox} has its state changed.
         */
        void onCheckChanged(UsbDevice device, CheckBox checkBox, boolean isChecked);
    } // End DeviceAdapter.CheckBoxListener interface
} // End DeviceAdapter class
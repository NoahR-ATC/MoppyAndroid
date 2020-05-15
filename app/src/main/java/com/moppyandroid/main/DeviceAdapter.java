package com.moppyandroid.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link List}{@code <}{@link String}{@code >} adapter for a {@link RecyclerView}
 * that displays a {@link CheckBox}, one of the provided {@code String}s, and an info icon as a list entry.
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {
    private List<String> dataset;
    private ClickListener clickListener;

    /**
     * Constructs a {@code DeviceAdapter} with a {@link List} of names to display.
     *
     * @param dataset the {@link List} to show
     */
    public DeviceAdapter(List<String> dataset, ClickListener clickListener) {
        if (dataset == null) { this.dataset = new ArrayList<>(); }
        else { this.dataset = dataset; }
        this.clickListener = clickListener;
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
        holder.deviceNameView.setText(dataset.get(position));
        holder.deviceNameView.setSelected(true); // Enable marquee
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
    public class Holder extends RecyclerView.ViewHolder implements View.OnClickListener {
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
            v.setOnClickListener(this);
        } // End DeviceAdapter.Holder(ConstraintLayout) constructor

        /**
         * Method triggered when a {@code DeviceAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) {
            if (clickListener != null) { clickListener.onClick(); }
        } // End onClick method
    } // End DeviceAdapter.Holder class

    /**
     * Used to notify clients of click events
     */
    interface ClickListener {
        /**
         * Triggered when a {@code device_entry_layout} is clicked.
         */
        void onClick();
    } // End BrowserAdapter.ClickListener interface
} // End BrowserAdapter class
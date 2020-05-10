package com.moppyandroid.main;

import android.support.v4.media.MediaBrowserCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link List}{{@code @literal <}}{@link android.support.v4.media.MediaBrowserCompat.MediaItem}{{@code @literal >}}
 * adapter for a {@link RecyclerView} that displays each item's icon, title, and subtitle as a list entry.
 */
public class BrowserAdapter extends RecyclerView.Adapter<BrowserAdapter.Holder> {
    private List<MediaBrowserCompat.MediaItem> dataset;
    private ClickListener clickListener;

    /**
     * Constructs a {@code BrowserAdapter} with a {@link List} of
     * {@link android.support.v4.media.MediaBrowserCompat.MediaItem}s to display.
     *
     * @param dataset the {@link List} to show
     */
    public BrowserAdapter(List<MediaBrowserCompat.MediaItem> dataset, ClickListener clickListener) {
        if (dataset == null) { this.dataset = new ArrayList<>(); }
        else { this.dataset = dataset; }
        this.clickListener = clickListener;
    } // End BrowserAdapter(List<MediaItem>) constructor

    /**
     * Method triggered when a {@link androidx.recyclerview.widget.RecyclerView.ViewHolder} is created.
     */
    @Override
    public BrowserAdapter.Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout v = (LinearLayout) LayoutInflater.from(parent.getContext()).inflate(
                R.layout.browser_entry_layout,
                parent,
                false
        );
        return new Holder(v);
    } // End onCreateViewHolder method

    /**
     * Method triggered when a {@link Holder} is bound to the {@link RecyclerView}.
     */
    @Override
    public void onBindViewHolder(Holder holder, int position) {
        holder.iconView.setImageURI(dataset.get(position).getDescription().getIconUri());
        holder.nameView.setText(dataset.get(position).getDescription().getTitle());
        holder.nameView.setSelected(true); // Enable marquee
        holder.lengthView.setText(dataset.get(position).getDescription().getSubtitle());
        holder.lengthView.setSelected(true); // Enable marquee
    } // End onBindViewHolder method

    /**
     * Gets the size of the data set.
     *
     * @return the number of items
     */
    @Override
    public int getItemCount() { return dataset.size(); }

    /**
     * Represents an entry in a {@link BrowserAdapter}.
     */
    public class Holder extends RecyclerView.ViewHolder implements View.OnClickListener {
        /**
         * The 48dp x 48dp {@link ImageView} displayed at the left of an entry
         */
        public ImageView iconView;
        /**
         * The {@link TextView} for the large upper text of an entry used for displaying the name
         */
        public TextView nameView;
        /**
         * The {@link TextView} for the small lower text of an entry used for displaying the song duration
         */
        public TextView lengthView;

        /**
         * Constructs a {@code BrowserAdapter.Holder} using an inflated {@code browser_entry_layout}.
         *
         * @param v the {@code browser_entry_layout}
         */
        public Holder(LinearLayout v) {
            super(v);
            iconView = v.findViewById(R.id.entry_icon_view);
            nameView = v.findViewById(R.id.entry_name_text);
            lengthView = v.findViewById(R.id.entry_length_text);
            v.setOnClickListener(this);
        } // End BrowserAdapter.Holder(LinearLayout) constructor

        /**
         * Method triggered when a {@code BrowserAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) {
            if (clickListener != null) { clickListener.onClick(dataset.get(getAdapterPosition())); }
        } // End onClick method
    } // End BrowserAdapter.Holder class

    /**
     * Used to notify clients of click events
     */
    interface ClickListener {
        /**
         * Triggered when a {@code browser_entry_layout} is clicked.
         *
         * @param item the item that was clicked
         */
        void onClick(MediaBrowserCompat.MediaItem item);
    } // End BrowserAdapter.ClickListener interface
} // End BrowserAdapter class

package com.moppyandroid.main;

import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
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
 * {@link List}{@code <}{@link android.support.v4.media.session.MediaSessionCompat.QueueItem}{@code >} adapter for a
 * {@link RecyclerView} that each item's title, duration, and a play icon if it is the playing item.
 */
public class QueueAdapter extends RecyclerView.Adapter<QueueAdapter.Holder> {
    private List<MediaSessionCompat.QueueItem> dataset;
    private String currentMediaId;
    private ClickListener clickListener;

    /**
     * Constructs a {@code QueueAdapter} with a {@link List} of
     * {@link android.support.v4.media.session.MediaSessionCompat.QueueItem}s to display.
     *
     * @param dataset the {@link List} to show
     */
    public QueueAdapter(List<MediaSessionCompat.QueueItem> dataset, String currentMediaId, QueueAdapter.ClickListener clickListener) {
        if (dataset == null) { this.dataset = new ArrayList<>(); }
        else { this.dataset = dataset; }
        this.currentMediaId = currentMediaId;
        this.clickListener = clickListener;
    } // End QueueAdapter(List<QueueItem>) constructor

    /**
     * Method triggered when a {@link androidx.recyclerview.widget.RecyclerView.ViewHolder} is created.
     */
    @Override
    public QueueAdapter.Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout v = (LinearLayout) LayoutInflater.from(parent.getContext()).inflate(
                R.layout.queue_entry_layout,
                parent,
                false
        );
        return new QueueAdapter.Holder(v);
    } // End onCreateViewHolder method

    /**
     * Method triggered when a {@link QueueAdapter.Holder} is bound to the {@link RecyclerView}.
     */
    @Override
    public void onBindViewHolder(QueueAdapter.Holder holder, int position) {
        // Set the icon to play arrow if this is the currently-playing song
        MediaDescriptionCompat description = dataset.get(position).getDescription();
        if (description != null) {
            String mediaId = description.getMediaId();
            if (mediaId != null && mediaId.equals(currentMediaId)) { // This is the playing song
                holder.iconView.setImageResource(R.drawable.ic_play);
                holder.layout.setBackgroundResource(R.color.backgroundDark);
            }
            else { holder.iconView.setImageResource(R.drawable.ic_musicfile); }
        }
        else { holder.iconView.setImageResource(R.drawable.ic_musicfile); }

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
     * Represents an entry in a {@link QueueAdapter}.
     */
    public class Holder extends RecyclerView.ViewHolder implements View.OnClickListener {
        /**
         * The {@link LinearLayout} containing an entry
         */
        public LinearLayout layout;
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
         * Constructs a {@code QueueAdapter.Holder} using an inflated {@code queue_entry_layout}.
         *
         * @param v the {@code queue_entry_layout}
         */
        public Holder(LinearLayout v) {
            super(v);
            layout = v;
            iconView = v.findViewById(R.id.entry_icon_view);
            nameView = v.findViewById(R.id.entry_name_text);
            lengthView = v.findViewById(R.id.entry_length_text);
            v.setOnClickListener(this);
        } // End QueueAdapter.Holder(LinearLayout) constructor

        /**
         * Method triggered when a {@code QueueAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) {
            if (clickListener != null) { clickListener.onClick(dataset.get(getAdapterPosition())); }
        } // End onClick method
    }

    /**
     * Used to notify clients of click events
     */
    interface ClickListener {
        /**
         * Triggered when a {@code queue_entry_layout} is clicked.
         *
         * @param item the item that was clicked
         */
        void onClick(MediaSessionCompat.QueueItem item);
    } // End QueueAdapter.ClickListener interface
} // End QueueAdapter class

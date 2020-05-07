package com.moppyandroid.main;

import android.app.AlertDialog;
import android.support.v4.media.MediaBrowserCompat;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

/**
 * {@link List}{{@code @literal <}}{@link android.support.v4.media.MediaBrowserCompat.MediaItem}{{@code @literal >}}
 * adapter for a {@link RecyclerView} that displays each item's title and icon.
 */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.Holder> {
    private List<MediaBrowserCompat.MediaItem> dataset;

    /**
     * Constructs a {@code LibraryAdapter} with a {@link List} of
     * {@link android.support.v4.media.MediaBrowserCompat.MediaItem}s to display.
     *
     * @param dataset the {@link List} to show
     */
    public LibraryAdapter(List<MediaBrowserCompat.MediaItem> dataset) {
        this.dataset = dataset;
    } // End LibraryHolder(List<MediaItem>) constructor

    /**
     * Method triggered when a {@link androidx.recyclerview.widget.RecyclerView.ViewHolder} is created.
     */
    @Override
    public LibraryAdapter.Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout v = (LinearLayout) LayoutInflater.from(parent.getContext()).inflate(
                R.layout.library_cell_layout,
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
        holder.imageView.setImageURI(dataset.get(position).getDescription().getIconUri());
        holder.textView.setText(dataset.get(position).getDescription().getTitle());
    } // End onBindViewHolder method

    /**
     * Gets the size of the data set.
     *
     * @return the number of items
     */
    @Override
    public int getItemCount() { return dataset.size(); }

    /**
     * Represents an entry in a {@link LibraryAdapter}.
     */
    public class Holder extends RecyclerView.ViewHolder implements View.OnClickListener {
        /**
         * The 128dpx128dp {@link ImageView} displayed
         */
        public ImageView imageView;
        /**
         * The {@link TextView} displayed beneath the {@link ImageView}.
         */
        public TextView textView;

        /**
         * Constructs a {@code LibraryAdapter.Holder} using an inflated {@code library_cell_layout}.
         *
         * @param v the {@code library_cell_layout}
         */
        public Holder(LinearLayout v) {
            super(v);
            imageView = v.findViewById(R.id.cell_image_view);
            textView = v.findViewById(R.id.cell_text_view);
            v.setOnClickListener(this);
        } // End LibraryAdapter.Holder(LinearLayout) constructor

        /**
         * Method triggered when a {@code LibraryAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) { // TODO: Start activity for next level media ID result
            AlertDialog.Builder builder = new AlertDialog.Builder(v.getContext());
            builder.setTitle("MoppyAndroid");
            builder.setCancelable(false);
            builder.setMessage(dataset.get(getAdapterPosition()).getMediaId());
            builder.setPositiveButton("OK", null);
            builder.create().show();
        } // End onClick method
    } // End LibraryAdapter.Holder class
} // End LibraryAdapter class

package com.moppyandroid.main;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * {@link List}{{@code @literal <}}{@link String}{{@code @literal >}} adapter for a {@link RecyclerView}
 * that displays an arrow and each item's title.
 */
public class PathAdapter extends RecyclerView.Adapter<PathAdapter.Holder> {
    private List<String> dataset;
    private ClickListener clickListener;

    /**
     * Constructs a {@code PathAdapter} with a {@link List}{@code {@literal <}}{@link String}{@code {@literal >}}
     * of the path segments to display.
     *
     * @param pathSegments the {@link List} of path segments to show
     */
    public PathAdapter(List<String> pathSegments, ClickListener clickListener) {
        this.clickListener = clickListener;

        // If a null list is supplied we create an empty adapter, but if a list is supplied that doesn't
        // have any items than this is a programming error and an IllegalArgumentException is raised
        if (pathSegments == null) {
            this.dataset = new ArrayList<>();
        }
        else if (pathSegments.size() < 1) {
            throw new IllegalArgumentException("Empty path supplied");
        }
        else { this.dataset = pathSegments; }
    } // End PathAdapter(List<MediaItem>) constructor

    /**
     * Method triggered when a {@link androidx.recyclerview.widget.RecyclerView.ViewHolder} is created.
     */
    @Override
    public PathAdapter.Holder onCreateViewHolder(ViewGroup parent, int viewType) {
        LinearLayout v = (LinearLayout) LayoutInflater.from(parent.getContext()).inflate(
                R.layout.path_cell_layout,
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
        holder.pathText.setText(dataset.get(position));
    } // End onBindViewHolder method

    /**
     * Gets the size of the data set.
     *
     * @return the number of items
     */
    @Override
    public int getItemCount() { return dataset.size(); }

    /**
     * Represents an entry in a {@link PathAdapter}.
     */
    public class Holder extends RecyclerView.ViewHolder implements View.OnClickListener {
        /**
         * The {@link TextView} displayed to the right of the arrow.
         */
        public TextView pathText;

        /**
         * Constructs a {@code PathAdapter.Holder} using an inflated {@code path_cell_layout}.
         *
         * @param v the {@code path_cell_layout}
         */
        public Holder(LinearLayout v) {
            super(v);
            pathText = v.findViewById(R.id.path_cell_text);
            v.setOnClickListener(this);
        } // End PathAdapter.Holder(LinearLayout) constructor

        /**
         * Method triggered when a {@code PathAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) {
            if (clickListener != null) {
                // Checks if dataset[position + 1] is valid and returns if not, since the last cell
                // is the current segment and there's no sense in reloading the same items
                if (getAdapterPosition() + 1 >= getItemCount()) { return; }
                clickListener.onClick(dataset.subList(0, getAdapterPosition() + 1));
            }
        } // End onClick method
    } // End PathAdapter.Holder class

    /**
     * Used to notify clients of click events
     */
    interface ClickListener {
        /**
         * Triggered when a {@code path_cell_layout} is clicked.
         *
         * @param segments the segments leading up to and including the clicked segment
         */
        void onClick(List<String> segments);
    } // End PathAdapter.ClickListener interface
} // End PathAdapter class

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
 * {@link List}{@code <}{@link android.support.v4.media.MediaBrowserCompat.MediaItem}{@code >}
 * adapter for a {@link RecyclerView} that displays each item's title and icon as a grid card.
 */
public class LibraryAdapter extends RecyclerView.Adapter<LibraryAdapter.Holder> {
    private List<MediaBrowserCompat.MediaItem> dataset;
    private ClickListener clickListener;

    /**
     * Constructs a {@code LibraryAdapter} with a {@link List} of
     * {@link android.support.v4.media.MediaBrowserCompat.MediaItem}s to display.
     *
     * @param dataset the {@link List} to show
     */
    public LibraryAdapter(List<MediaBrowserCompat.MediaItem> dataset, ClickListener clickListener) {
        if (dataset == null) { this.dataset = new ArrayList<>(); }
        else { this.dataset = dataset; }
        this.clickListener = clickListener;
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
        holder.textView.setSelected(true); // Enable marquee
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
            imageView = v.findViewById(R.id.library_cell_icon_view);
            textView = v.findViewById(R.id.library_cell_title);
            v.setOnClickListener(this);
        } // End LibraryAdapter.Holder(LinearLayout) constructor

        /**
         * Method triggered when a {@code LibraryAdapter.Holder} is clicked.
         */
        @Override
        public void onClick(View v) {
            if (clickListener != null) { clickListener.onClick(dataset.get(getAdapterPosition())); }
        } // End onClick method
    } // End LibraryAdapter.Holder class

    /**
     * Used to notify clients of click events
     */
    interface ClickListener {
        /**
         * Triggered when a {@code library_cell_layout} is clicked.
         *
         * @param item the item that was clicked
         */
        void onClick(MediaBrowserCompat.MediaItem item);
    } // End LibraryAdapter.ClickListener interface
} // End LibraryAdapter class

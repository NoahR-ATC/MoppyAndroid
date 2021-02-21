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

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.util.Log;
import android.view.MenuItem;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NavUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.moppyandroid.main.service.MoppyMediaService;

import java.util.Arrays;
import java.util.List;

/**
 * Activity for browsing a {@link MoppyMediaService}'s media tree.
 */
public class BrowserActivity extends AppCompatActivity {
    /**
     * {@link String} extra for the initial media ID to show.
     */
    public static final String EXTRA_INITIAL_ID = "BROWSER_INITIAL_MEDIA_ID";
    /**
     * {@link android.support.v4.media.MediaBrowserCompat.MediaItem} result extra for the file the user selected
     */
    public static final String EXTRA_SELECTED_FILE = "BROWSER_SELECTED_FILE";
    /**
     * {@link String} extra field for the reason an error was raised.
     */
    public static final String EXTRA_ERROR_REASON = "BROWSER_ERROR_REASON";

    private boolean initialized = false;
    private String mediaRoot;
    private List<String> currentPathSegments;
    private MediaBrowserCompat mediaBrowser;
    private RecyclerView fileRecycler;
    private RecyclerView pathRecycler;

    /**
     * Triggered when the {@link AppCompatActivity} is created
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getIntent().getStringExtra(EXTRA_INITIAL_ID) == null) {
            closeWithError("EXTRA_INITIAL_ID not supplied");
        }

        // Initialize variables and set content view
        currentPathSegments = null;
        setContentView(R.layout.activity_browser);

        // Create the toolbar
        Toolbar toolbar = findViewById(R.id.browser_toolbar);
        TextView titleView = toolbar.findViewById(R.id.browser_toolbar_title);
        setSupportActionBar(toolbar);
        titleView.setText(toolbar.getTitle());
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false); // Since we're using a custom title view, disable the theme's

        // Create the recyclers
        fileRecycler = findViewById(R.id.file_recycler);
        pathRecycler = findViewById(R.id.path_recycler);
        fileRecycler.setAdapter(new BrowserAdapter(null, null));
        fileRecycler.setLayoutManager(new LinearLayoutManager(this));
        pathRecycler.setAdapter(new PathAdapter(null, null));
        pathRecycler.setLayoutManager(new LinearLayoutManager(BrowserActivity.this, LinearLayoutManager.HORIZONTAL, false));

        // Connect to the media service for media tree loading
        mediaBrowser = new MediaBrowserCompat(
                this,
                new ComponentName(this, MoppyMediaService.class),
                new BrowserConnectionCallback(),
                null
        );
        mediaBrowser.connect();
    } // End onCreate method

    /**
     * Triggered when the activity is destroyed.
     */
    @Override
    protected void onDestroy() {
        if (mediaBrowser != null) { mediaBrowser.disconnect(); }
        super.onDestroy();
    }

    /**
     * Triggered when the back button/gesture is pressed/occurs
     */
    @Override
    public void onBackPressed() {
        if (currentPathSegments == null || currentPathSegments.size() < 2) {
            super.onBackPressed();
        }
        else {
            backupToId(currentPathSegments.subList(0, currentPathSegments.size() - 1));
        }
    } // End onBackPressed method

    /**
     * Triggered when an item in the action bar is pressed
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) { // https://stackoverflow.com/a/13471504
        // Respond to the action bar's Up/Home button
        if (item.getItemId() == android.R.id.home) {
            NavUtils.navigateUpFromSameTask(this);
            return true;
        }
        return super.onOptionsItemSelected(item);
    } // End onOptionsItemSelected method

    // Finishes the activity with RESULT_CANCELLED and EXTRA_ERROR_REASON
    private void closeWithError(String reason) {
        Intent errorIntent = new Intent();
        errorIntent.putExtra(EXTRA_ERROR_REASON, reason);
        setResult(RESULT_CANCELED, errorIntent);
        finish();
    } // End closeWithError method

    // Initializes variables and loads the initial folder
    private void init() {
        mediaRoot = mediaBrowser.getRoot();
        loadRecyclers(getIntent().getStringExtra(EXTRA_INITIAL_ID));
        initialized = true;
    } // End init method

    // Uses a media ID to load the file recycler and recreate the path recycler
    private void loadRecyclers(String loadId) {
        if (mediaBrowser.isConnected()) { // In case connection suspended we just ignore input
            mediaBrowser.subscribe(loadId, new MediaBrowserCompat.SubscriptionCallback() {
                @Override
                public void onChildrenLoaded(@NonNull String parentId, @NonNull List<MediaBrowserCompat.MediaItem> children) {
                    super.onChildrenLoaded(parentId, children);
                    // Create the file recycler and set it to call loadItem when an item is clicked
                    fileRecycler.setAdapter(new BrowserAdapter(children, (item) -> loadItem(item)));

                    // Create the path recycler and set it to call backupToId when a segment is clicked
                    currentPathSegments = Arrays.asList(parentId.substring(mediaRoot.length() + 1).split("/"));
                    pathRecycler.setAdapter(new PathAdapter(currentPathSegments, (segments) -> backupToId(segments)));

                    mediaBrowser.unsubscribe(parentId);
                } // End subscribe(loadId)->onChildrenLoaded method
            }); // End SubscriptionCallback implementation
        } // End if(isConnected)
    } // End loadRecyclers method

    private void loadItem(MediaBrowserCompat.MediaItem item) {
        if (item.isBrowsable()) { loadRecyclers(item.getMediaId()); }
        else {
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_SELECTED_FILE, item);
            setResult(RESULT_OK, resultIntent);
            finish();
        } // End if(item.isBrowsable) {} else
    } // End loadItem method

    private void backupToId(List<String> idSegments) {
        StringBuilder idBuilder = new StringBuilder();
        idBuilder.append(mediaRoot).append("/");
        for (String s : idSegments) { idBuilder.append(s).append("/"); }
        idBuilder.setLength(idBuilder.length() - 1);
        loadRecyclers(idBuilder.toString());
    } // End backupToId method

    // Receives callbacks about mediaBrowser.connect
    private class BrowserConnectionCallback extends MediaBrowserCompat.ConnectionCallback {
        @Override
        public void onConnected() { // Successful connection
            // Run the initializer
            if (!initialized) { init(); }
            super.onConnected();
        } // End onConnected method

        @Override
        public void onConnectionSuspended() { // Server crashed, awaiting restart
            // Nothing to do here, mediaBrowser is only vulnerably accessed through loadRecyclers and that
            // ignores the input if not connected
            super.onConnectionSuspended();
        } // End onConnectionSuspended method

        @Override
        public void onConnectionFailed() { // Connection refused
            // Log what (shouldn't have) happened and close the activity
            Log.wtf(BrowserActivity.class.getName() + "->onConnectionFailed", "Unable to connect to MoppyMediaService in browser");
            closeWithError("Unable to connect to Media Service");
            super.onConnectionFailed();
        } // End onConnectionFailed method
    } // End BrowserConnectionCallback class
} // End BrowserActivity class

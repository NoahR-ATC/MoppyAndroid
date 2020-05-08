package com.moppyandroid.main;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.NavUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Bundle;
import android.support.v4.media.MediaBrowserCompat;
import android.view.MenuItem;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Activity for browsing a {@link MoppyMediaService}'s media tree.
 */
public class BrowserActivity extends AppCompatActivity {
    /**
     * {@link String} extra for the path the activity is in without the root identifier, e.g. {@code PATH/Music}, {@code ARTIST/{@literal <unknown>}}.
     */
    public static final String EXTRA_PATH_STRING = "EXTRA_BROWSER_PATH_STRING";
    /**
     * {@link ArrayList}{{@code @literal <}}{@link android.support.v4.media.MediaBrowserCompat.MediaItem}{{@code @literal >}}
     * extra for the items to display for browsing.
     */
    public static final String EXTRA_MEDIA_LIST = "EXTRA_BROWSER_MEDIA_ITEMS_LIST";

    /**
     * Triggered when the {@link AppCompatActivity} is created
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_browser);

        // Create the toolbar
        Toolbar toolbar = findViewById(R.id.browser_toolbar);
        TextView titleView = toolbar.findViewById(R.id.browser_toolbar_title);
        setSupportActionBar(toolbar);
        titleView.setText(toolbar.getTitle());
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportActionBar().setDisplayShowTitleEnabled(false); // Since we're using a custom title view, disable the theme's

        // Create the recyclers
        // TODO: Use listener to extract media ID for next folder/file loading
        ArrayList<MediaBrowserCompat.MediaItem> list = getIntent().getParcelableArrayListExtra(EXTRA_MEDIA_LIST);
        RecyclerView fileRecycler = findViewById(R.id.file_recycler);
        fileRecycler.setAdapter(new BrowserAdapter(list, null));
        fileRecycler.setLayoutManager(new LinearLayoutManager(this));

        RecyclerView pathRecycler = findViewById(R.id.path_recycler);
        pathRecycler.setAdapter(new PathAdapter(getIntent().getStringExtra(EXTRA_PATH_STRING), null));
        pathRecycler.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
    } // End onCreate method

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

    /**
     * Triggered when the back button/gesture is pressed/occurs
     */
    @Override
    public void onBackPressed() {
        // TODO: Walk back a folder or finish()
    } // End onBackPressed method
} // End BrowserActivity class

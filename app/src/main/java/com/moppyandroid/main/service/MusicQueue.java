package com.moppyandroid.main.service;

import android.support.v4.media.MediaDescriptionCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Represents a queue of {@link android.support.v4.media.session.MediaSessionCompat.QueueItem}s that
 * manages the current playing item, as well as maintains a window to be used with
 * {@link android.support.v4.media.session.MediaSessionCompat#setQueue(List)}.
 */
public class MusicQueue {
    /**
     * The maximum size of the queue window/subset handed to the {@link MediaSessionCompat}.
     */
    public static final int WINDOW_SIZE = 50;

    private static final long ID_INCREMENT = 10000; // The increment between queue item IDs when appended to the queue

    private final HashMap<Long, MediaSessionCompat.QueueItem> musicQueueFull;   // The map of IDs to QueueItems that contains the entire music queue
    private final List<Long> queueIndexToId;                                    // Used to track index of items in musicQueueFull
    private final List<MediaSessionCompat.QueueItem> window;                    // Subset of musicQueueFull that gets handed to the media session
    private final MediaSessionCompat mediaSession; // The media session that the queue window is posted to

    private int windowOffset = 0;                   // Offset of window[0] in queueIndexToId (and therefore musicQueueFull's items)
    private int windowIndex = 0;                    // Index of the playing item in window
    private long lastAvailableActions = 0;          // Flag collection for the current available queue playback actions
    private Callback actionsCallback;               // Callback triggered when lastAvailableActions changes

    /**
     * Constructs a {@code MusicQueue} without a {@link MusicQueue.Callback}.
     *
     * @param mediaSession the media session the queue is posted to
     * @see #MusicQueue(MediaSessionCompat, Callback)
     */
    public MusicQueue(MediaSessionCompat mediaSession) {
        musicQueueFull = new HashMap<>();
        queueIndexToId = new ArrayList<>();
        window = new ArrayList<>();
        this.mediaSession = mediaSession;
    }

    /**
     * Constructs a {@code MusicQueue} with a {@link MusicQueue.Callback}.
     *
     * @param mediaSession    the media session the queue is posted to
     * @param actionsCallback the callback triggered when the available actions change
     */
    public MusicQueue(MediaSessionCompat mediaSession, Callback actionsCallback) {
        this(mediaSession);
        this.actionsCallback = actionsCallback;
    }

    /**
     * Adds a song to the end of the music queue.
     *
     * @param description the song to add
     */
    public void addToQueue(MediaDescriptionCompat description) {
        int index = (window.size() > 0) ? windowOffset + windowIndex + 1 : 0;
        addToQueue(description, index); // false always returned since we add to the end of the window
    } // End addToQueue(MediaDescriptionCompat) method

    /**
     * Adds a song to the music queue at a specific index.
     *
     * @param description the song to add
     * @param index       the index to insert the song at
     * @return {@code true} if a song reload is suggested due to the song being inserted at the position of the currently-playing song
     * @throws IndexOutOfBoundsException if {@code index < 0} or {@code index > }{@link #getQueueSize()} (one passed current final index)
     */
    public boolean addToQueue(MediaDescriptionCompat description, int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index less than 0, index = " + index);
        }
        if (index > queueIndexToId.size()) {
            throw new IndexOutOfBoundsException("index = " + index + ", size = " + queueIndexToId.size());
        }

        boolean songReloadSuggested = insertInQueue(description, index);
        moveWindow(index);

        // Post action change if necessary
        updateActions();
        return songReloadSuggested;
    } // End addToQueue(MediaDescriptionCompat, int) method

    /**
     * Removes a specific song from the queue.
     *
     * @param description the song to remove
     * @return {@code true} if the currently-playing song was removed and a song reload is necessary
     */
    public boolean removeFromQueue(MediaDescriptionCompat description) {
        // Find the first item with the specified description
        MediaSessionCompat.QueueItem item = musicQueueFull.values().stream().filter((q) -> q.getDescription().equals(description)).findFirst().orElse(null);
        if (item == null) { return false; } // Not found
        int index = queueIndexToId.indexOf(item.getQueueId());

        return removeFromQueue(index);
    } // End removeFromQueue(MediaDescriptionCompat) method

    /**
     * Removes a specific song from the queue by its ID.
     *
     * @param id the ID of the song to remove
     * @return {@code true} if the currently-playing song was removed and a song reload is necessary
     */
    public boolean removeFromQueue(long id) {
        int index = queueIndexToId.indexOf(id);
        if (index < 0) { return false; } // Not found

        return removeFromQueue(index);
    } // End removeFromQueue(long) method

    /**
     * Removes a song at a specific index from the queue.
     *
     * @param index the index of the song to remove
     * @return {@code true} if the currently-playing song was removed and a song reload is necessary
     * @throws IndexOutOfBoundsException if {@code index < 0} or {@code index >= }{@link #getQueueSize()} (index of final song)
     */
    public boolean removeFromQueue(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index less than 0, index = " + index);
        }
        if (index >= queueIndexToId.size()) {
            throw new IndexOutOfBoundsException("index = " + index + ", size = " + queueIndexToId.size());
        }

        boolean songReloadRequired = false;
        synchronized (musicQueueFull) {
            musicQueueFull.remove(queueIndexToId.get(index));
            queueIndexToId.remove(index);

            synchronized (window) {
                boolean windowChanged = true;
                // If the index is passed the window, there is no need to change anything
                if (index >= windowOffset + WINDOW_SIZE) { windowChanged = false;}
                // Decrement the offset if the new item appears before window starts. No need
                // to recreate the window since the window contents are the same
                else if (index < windowOffset) {
                    --windowOffset;
                    windowChanged = false;
                }
                // If the new item is inserted where the playing item is or after, recreate the window
                // for the current position. Also, if the new item is where the playing item is,
                // mark that a reload is required.
                else if (index >= windowOffset + windowIndex) {
                    if (index == windowOffset) { songReloadRequired = true; }
                    moveWindow(windowOffset + windowIndex);
                }
                // If the new item is after the window starts but before currentWindowIndex move the window back one
                else if (index < windowOffset + windowIndex) {
                    // Note: This can't be negative since if index < windowOffset + windowIndex && windowOffset + windowIndex <= 0
                    //      then the previous case index >= windowOffset + windowIndex would of covered it
                    moveWindow(windowOffset + windowIndex - 1);
                } // End if(index passed window) {} else if(index before window) else if(index >= currentIndex) {} else if(index < currentIndex)

                // If the window was recreated update the media session
                if (windowChanged) { mediaSession.setQueue(new ArrayList<>(window)); }
            } // End sync(window)
        } // End sync(musicQueueFull)

        // Post action change if necessary
        updateActions();
        return songReloadRequired;
    } // End removeFromQueue(int) method

    /**
     * Skips to a specific song in the queue.
     *
     * @param id the ID of the song to skip to
     * @return {@code null} if {@code id} doesn't match a song, otherwise the song that was skipped to
     */
    public MediaSessionCompat.QueueItem skipToSong(long id) {
        int index = queueIndexToId.indexOf(id);
        if (index < 0) { return null; } // Note: IDs outside of window are valid

        moveWindow(index);

        // Post action change if necessary
        updateActions();
        return getCurrentSong();
    } // End skipToSong method

    /**
     * Skips to the song at a specific index in the queue.
     *
     * @param index the index of the song to skip to
     * @return {@code null} if {@code index} doesn't exist in the queue, otherwise the song that was skipped to
     */
    public MediaSessionCompat.QueueItem skipToSong(int index) {
        if (checkSongAvailable(index)) {
            moveWindow(index);
            updateActions();
            return getCurrentSong();
        }
        else { return null; }
    } // End skipToSong method

    /**
     * Skips to the next song in the queue.
     *
     * @return {@code null} if there are no songs left in the queue, otherwise the song that was skipped to
     */
    public MediaSessionCompat.QueueItem skipToNext() { return skipToNext(1); }

    /**
     * Skips forward in the queue a number of times. If there aren't enough songs left to skip
     * forward the requested number of times, {@code null} is returned and the queue remains unchanged.
     *
     * @param times the number of times to skip forward
     * @return {@code null} if the queue couldn't be skipped as requested, otherwise the song that was skipped to
     */
    public MediaSessionCompat.QueueItem skipToNext(int times) {
        if (windowOffset + windowIndex + times >= musicQueueFull.size()) { return null; }
        moveWindow(windowOffset + windowIndex + times);

        // Post action change if necessary
        updateActions();
        return getCurrentSong();
    } // End skipToNext method

    /**
     * Skips to the previous song in the queue.
     *
     * @return {@code null} if there are no previous songs left in the queue, otherwise the song that was skipped to
     */
    public MediaSessionCompat.QueueItem skipToPrevious() { return skipToPrevious(1); }

    /**
     * Skips backward in the queue a number of times. If there aren't enough songs left to skip
     * backward the requested number of times, {@code null} is returned and the queue remains unchanged.
     *
     * @param times the number of times to skip backward
     * @return {@code null} if the queue couldn't be skipped as requested, otherwise the song that was skipped to
     */
    public MediaSessionCompat.QueueItem skipToPrevious(int times) {
        // TODO: Restart song/first song if true
        if (times < 1 || windowOffset + windowIndex - times < 0) { return null; }
        moveWindow(windowOffset + windowIndex - times);

        // Post action change if necessary
        updateActions();
        return getCurrentSong();
    } // End skipToPrevious method

    /**
     * Gets the currently-playing song.
     *
     * @return {@code null} if no song is supposed to be playing, otherwise the current song
     */
    public MediaSessionCompat.QueueItem getCurrentSong() { return musicQueueFull.get(queueIndexToId.get(windowOffset + windowIndex)); }

    /**
     * Gets the song associated with a specific ID.
     *
     * @param id the ID of the song to find
     * @return {@code null} if no song is represented by {@code id}, otherwise the requested song
     * @see #skipToSong(long)
     */
    public MediaSessionCompat.QueueItem peekSong(long id) { return musicQueueFull.get(id); }

    /**
     * Gets the song at a specific index.
     *
     * @param index the index of the song to find
     * @return {@code null} if {@code index} doesn't exist in the queue, otherwise the requested song
     * @see #skipToSong(int)
     */
    public MediaSessionCompat.QueueItem peekSong(int index) {
        if (checkSongAvailable(index)) { return musicQueueFull.get(queueIndexToId.get(index)); }
        else { return null; }
    }

    /**
     * Gets the song at a specific index in the current window of the music queue.
     *
     * @param index the index of the song to find
     * @return {@code null} if the index doesn't exist in the window, otherwise the requested song
     * @see #skipToSong(int)
     */
    public MediaSessionCompat.QueueItem peekWindowSong(int index) {
        if (0 <= index && index < window.size()) { return window.get(index); }
        else { return null; }
    }

    /**
     * Gets the next song if available, without modifying the queue.
     *
     * @return {@code null} if there are no songs left in the queue, otherwise the next song
     * @see #skipToNext()
     */
    public MediaSessionCompat.QueueItem peekNextSong() {
        if (nextSongAvailable()) {
            return musicQueueFull.get(queueIndexToId.get(windowOffset + windowIndex + 1));
        }
        else { return null; }
    }

    /**
     * Gets the previous song if available, without modifying the queue.
     *
     * @return {@code null} if there are no previous songs left in the queue, otherwise the previous song
     * @see #skipToPrevious()
     */
    public MediaSessionCompat.QueueItem peekPreviousSong() {
        if (previousSongAvailable()) {
            return musicQueueFull.get(queueIndexToId.get(windowOffset + windowIndex - 1));
        }
        else { return null; }
    }

    /**
     * Checks if a song is in the queue.
     *
     * @param description the song to check for
     * @return {@code true} if {@code description} matches a song in the queue, otherwise {@code false}
     */
    public boolean checkSongAvailable(MediaDescriptionCompat description) {
        return (musicQueueFull.values().stream().filter((q) -> q.getDescription().equals(description)).findFirst().orElse(null) != null);
    }

    /**
     * Checks if an ID corresponds to a song in the queue.
     *
     * @param id the ID to find the associated song with
     * @return {@code true} if {@code id} corresponds to a song in the queue, otherwise {@code false}
     */
    public boolean checkSongAvailable(long id) { return musicQueueFull.containsKey(id); }

    /**
     * Checks if there is a song available at a specific index.
     *
     * @param index the index to check for a song
     * @return {@code true} if {@code index} corresponds to a song in the queue, otherwise {@code false}
     */
    public boolean checkSongAvailable(int index) { return (0 < index && index < queueIndexToId.size()); }

    /**
     * Checks if there is another song forward in the queue that can be played with {@link #skipToNext()}.
     *
     * @return {@code true} if another song is available, otherwise {@code false}
     */
    public boolean nextSongAvailable() { return ((lastAvailableActions & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) > 0); }

    /**
     * Checks if there are any previous songs in the queue that can be played with {@link #skipToPrevious()}.
     *
     * @return {@code true} if another song is available, otherwise {@code false}
     */
    public boolean previousSongAvailable() { return ((lastAvailableActions & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) > 0); }

    /**
     * Gets the currently available {@link PlaybackStateCompat} actions.
     *
     * @return the currently available actions
     */
    public long getActions() {
        lastAvailableActions =
                ((queueIndexToId.size() > 0) ?
                        PlaybackStateCompat.ACTION_SKIP_TO_QUEUE_ITEM : 0
                ) |
                ((queueIndexToId.size() > windowOffset + windowIndex + 1) ?
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT : 0
                ) |
                ((windowOffset + windowIndex > 0) ?
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS : 0
                );
        return lastAvailableActions;
    } // End getActions method

    /**
     * Gets the total size of the current music queue.
     *
     * @return the size of the queue
     */
    public int getQueueSize() { return queueIndexToId.size(); }

    /**
     * Gets a section of the current music queue between {@code fromIndex} (inclusive) and {@code toIndex}
     * (exclusive).
     *
     * @param fromIndex the index of the first element to include, must be greater than {@code 0}
     * @param toIndex   the index of the first element to not include, must be greater than {@code fromIndex}
     *                  and not exceed {@link #getQueueSize()}
     * @return the sublist of the music queue in the range {@code [fromIndex, toIndex)}
     */
    public List<MediaSessionCompat.QueueItem> getQueueSublist(int fromIndex, int toIndex) {
        if (fromIndex < 0) {
            throw new IndexOutOfBoundsException("fromIndex less than 0, fromIndex = " + fromIndex);
        }
        if (toIndex > queueIndexToId.size()) {
            throw new IndexOutOfBoundsException("toIndex = " + toIndex + ", size = " + queueIndexToId.size());
        }
        if (fromIndex >= toIndex) {
            throw new IllegalArgumentException("fromIndex(" + fromIndex + ") > toIndex(" + toIndex + ")");
        }

        List<MediaSessionCompat.QueueItem> result = new ArrayList<>();
        for (int i = fromIndex; i < toIndex; ++i) {
            result.add(musicQueueFull.get(queueIndexToId.get(i)));
        }
        return result;
    } // End getQueueSublist method

    /**
     * Gets the index of the playing song in the full music queue.
     *
     * @return the playing song's full index
     */
    public int getIndex() { return windowOffset + windowIndex; }

    /**
     * Gets the offset of the current window in the full music queue.
     *
     * @return the offset
     */
    public int getWindowOffset() { return windowOffset; }

    /**
     * Gets the index of the playing item in the current window of the music queue.
     *
     * @return the playing item's window index
     * @see #getWindowOffset()
     * @see #getIndex()
     */
    public int getWindowIndex() { return windowIndex; }

    /**
     * Gets the number of songs forward in the queue, equivalent to the number of times {@link #skipToNext()}
     * can be successfully called. Doesn't include the current song.
     *
     * @return the number of songs ahead of the queue head
     */
    public int getNextSongCount() { return queueIndexToId.size() - windowOffset - windowIndex - 1; }

    /**
     * Gets the number of songs backward in the queue, equivalent to the number of times {@link #skipToPrevious()}
     * can be successfully called. Doesn't include the current song.
     *
     * @return the number of songs behind the queue head
     */
    public int getPreviousSongCount() { return windowOffset + windowIndex; }

    // Moves window to center on the provided index, or if not possible, moves the window to make
    // the provided index as close to center as possible.
    // Note: A flowchart and complete example have been included that is very helpful for understanding how this works
    private void moveWindow(int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index less than 0, index = " + index);
        }
        if (index >= queueIndexToId.size()) {
            throw new IndexOutOfBoundsException("index = " + index + ", size = " + queueIndexToId.size());
        }

        // Check if index is able to be the center of a window
        synchronized (window) {
            if (queueIndexToId.size() <= WINDOW_SIZE) {
                // Needed to ensure that the third case isn't inadvertently triggered instead of the first/final
                windowOffset = 0;
                windowIndex = index;
            }
            else if (index > WINDOW_SIZE / 2 && index < queueIndexToId.size() - WINDOW_SIZE / 2) {
                // Put index in center of window
                windowIndex = WINDOW_SIZE / 2;
                windowOffset = index - windowIndex;
            }
            else if (index >= queueIndexToId.size() - WINDOW_SIZE / 2) {
                // lastPossibleWindowOffset + QUEUE_SIZE/2 <= index < queueIndexToId.size
                // Put index on right side of window
                windowOffset = queueIndexToId.size() - WINDOW_SIZE;
                windowIndex = index - windowOffset;
            }
            else { // 0 < index <= QUEUE_SIZE/2
                // Put index on left side of window
                windowOffset = 0;
                windowIndex = index;
            }

            // Rebuild the window now that the new offset and index has been chosen
            window.clear();
            int toIndex = Math.min(windowOffset + WINDOW_SIZE, queueIndexToId.size());
            for (int i = windowOffset; i < toIndex; ++i) {
                window.add(musicQueueFull.get(queueIndexToId.get(i)));
            }
        } // End sync(window)
    } // End moveWindow method

    // Inserts the provided song at the provided index in the music queue, returning true if the song
    // is inserted at the index of the currently playing song and therefore a reload is suggested. If
    // index exceeds the current size of the queue, it is inserted at the end regardless of index value
    private boolean insertInQueue(MediaDescriptionCompat description, int index) {
        if (index < 0) {
            throw new IndexOutOfBoundsException("index less than 0, index = " + index);
        }

        boolean songReloadSuggested = false;

        long id;
        if (queueIndexToId.size() > index) {
            long currentIndexId = queueIndexToId.get(index);
            long previousIndexId = (index > 0) ? queueIndexToId.get(index - 1) : 0;

            // Calculate an ID halfway between the current ID at the requested index and the ID at
            // the previous index, truncating decimal if odd
            id = (currentIndexId - previousIndexId) / 2 + previousIndexId;

            // Short circuits so id isn't incremented if first clause false, second clause triggered
            // if the situation cannot be resolved by adding 1 to id
            if (id == previousIndexId && ++id == currentIndexId) {
                // Too many items have been inserted and IDs are colliding, relabeling needed
                synchronized (musicQueueFull) {
                    Map<Long, MediaSessionCompat.QueueItem> musicQueueClone = new HashMap<>(musicQueueFull);
                    musicQueueFull.clear();

                    // Calculate and reassign IDs
                    for (int i = 0; i < queueIndexToId.size(); ++i) {
                        musicQueueFull.put((i + 1) * ID_INCREMENT, musicQueueClone.get(queueIndexToId.get(i)));
                        queueIndexToId.set(i, (i + 1) * ID_INCREMENT);
                    } // End for(i < queueIndexToId.size)

                    // Update window
                    synchronized (window) {
                        window.clear();
                        for (int i = windowOffset; i < windowOffset + WINDOW_SIZE; ++i) {
                            window.add(musicQueueFull.get(queueIndexToId.get(i)));
                        }
                    } // End sync(window)
                } // End sync(musicQueueFull)
                mediaSession.setQueue(new ArrayList<>(window));
            } // End if(id == previousIndexId && ++id == currentIndexId)
        } // End if(queueIndexToId.size > index)
        else {
            index = queueIndexToId.size();
            if (queueIndexToId.size() > 0) {
                // Calculate the next multiple of ID_INCREMENT
                long lastId = queueIndexToId.get(queueIndexToId.size() - 1);
                long r = lastId % ID_INCREMENT;
                id = lastId + (ID_INCREMENT - r);
            } // End if(queueIndexToId.size > 0)
            else { id = ID_INCREMENT; }
        } // End if(queueIndexToId.size > index) {} else

        synchronized (musicQueueFull) {
            musicQueueFull.put(id, new MediaSessionCompat.QueueItem(description, id));
            queueIndexToId.add(index, id);

            synchronized (window) {
                boolean windowChanged = true;
                // If the index is passed the window, there is no need to change anything
                if (index >= windowOffset + WINDOW_SIZE) { windowChanged = false;}
                // Increment the offset if the new item appears before window starts. No need
                // to recreate the window since the window contents are the same
                else if (index < windowOffset) {
                    ++windowOffset;
                    windowChanged = false;
                }
                // If the new item is inserted where the playing item is or after, recreate the window
                // for the current position. Also, if the new item is where the playing item is,
                // mark that a reload is suggested.
                else if (index >= windowOffset + windowIndex) {
                    if (index == windowOffset) { songReloadSuggested = true; }
                    moveWindow(windowOffset + windowIndex);
                }
                // If the new item is after the window starts but before currentWindowIndex move the window forward one
                else if (index < windowOffset + windowIndex) {
                    moveWindow(windowOffset + windowIndex + 1);
                } // End if(index passed window) {} else if(index before window) else if(index >= currentIndex) {} else if(index < currentIndex)

                // If the window was recreated update the media session
                if (windowChanged) { mediaSession.setQueue(new ArrayList<>(window)); }
            } // End sync(window)
        } // End sync(musicQueueFull)

        // Post action change if necessary
        updateActions();
        return songReloadSuggested;
    } // End insertInQueue method

    private void updateActions() {
        if (actionsCallback != null) {
            // Update lastAvailableActions
            long previousActions = lastAvailableActions;
            getActions();

            if (previousActions != lastAvailableActions) {
                actionsCallback.onAvailableActionsChanged(
                        true,
                        (lastAvailableActions & PlaybackStateCompat.ACTION_SKIP_TO_NEXT) > 0,
                        (lastAvailableActions & PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS) > 0
                );
            }
        } // End if(actionsCallback != null)
    } // End updateActions method

    /**
     * Callback triggered when the available queue manipulation options change.
     */
    public interface Callback {
        void onAvailableActionsChanged(boolean skipToItem, boolean skipToNext, boolean skipToPrevious);
    }
} // End MusicQueue class

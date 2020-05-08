package com.moppyandroid.main;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.support.v4.media.MediaMetadataCompat;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.nio.file.InvalidPathException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;

/**
 * Represents the MIDI file library of an Android device.
 */
public class MidiLibrary implements Map<String, MidiLibrary.MapNode> {
    /**
     * The package and name of the {@code MidiLibrary} class. Refactors <b>must</b> ensure this is accurate.
     */
    protected static final String CLASS_NAME = "com.moppyandroid.main.MidiLibrary";
    /**
     * The ID of the root folder.
     */
    public static final String ROOT_ID = CLASS_NAME + ".ROOT";
    /**
     * The ID of the folder containing the songs sorted by path.
     */
    public static final String PATH_ID = ROOT_ID + "/PATH";
    /**
     * The ID of the folder containing the songs sorted by album.
     */
    public static final String ALBUM_ID = ROOT_ID + "/ALBUM";
    /**
     * The ID of the folder containing the songs sorted by artist.
     */
    public static final String ARTIST_ID = ROOT_ID + "/ARTIST";
    /**
     * URI for the default folder icon.
     */
    public static final String FOLDER_ICON_URI = ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + BuildConfig.APPLICATION_ID + "/drawable/ic_folder";
    /**
     * URI for the album icon.
     */
    public static final String ALBUM_ICON_URI = ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + BuildConfig.APPLICATION_ID + "/drawable/ic_album";
    /**
     * URI for the artist icon.
     */
    public static final String ARTIST_ICON_URI = ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + BuildConfig.APPLICATION_ID + "/drawable/ic_artist";
    /**
     * URI for the music file icon.
     */
    public static final String MUSIC_FILE_ICON_URI = ContentResolver.SCHEME_ANDROID_RESOURCE + "://" + BuildConfig.APPLICATION_ID + "/drawable/ic_musicfile";

    private Folder rootFolder; // The root folder containing all categories

    // IDE Complains about minimum SDK version of 29 needed for MediaStore.Audio.Media.Duration, however
    // the documentation is incorrect and it does exist on earlier SDKs
    @SuppressLint("InlinedApi")
    private static String[] projection = new String[]{ // The fields to retrieve for each file
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
    }; // End projection initialization

    /**
     * Checks if the provided context has permission to read external storage.
     *
     * @param context the context to check
     * @return {@code true} if permission has been granted, otherwise {@code false}
     */
    public static boolean hasStoragePermission(Context context) {
        return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE)
               ==
               PackageManager.PERMISSION_GRANTED;
    } // End hasStoragePermission method

    /**
     * Requests permission to read external storage for the provided activity.
     *
     * @param activity the activity to request permission on
     */
    public static void requestStoragePermission(Activity activity, int requestCode) {
        ActivityCompat.requestPermissions(
                activity,
                new String[]{ Manifest.permission.READ_EXTERNAL_STORAGE },
                requestCode
        ); // End requestPermissions call
    } // End requestStoragePermission method

    /**
     * Factory method for creating a MIDI library.
     *
     * @param context the context to use for retrieving files
     * @return {@code null} if read permission has not been granted, otherwise a valid MIDI library
     */
    @Nullable
    public static MidiLibrary getMidiLibrary(Context context) {
        if (!hasStoragePermission(context)) { return null; }

        // Create the root folder and the category folders
        // Since we access the "root" folder by a path represented by ROOT_ID, it can't actually be the
        //      root folder. Therefore, we create realRootFolder to contain ROOT_ID as its only child.
        RootFolder realRootFolder = new RootFolder();
        Folder rootFolder = realRootFolder.createFolder(ROOT_ID);
        Folder pathFolder = rootFolder.createFolder("PATH");
        Folder artistFolder = rootFolder.createFolder("ARTIST", ARTIST_ICON_URI);
        Folder albumFolder = rootFolder.createFolder("ALBUM", ALBUM_ICON_URI);

        // Open up a query for all MIDI files in the MediaStore.Audio table, closing it automatically
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        try (
                Cursor cursor = resolver.query(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        projection,
                        "is_music != 0 AND " + MediaStore.Audio.Media.MIME_TYPE + "=?",
                        new String[]{ "audio/midi" },
                        MediaStore.Audio.Media.DISPLAY_NAME + " ASC"
                )) {
            if (cursor != null) {
                // Get the column offsets for each field
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int pathColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.RELATIVE_PATH);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);

                // Loop through all the files selected
                while (cursor.moveToNext()) {
                    // Get the fields of the current file
                    long id = cursor.getLong(idColumn);
                    String name = cursor.getString(nameColumn);
                    int duration = cursor.getInt(durationColumn);
                    String path = cursor.getString(pathColumn);
                    String artist = cursor.getString(artistColumn);
                    String album = cursor.getString(albumColumn);
                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

                    // Use the retrieved information to create a MidiFile in each folder category
                    // Note: If an InvalidPathException is raised (likely because a file with that name already exists)
                    //      then the file is re-added to that category with it's path appended to its name
                    // Path category
                    try {
                        pathFolder.createFolder(path).createFile(contentUri, name, duration, artist, album);
                    }
                    catch (InvalidPathException e) {
                        String newName = name + " (" + path + ")";
                        pathFolder.createFolder(path).createFile(contentUri, newName, duration, artist, album);
                    }
                    // Artist category
                    try {
                        artistFolder.createFolder(artist, ARTIST_ICON_URI).createFile(contentUri, name, duration, artist, album);
                    }
                    catch (InvalidPathException e) {
                        String newName = name + " (" + path + ")";
                        artistFolder.createFolder(artist, ARTIST_ICON_URI).createFile(contentUri, newName, duration, artist, album);
                    }
                    // Album category
                    try {
                        albumFolder.createFolder(album, ALBUM_ICON_URI).createFile(contentUri, name, duration, artist, album);
                    }
                    catch (InvalidPathException e) {
                        String newName = name + " (" + path + ")";
                        albumFolder.createFolder(album, ALBUM_ICON_URI).createFile(contentUri, newName, duration, artist, album);
                    }
                } // End while(cursor.next)
            } // End if(cursor != null)
        } // End try(cursor = query(EXTERNAL_CONTENT_URI)

        // Create and return the MidiLibrary object
        return new MidiLibrary(realRootFolder);
    }

    public static void getMidiLibraryAsync(Context context, MidiLibrary.Callback callback) {
        new Thread() {
            @Override
            public void run() { callback.onLoadCompletion(getMidiLibrary(context)); }
        }.start();
    } // End getMIDILibraryAsync method

    // Constructs a MIDI library with the passed root folder. See getMidiLibrary for explanation about root folder to pass
    private MidiLibrary(Folder rootFolder) { this.rootFolder = rootFolder; }

    /**
     * Gets the number of items in the root of this MIDI library.
     *
     * @return the number of children in the root folder
     */
    @Override
    public int size() { return rootFolder.getChildrenCount(); }

    /**
     * Checks if the root of this MIDI library is empty.
     *
     * @return {@code true} if the library is empty, otherwise {@code false}
     */
    @Override
    public boolean isEmpty() { return (size() == 0); }

    /**
     * Checks if the passed {@link String} is one of the names of the items in the root of this MIDI library.
     *
     * @param key the name to check for
     * @return {@code true} if {@code key} is the name of a root item, {@code false} otherwise
     */
    @Override
    public boolean containsKey(Object key) {
        if (!(key instanceof String)) {
            throw new ClassCastException(
                    "key is of type " +
                    (key == null ? "null" : key.getClass().getName()) +
                    " not " + String.class.getName()
            );
        } // End if(key ∉ String)
        return rootFolder.getKeys().contains(key);
    } // End containsKey method

    /**
     * Checks if the passed {@link MapNode} is one of the items in the root of this MIDI library.
     *
     * @param value the {@code MapNode} object to check for
     * @return {@code true} if {@code value} is a root item, {@code false} otherwise
     */
    @Override
    public boolean containsValue(Object value) {
        if (!(value instanceof MapNode)) {
            throw new ClassCastException(
                    "value is of type " +
                    (value == null ? "null" : value.getClass().getName()) +
                    " not " + MidiFile.class.getName()
            );
        } // End if(value ∉ MapNode)
        return rootFolder.getChildren().contains(value);
    } // End containsValue method

    /**
     * Gets the {@link MapNode} associated with the provided name.
     *
     * @param key the name of the item to retrieve
     * @return {@code null} if the item wasn't found, the requested {@code MapNode} otherwise
     */
    @Nullable
    @Override
    public MapNode get(Object key) {
        if (!(key instanceof String)) {
            throw new ClassCastException(
                    "key is of type " + (key == null ? "null" : key.getClass().getName()) + " not " + String.class.getName()
            );
        } // End if(key ∉ String)
        return rootFolder.get((String) key);
    } // End get method

    /**
     * <b>DISABLED METHOD</b><br>
     * Throws {@link UnsupportedOperationException}.
     */
    @Nullable
    @Override
    public MidiFile put(String key, MapNode value) {
        throw new UnsupportedOperationException("put method not supported by " + MidiLibrary.class.getName());
    } // End put method

    /**
     * <b>DISABLED METHOD</b><br>
     * Throws {@link UnsupportedOperationException}.
     */
    @Nullable
    @Override
    public MidiFile remove(@Nullable Object key) {
        throw new UnsupportedOperationException("remove method not supported by " + MidiLibrary.class.getName());
    } // End remove method


    /**
     * <b>DISABLED METHOD</b><br>
     * Throws {@link UnsupportedOperationException}.
     */
    @Override
    public void putAll(@NonNull Map<? extends String, ? extends MapNode> m) {
        throw new UnsupportedOperationException("putAll method not supported by " + MidiLibrary.class.getName());
    } // End putAll method

    /**
     * <b>DISABLED METHOD</b><br>
     * Throws {@link UnsupportedOperationException}.
     */
    @Override
    public void clear() {
        throw new UnsupportedOperationException("clear method not supported by " + MidiLibrary.class.getName());
    } // End clear method

    /**
     * Gets the names of all items in the root of this MIDI library.
     *
     * @return the {@link Set} of keys for all root items
     */
    @NonNull
    @Override
    public Set<String> keySet() { return rootFolder.getKeys(); }

    /**
     * Gets the names of all dead-end items in this MIDI library, including those in subfolders. For
     * a more detailed explanation of dead-end items, see {@link Folder#getKeysRecursive()}.
     *
     * @return the {@link Set} of all item names
     */
    @NonNull
    public Set<String> keySetRecursive() { return rootFolder.getKeysRecursive(); }

    /**
     * Gets all of the {@link MapNode} objects in the root of this MIDI library
     *
     * @return the {@link Collection} of root items
     */
    @NonNull
    @Override
    public Collection<MapNode> values() { return rootFolder.getChildren(); }

    /**
     * Gets all of the dead-end {@link MapNode} objects in this MIDI library, including those in subfolders.
     * For a more detailed explanation of dead-end objects, see {@link Folder#getChildrenRecursive()}
     *
     * @return the {@link Collection} of all items
     */
    @NonNull
    public Collection<MapNode> valuesRecursive() { return rootFolder.getChildrenRecursive(); }

    /**
     * <b>DISABLED METHOD</b><br>
     * throws {@link UnsupportedOperationException}.
     */
    @NonNull
    @Override
    public Set<Entry<String, MapNode>> entrySet() {
        throw new UnsupportedOperationException("entrySet method not supported by " + MidiLibrary.class.getName());
    } // End entrySet method

    /**
     * Searches for a MIDI file with a similar name
     *
     * @param query the name to search for
     * @return {@code null} if {@code query} didn't match any files, otherwise the first matched file
     */
    @Nullable
    public MidiFile searchFileFuzzy(String query) { return searchFileFuzzy(query, (f) -> true); }

    /**
     * Searches for a {@link MidiFile} using a {@link Predicate} to allow the caller to specify
     * attributes needed by a found {@code MidiFile} to be considered valid.
     * <p>
     * e.g. predicate could be {@code (file) -> { return file.getAlbum() == "Greatest Hits"; } }
     *
     * @param query     the name of the file to search for
     * @param predicate the functional interface used to validate a matching {@code MidiFile}
     * @return {@code null} if no matching files were found, otherwise the first matched file
     */
    @Nullable
    public MidiFile searchFileFuzzy(String query, Predicate<MidiFile> predicate) {
        // Replace _ and spaces with nothing and convert to lowercase for better search results
        query = query.replace("_", "");
        query = query.replace(" ", "");
        query = query.toLowerCase();

        // Retrieve the children of the requested branch and return the queried file if found
        MapNode node = get(PATH_ID);
        if (!(node instanceof Folder)) {
            throw new IllegalStateException("PATH folder not created in MidiLibrary instance");
        }
        Set<MapNode> collection = node.getChildrenRecursive();
        for (MapNode item : collection) {
            if (!(item instanceof MidiFile)) { continue; }
            String name = item.getName();
            name = name.replace("_", "");
            name = name.replace(" ", "");
            name = name.toLowerCase();
            if ((query.contains(name) || name.contains(query)) && predicate.test((MidiFile) item)) {
                return (MidiFile) item;
            }
        } // End for(item : collection)

        // Return null if no MidiFiles are found matching the query
        return null;
    } // End searchFuzzy(String, String) method

    /**
     * Callback interface used for running code upon completion of asynchronous {@link MidiLibrary} creation
     *
     * @see #getMidiLibraryAsync(Context, MidiLibrary.Callback)
     */
    public interface Callback {
        void onLoadCompletion(MidiLibrary midiLibrary);
    }

    /**
     * A structure representing a MIDI file that can be contained in a {@link Folder}.
     */
    public static class MidiFile implements MapNode { // https://developer.android.com/training/data-storage/shared/media
        private final Uri uri;
        private final String name;
        private final int duration;
        private final String artist;
        private final String album;
        private final String parentName;
        private final String globalName;
        private final MediaMetadataCompat metadata;

        /**
         * Constructs a {@code MidiFile} object.
         *
         * @param uri        the {@link Uri} that can be used to open the file
         * @param name       the file's name
         * @param duration   the duration (in milliseconds)
         * @param artist     the file's artist
         * @param album      the file's album
         * @param parentName the fully-qualified name of the parent {@link Folder}, use {@code null} if there is no parent
         */
        public MidiFile(Uri uri, String name, int duration, String artist, String album, String parentName) {
            this.uri = uri;
            this.name = name;
            this.duration = duration; // Duration is in milliseconds
            this.artist = artist;
            this.album = album;
            this.parentName = parentName;
            this.globalName = ((parentName != null) ? parentName + "/" : "") + name;

            // Create the metadata
            MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, globalName);
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, name);
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, name);
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, album);
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, artist);
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI, MUSIC_FILE_ICON_URI);
            metaBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration);
            StringBuilder timeTextBuilder = new StringBuilder();
            long durationSeconds = duration / 1000;
            String temp;
            timeTextBuilder.append(durationSeconds / 3600);
            timeTextBuilder.append(":");
            temp = Long.toString((durationSeconds % 3600) / 60);
            timeTextBuilder.append(("00" + temp).substring(temp.length())).append(":");
            temp = Long.toString(durationSeconds % 60);
            timeTextBuilder.append(("00" + temp).substring(temp.length()));
            temp = timeTextBuilder.toString();
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, temp);
            metadata = metaBuilder.build();
        } // End MidiFile constructor


        // Getters

        /**
         * Gets the {@link Uri} of this {@code MidiFile}.
         *
         * @return the {@code Uri}
         */
        public Uri getUri() { return uri; }

        /**
         * Gets the duration (in milliseconds) of this {@code MidiFile}.
         *
         * @return the duration
         */
        public int getDuration() { return duration; }

        /**
         * Gets the artist of this {@code MidiFile}.
         *
         * @return the artist
         */
        public String getArtist() { return artist; }

        /**
         * Gets the album this {@code MidiFile} belongs to.
         * <p>
         *
         * @return the album
         */
        public String getAlbum() { return album; }


        // Implement interface methods

        /**
         * Always returns {@code false}.
         *
         * @return {@code false}
         */
        @Override
        public boolean hasChildren() { return false; }

        /**
         * Always returns {@code null}.
         *
         * @return {@code null}
         */
        @Override
        public Set<MapNode> getChildren() { return null; }

        /**
         * Always returns {@code null}.
         *
         * @return {@code null}
         */
        @Override
        public Set<MapNode> getChildrenRecursive() { return null; }

        /**
         * Always returns {@code -1}.
         *
         * @return {@code -1}
         */
        @Override
        public int getChildrenCount() { return -1; }

        /**
         * Always returns {@code null}.
         *
         * @return {@code null}
         */
        @Override
        public Set<String> getKeys() { return null; }

        /**
         * Always returns {@code null}.
         *
         * @return {@code null}
         */
        @Override
        public Set<String> getKeysRecursive() { return null; }

        /**
         * Always returns {@code true}.
         *
         * @return {@code true}
         */
        @Override
        public boolean isFile() { return true; }

        /**
         * Gets the name of this {@code MidiFile}.
         *
         * @return the name
         */
        @Override
        public String getName() { return name; }

        /**
         * Gets the fully-qualified name of this {@code MidiFile}.
         *
         * @return the global name
         */
        @Override
        public String getNameGlobal() { return globalName; }

        /**
         * Gets the fully-qualified name of this {@code MidiFile}'s parent.
         *
         * @return the parent's global name
         */
        @Override
        public String getParentName() { return parentName; }

        /**
         * Gets the metadata of this {@code MidiFile}.
         *
         * @return the metadata
         */
        @Override
        public MediaMetadataCompat getMetadata() { return metadata; }

        /**
         * Compares this {@code MidiFile} with another {@link MapNode} and determines which should be sorted first. Case is ignored.
         *
         * @param node the {@code MapNode} to compare against
         * @return negative integer if this {@code MidiFile} should be sorted first, 0 if the objects should be sorted equally, or
         * a positive integer if {@code node} should be sorted first
         */
        @Override
        public int compareTo(MapNode node) { return this.name.compareToIgnoreCase(node.getName()); }
    } // End MidiFile class

    /**
     * A structure representing a folder in a {@link MidiLibrary}. Backed by a {@link TreeMap}.
     */
    public static class Folder implements MapNode {
        private final String name;
        private final String globalName;
        private final String parentName;
        private final String iconUri;
        private final MediaMetadataCompat metadata;
        private TreeMap<String, MapNode> children;

        /**
         * Creates a folder to house the provided {@link MidiFile}. The default folder icon is used for all new folders.
         *
         * @param midiFile the file to create folders to contain
         * @param iconUri  the URI {@link String} of the icon to use for the folders; use {@code null} for default icon
         * @return {@code null} if no folders are in file path
         */
        @Nullable
        public static Folder createFolderStructureWithFile(MidiFile midiFile, String iconUri) {
            // Break up the fully-qualified name of the file and ensure it is valid. Size must be 2 or greater, since
            // segments ∋ {..., parentNameSegment, folderName}, as less than 2 elements would mean a name is missing
            List<String> segments = Arrays.asList(midiFile.getNameGlobal().split("/"));
            if (segments.size() < 2) { return null; }
            if (segments.contains("")) {
                throw new InvalidPathException(midiFile.getNameGlobal(), "One or more segments missing in file/folder name");
            } // End if(segments ∋ "")

            // Reconstruct the parent name, not adding "/" for last segment
            // Note: size - 1 because last index is folder name and not part of parent name
            StringBuilder parentNameBuilder = new StringBuilder();
            for (int i = 0; i < segments.size() - 1; ++i) {
                parentNameBuilder.append(segments.get(i));
                if (i < segments.size() - 2) { parentNameBuilder.append("/"); }
            } // End for(i < segments.size - 1)

            // Recursively create the folders until the MidiFile can be added
            // Note: Default icon used
            Folder rootFolder = new Folder(segments.get(0), parentNameBuilder.toString(), null);
            if (segments.size() == 2) { rootFolder.addChild(midiFile); }
            else {
                // Reconstruct the folder path without the parent name or file name
                StringBuilder pathBuilder = new StringBuilder();
                for (int i = 1; i < segments.size() - 1; ++i) {
                    pathBuilder.append(segments.get(i));
                    if (i < segments.size() - 2) { pathBuilder.append("/"); }
                } // End for(i = 1; i < segments.size - 1)

                rootFolder.createFolder(pathBuilder.toString(), iconUri).addChild(midiFile);
            } // End if(segments.size == 2) {} else

            return rootFolder;
        } // End createFolderStructureWithFile method

        /**
         * Constructs a new {@code Folder}.
         *
         * @param folderName the name to use for this {@code Folder}
         * @param parentName the fully-qualified name of the parent {@link Folder}, use {@code null} if there is no parent
         * @param iconUri    the URI {@link String} of the icon to use for this {@code Folder}
         */
        public Folder(String folderName, String parentName, String iconUri) {
            this.children = new TreeMap<>();

            // Overridable function used to validate the folder names
            this.name = validateFolderNames(folderName, parentName);

            this.iconUri = iconUri;
            this.parentName = parentName;
            if (parentName == null) { this.globalName = this.name; }
            else { this.globalName = parentName + "/" + this.name; }

            // metadata is final so this can't be done in a method
            MediaMetadataCompat.Builder metaBuilder = new MediaMetadataCompat.Builder();
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, this.globalName);
            metaBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, this.name);
            metaBuilder.putString(
                    MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON_URI,
                    ((this.iconUri == null) ? FOLDER_ICON_URI : this.iconUri)
            );
            metadata = metaBuilder.build();
        } // End Folder(String, String, String) constructor

        // Used for cloning
        private Folder(Folder sourceFolder) {
            this.children = sourceFolder.children;
            this.name = sourceFolder.name;
            this.parentName = sourceFolder.parentName;
            this.globalName = sourceFolder.globalName;
            this.iconUri = sourceFolder.iconUri;
            this.metadata = sourceFolder.metadata;
        }

        /**
         * Verifies the passed folder and parent names. Override to change acceptable names.
         * This implementation breaks down the path segments of {@code folderName} and creates subdirectories.
         *
         * @param folderName the requested folder name
         * @param parentName the fully-qualified name of the parent folder
         * @return the processed folder name
         * @see RootFolder
         */
        protected String validateFolderNames(String folderName, String parentName) {
            if (folderName == null) {
                throw new IllegalArgumentException("folder name cannot be null");
            }

            // Break down the folder path and create necessary folders
            String[] segments = folderName.split("/");
            if (segments.length > 1) {
                // Reconstruct the path without the first segment (eventual this.name)
                StringBuilder pathBuilder = new StringBuilder();
                for (int i = 1; i < segments.length; ++i) {
                    pathBuilder.append(segments[i]).append("/");
                } // End for(i = 1; i < segments.length)
                pathBuilder.setLength(pathBuilder.length() - 1); // Remove the trailing '/'
                addChild(new Folder(pathBuilder.toString(), parentName + "/" + segments[0], null));
            } // End if(segments.length > 1)
            return segments[0];
        } // End validateFolderNames method

        /**
         * Creates a {@code Folder} with the default icon. If the requested {@code Folder} already
         * exists, that {@code Folder} is returned.
         *
         * @param folderName the name or relative path of the folder to create
         * @return the created/found {@code Folder}
         * @see Folder#createFolder(String, String)
         */
        public Folder createFolder(String folderName) { return createFolder(folderName, null); }

        /**
         * Creates a {@code Folder} with a specific icon. If the requested {@code Folder} already
         * exists, that {@code Folder} is returned and the supplied icon is ignored.
         *
         * @param folderName the name or relative path of the folder to create
         * @param iconUri    the URI {@link String} of the icon to use for the folders; use {@code null} for default icon
         * @return the created/found {@code Folder}
         */
        public Folder createFolder(String folderName, String iconUri) {
            // Remove trailing slash if necessary
            if (folderName.endsWith("/")) {
                folderName = folderName.substring(0, folderName.length() - 1);
            } // End if(folderName.end == "/")

            // Check if the folder has already been created
            MapNode node = get(folderName);
            if (node != null) {
                if (!(node instanceof Folder)) {
                    throw new InvalidPathException(
                            folderName,
                            folderName + " represents both a folder and a file"
                    ); // End new InvalidPathException
                } // End if(node ∉ Folder)
                return (Folder) node;
            } // End if(node != null)

            // Can't cache and return the new folder because the folder name may be a path
            addChild(new Folder(folderName, this.globalName, iconUri));
            return (Folder) get(folderName);
        } // End createFolder method

        /**
         * Creates a {@link MidiFile} in this {@code Folder}.
         *
         * @param uri      the {@link Uri} that can be used to open the file
         * @param fileName the file's name
         * @param duration the duration (in milliseconds)
         * @param artist   the file's artist
         * @param album    the file's album
         * @return the created file
         */
        public MidiFile createFile(Uri uri, String fileName, int duration, String artist, String album) {
            MidiFile midiFile = new MidiFile(uri, fileName, duration, artist, album, this.getNameGlobal());
            addChild(midiFile);
            return midiFile;
        } // End createFile method

        /**
         * Adds a {@link MapNode} to this {@code Folder}. If a {@code MapNode} with the same name as
         * {@code node} already exists and they are both {@code Folder}s, they are combined; if they
         * are not both {@code Folder}s, an {@link InvalidPathException} is raised.
         *
         * @param node the {@code MapNode} to be added
         */
        public void addChild(@Nullable MapNode node) {
            if (node == null) { return; }
            // Attempt to merge nodes if the node to be added already exists, otherwise add it
            if (children.containsKey(node.getName())) {
                // Ensure both nodes folders, and then combine them
                MapNode existingNode = children.get(node.getName());
                if ((!(existingNode instanceof Folder)) || (!(node instanceof Folder))) {
                    throw new InvalidPathException(
                            getNameGlobal() + "/" + node.getName(),
                            "Both instances of " + node.getName() + " are not folders"
                    );
                } // End if(existingNode ∉ Folder)
                Set<MapNode> nodeChildren = node.getChildren();
                for (MapNode child : nodeChildren) { ((Folder) existingNode).addChild(child); }
            } // End if(children ∋ node.name)
            else { children.put(node.getName(), node); }
        } // End addChild method

        /**
         * Gets a child of this {@code Folder}.
         *
         * @param key the name or relative path of the requested item
         * @return {@code null} if the item wasn't found, otherwise the requested item as a {@code MapNode}
         */
        public MapNode get(String key) {
            int index = key.indexOf("/");
            if (index == -1 || index + 1 >= key.length()) { return children.get(key); }
            MapNode node = children.get(key.substring(0, index));
            if (!(node instanceof Folder)) { return null; }
            return ((Folder) node).get(key.substring(index + 1));
        } // End get method

        /**
         * Checks if a {@link String} is the name of an item that is a direct child of this {@code Folder}.
         *
         * @param key the name of the item
         * @return {@code true} if {@code key} is the name of an item is in this {@code Folder}, otherwise {@code false}
         */
        public boolean containsKey(String key) { return children.containsKey(key); }

        /**
         * Checks if a {@link MapNode} is a direct child of this {@code Folder}.
         *
         * @param value the item to check for
         * @return {@code true} if {@code value} is an item is in this {@code Folder}, otherwise {@code false}
         */
        public boolean containsValue(MapNode value) { return children.containsValue(value); }

        /**
         * Creates a new {@code Folder} with the same properties as this {@code Folder}.
         *
         * @return this {@code Folder}'s clone
         */
        @Override
        @NonNull
        public Folder clone() {
            try { super.clone(); } catch (CloneNotSupportedException ignored) { }
            return new Folder(this);
        } // End clone method

        /**
         * Gets all of the subfolders contained in this {@code Folder}.
         *
         * @return A {@link Set} containing all subfolders
         */
        public Set<Folder> getFoldersRecursive() {
            Set<Folder> result = new TreeSet<>();
            Collection<MapNode> nodes = children.values();
            for (MapNode node : nodes) {
                if (node instanceof Folder) {
                    result.add((Folder) node);
                    result.addAll(((Folder) node).getFoldersRecursive());
                } // End if(node ∈ Folder)
            } // End for(node : nodes)
            return result;
        } // End getFoldersRecursive method

        /**
         * Gets the URI of the icon this {@code Folder} uses.
         *
         * @return the URI as a {@link String}
         */
        public String getIconUri() { return iconUri; }


        // Implement interface methods

        /**
         * Checks if this {@code Folder} has any children.
         *
         * @return {@code true} if this {@code Folder} has children, otherwise {@code false}
         */
        @Override
        public boolean hasChildren() { return !children.isEmpty(); }

        /**
         * Gets all of the children of this {@code Folder}.
         *
         * @return the {@link Set} of all children
         */
        @Override
        public Set<MapNode> getChildren() { return new TreeSet<>(children.values()); }

        /**
         * Recursively gets all the dead-end children of this {@code Folder}. In other words,
         * any {@link MapNode}s without children (e.g. an empty {@code Folder} or a {@link MidiFile})
         * are found and returned.
         *
         * @return the {@link Set} of all children which have no children
         */
        @Override
        public Set<MapNode> getChildrenRecursive() {
            Set<MapNode> childrenSet = new TreeSet<>();
            Collection<MapNode> nodes = children.values();
            for (MapNode node : nodes) {
                if (node.hasChildren()) { childrenSet.addAll(node.getChildrenRecursive()); }
                else { childrenSet.add(node); }
            } // End for(node : nodes)
            return childrenSet;
        } // End getChildrenRecursive method

        /**
         * Gets the number children this {@code Folder} has.
         *
         * @return the size of the children table
         */
        @Override
        public int getChildrenCount() { return children.size(); }

        /**
         * Gets the names of all direct children of this {@code Folder}.
         *
         * @return the {@link Set} of all children names
         */
        @Override
        public Set<String> getKeys() { return children.keySet(); }

        /**
         * Recursively gets the names of all dead-end children children of this {@code Folder}. In other words,
         * the names of any {@link MapNode}s without children (e.g. an empty {@code Folder} or a {@link MidiFile})
         * are found and returned.
         *
         * @return the {@link Set} of all names of children which have no children
         */
        @Override
        public Set<String> getKeysRecursive() {
            Set<String> keys = new TreeSet<>();
            Collection<MapNode> nodes = children.values();
            for (MapNode node : nodes) {
                if (node.hasChildren()) { keys.addAll(node.getKeysRecursive()); }
                else { keys.add(node.getName()); }
            } // End for(node : nodes)
            return keys;
        } // End getKeysRecursive method

        /**
         * Always returns {@code false}.
         *
         * @return {@code false}
         */
        @Override
        public boolean isFile() { return false; }

        /**
         * Gets the name of this {@code Folder}.
         *
         * @return the name
         */
        @Override
        public String getName() { return name; }

        /**
         * Gets the fully-qualified name of this {@code Folder}.
         *
         * @return the global name
         */
        @Override
        public String getNameGlobal() { return globalName; }

        /**
         * Gets the fully-qualified name of this {@code Folder}'s parent.
         *
         * @return the parent's global name
         */
        @Override
        public String getParentName() { return parentName; }

        /**
         * Gets the metadata of this {@code Folder}.
         *
         * @return the metadata
         */
        @Override
        public MediaMetadataCompat getMetadata() { return metadata; }

        /**
         * Compares this {@code Folder} with another {@link MapNode} and determines which should be sorted first. Case is ignored.
         *
         * @param node the {@code MapNode} to compare against
         * @return negative integer if this {@code Folder} should be sorted first, 0 if the objects should be sorted equally, or
         * a positive integer if {@code node} should be sorted first
         */
        @Override
        public int compareTo(MapNode node) { return this.name.compareToIgnoreCase(node.getName()); }
    } // End Folder class

    /**
     * Subclass of {@link Folder} that allows for a null name.
     */
    protected static class RootFolder extends Folder {
        /**
         * Constructs a new {@code RootFolder}.
         */
        public RootFolder() { super((String) null, null, null); }

        /**
         * Name validation disabled, always returns {@code null} for the {@link Folder} name.
         *
         * @return {@code null}
         */
        @Override
        protected String validateFolderNames(String folderName, String parentName) { return null; }
    } // End RootFolder class

    /**
     * Represents an entry in a {@link Folder}.
     */
    public interface MapNode extends Comparable<MapNode> {
        /**
         * Checks if this {@code MapNode} has any children.
         *
         * @return {@code true} if this {@code MapNode} has children, otherwise {@code false}
         */
        boolean hasChildren();

        /**
         * Gets all of the children of this {@code MapNode}. Returns {@code null} if the {@code MapNode}'s
         * implementation doesn't store children.
         *
         * @return {@code null} if the {@code MapNode} implementation doesn't store children, otherwise the
         * {@link Set} of all children
         */
        Set<MapNode> getChildren();

        /**
         * Recursively gets all the children of this {@code MapNode}, including those contained in
         * {@link Folder}-like structures stored in this {@code MapNode}. Returns {@code null} if
         * the {@code MapNode}'s implementation doesn't store children.
         *
         * @return the {@link Set} of all descendants
         * @see Folder#getChildrenRecursive()
         */
        Set<MapNode> getChildrenRecursive();

        /**
         * Gets the number of children this {@code MapNode} contains. Returns -1 if the implementation
         * doesn't store children.
         *
         * @return {@code -1} if children aren't supported, otherwise the number of stored children
         */
        int getChildrenCount();

        /**
         * Gets the names of all direct children of this {@code MapNode}. Returns {@code null} if the
         * implementation doesn't store children
         *
         * @return the {@link Set} of all children names
         */
        Set<String> getKeys();

        /**
         * Recursively gets the names of all the children of this {@code MapNode}, including those
         * contained in {@link Folder}-like structures stored in this {@code MapNode}. Returns {@code null}
         * if the {@code MapNode}'s implementation doesn't store children
         *
         * @return the {@link Set} of all names of children which have no children
         */
        Set<String> getKeysRecursive();

        /**
         * Checks whether this {@code MapNode} is a "file" or "folder", where a folder is defined as
         * any {@code MapNode} implementation that stores other {@code MapNode}s, and a file is defined
         * as any other implementation.<br>
         * <br>
         * <b>Implementers must override</b> to <u>always</u> return {@code true} or {@code false}
         * in compliance with the above contract.
         *
         * @return {@code true} if the implementation stores {@code MapNode}s, otherwise {@code false}
         */
        boolean isFile();

        /**
         * Gets the name of this {@code MapNode}.
         *
         * @return the name
         */
        String getName();

        /**
         * Gets the fully-qualified name of this {@code MapNode}. This name includes all parent path
         * segments leading up to the root directory.<br>
         * <br>
         * For example, a {@link MidiFile} {code Tetris.mid} could be housed in a tree of {@link Folder}s:<br>
         * <ul style="list-style-type:none;">
         * <li>{@code ROOT_FOLDER -> Music -> Folk -> Korobeiniki -> Tetris.mid}</li>
         * </ul>
         * The {@code getName} method must return {@code Tetris.mid}, and the {@code getNameGlobal} method
         * must return {@code ROOT_FOLDER/Music/Folk/Korobeiniki/Tetris.mid}.
         *
         * @return the global name
         */
        String getNameGlobal();

        /**
         * Gets the metadata of this {@code MapNode}.
         *
         * @return the metadata
         */
        MediaMetadataCompat getMetadata();

        /**
         * Returns the fully-qualified name of this {@code MapNode}'s parent, or {@code null} if the
         * {@code MapNode} doesn't have a parent.<br>
         * <br>
         * To expand on the example provided at {@link #getNameGlobal}, {@code getParentName} would return
         * {@code ROOT_FOLDER/Music/Folk/Korobeiniki}.
         *
         * @return the global name of the parent
         */
        String getParentName();
    } // End MapNode interface
} // End MidiLibrary class

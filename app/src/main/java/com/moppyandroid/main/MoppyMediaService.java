package com.moppyandroid.main;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;

import androidx.annotation.Nullable;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

public class MoppyMediaService extends Service {
    public static final String CHANNEL_ID = "MediaServiceChannel";
    public static final String ACTION_ADD_DEVICE = "com.moppyandroid.main.ADD_DEVICE";
    public static final String ACTION_REMOVE_DEVICE = "com.moppyandroid.main.REMOVE_DEVICE";
    public static final String ACTION_LOAD_FILE = "com.moppyandroid.main.LOAD_FILE";
    public static final String ACTION_PLAY = "com.moppyandroid.main.PLAY";
    public static final String ACTION_PAUSE = "com.moppyandroid.main.PAUSE";
    public static final String ACTION_STOP = "com.moppyandroid.main.STOP";

    private static final int NOTIFICATION_ID = 1;
    private NotificationManager notificationManager;

    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) throws NullPointerException {
            // Ensure intent action is not null, exiting processing if so
            if (intent.getAction() == null) { return; }

            // Determine action and process accordingly
            switch (intent.getAction()) {
                case ACTION_ADD_DEVICE: {
                    onAddDevice(intent);
                    break;
                }
                case ACTION_REMOVE_DEVICE: {
                    onRemoveDevice(intent);
                    break;
                }
                case ACTION_LOAD_FILE: {
                    onLoadFile(intent);
                    break;
                }
                case ACTION_PLAY: {
                    onPlay(intent);
                    break;
                }
                case ACTION_PAUSE: {
                    onPause(intent);
                    break;
                }
                case ACTION_STOP:{
                    onStop(intent);
                    break;
                }
            } // End switch(intent.action)
        } // End onReceive method
    }; // End new BroadcastReceiver

    /**
     * Triggered when the service is first created, usually by the first {@link Context#startService(Intent)}
     * call. Always triggered before {@link #onStartCommand(Intent, int, int)}.
     */
    @Override
    public void onCreate() {
        // Create the intent filter and register the receiver
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(ACTION_ADD_DEVICE);
        intentFilter.addAction(ACTION_REMOVE_DEVICE);
        intentFilter.addAction(ACTION_LOAD_FILE);
        LocalBroadcastManager.getInstance(this).registerReceiver(broadcastReceiver, intentFilter);

        // Create the notification channel
        @SuppressLint("WrongConstant") // Don't know why this is appearing, I'm using what is suggests
        NotificationChannel notificationChannel = new NotificationChannel(
                CHANNEL_ID,
                "Media Service Channel",
                NotificationManager.IMPORTANCE_LOW
        );
        notificationManager = getSystemService(NotificationManager.class);
        if (notificationManager == null) {
            throw new RuntimeException("No notification manager found");
        }
        notificationManager.createNotificationChannel(notificationChannel);
    }

    /**
     * Triggered when this service is started with a call to {@link Context#startService}.
     *
     * @param intent the intent used in the {@code Context.startService call}
     * @param flags either 0, {@link Service#START_FLAG_REDELIVERY}, or {@link Service#START_FLAG_RETRY}
     * @param startId unique identifier for this start instance, used with {@link Service#stopSelfResult(int)}
     * @return
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Create the initial notification and start this service in the foreground
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Moppy Media Player")
                        .setContentText("No song loaded")
                        .setSmallIcon(R.drawable.ic_launcher_background) // TODO: Create proper icon
                        .setContentIntent(pendingIntent)
                        .build();
        startForeground(NOTIFICATION_ID, notification);
        return START_REDELIVER_INTENT;
    }

    /**
     * Triggered when the activity is killed (e.g. swipe up in recent apps, G.C.).
     * Not called if the app is force killed.
     *
     * @param rootIntent the intent used to launch the task being removed
     */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopForeground(true);
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    /**
     * Triggered when the service has completed and is being destroyed by the garbage collector.
     */
    @Override
    public void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(broadcastReceiver);
        super.onDestroy();
    }

    private void onLoadFile(Intent intent) {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, 0);
        Notification notification =
                new Notification.Builder(this, CHANNEL_ID)
                        .setContentTitle("Moppy Media Player")
                        .setContentText("AllStar.mid") // TODO: Set to name of loaded song
                        .setSmallIcon(R.drawable.ic_launcher_background) // TODO: Create proper icon
                        .setContentIntent(pendingIntent)
                        .build();
        notificationManager.notify(NOTIFICATION_ID, notification);
    }

    // Triggered by an ACTION_ADD_DEVICE broadcast
    private void onAddDevice(Intent intent) {
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent(MainActivity.ACTION_UNABLE_TO_CONNECT_DEVICE)
        ); // End sendBroadcast call
    }

    // Triggered by an ACTION_REMOVE_DEVICE broadcast
    private void onRemoveDevice(Intent intent){
        LocalBroadcastManager.getInstance(this).sendBroadcast(
                new Intent(MainActivity.ACTION_UNABLE_TO_CONNECT_DEVICE)
        ); // End sendBroadcast call
    }

    // Triggered by an ACTION_PLAY broadcast
    private void onPlay(Intent intent){

    }

    // Triggered by an ACTION_PAUSE broadcast
    private void onPause(Intent intent){

    }

    // Triggered by an ACTION_STOP broadcast
    private void onStop(Intent intent) {

    }

    /**
     * Binding disabled.
     *
     * @return null
     */
    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}

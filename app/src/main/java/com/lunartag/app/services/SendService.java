package com.lunartag.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.lunartag.app.R;

import java.io.File;

public class SendService extends Service {

    private static final String TAG = "SendService";
    private static final String CHANNEL_ID = "SendServiceChannel";
    private static final int NOTIFICATION_ID = 101;

    public static final String EXTRA_FILE_PATH = "com.lunartag.app.EXTRA_FILE_PATH";

    // Prefs to read settings (User input)
    private static final String PREFS_SETTINGS = "LunarTagSettings";
    private static final String KEY_WHATSAPP_GROUP = "whatsapp_group";

    // Prefs to communicate with Accessibility Service (The Bridge)
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_JOB_PENDING = "job_is_pending";

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String filePath = intent.getStringExtra(EXTRA_FILE_PATH);

        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "File path was null or empty. Stopping service.");
            stopSelf();
            return START_NOT_STICKY;
        }

        File imageFile = new File(filePath);
        if (!imageFile.exists()) {
            Log.e(TAG, "Image file does not exist at path: " + filePath);
            stopSelf();
            return START_NOT_STICKY;
        }

        // --- STEP 1: "Arm" the Accessibility Service ---
        // We retrieve the group name ("Love") and save it where the Accessibility Service can see it.
        armAccessibilityService();

        // --- STEP 2: Prepare the Notification ---
        
        // 1. Prepare the URI
        Uri imageUri = FileProvider.getUriForFile(
                this,
                getApplicationContext().getPackageName() + ".fileprovider",
                imageFile
        );

        // 2. Create the Intent that opens WhatsApp
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
        shareIntent.setPackage("com.whatsapp"); // Target WhatsApp specifically
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        shareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        // 3. Wrap it in a PendingIntent 
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this,
                0,
                shareIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // 4. Build the High-Priority Notification
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Scheduled Photo Ready")
                .setContentText("Tap here to send to WhatsApp Group")
                .setSmallIcon(R.drawable.ic_camera) 
                .setContentIntent(pendingIntent) 
                .setPriority(NotificationCompat.PRIORITY_HIGH) 
                .setCategory(NotificationCompat.CATEGORY_ALARM) 
                .setAutoCancel(true) 
                .build();

        // 5. Show it immediately
        startForeground(NOTIFICATION_ID, notification);
        
        Log.d(TAG, "Notification posted. Accessibility armed. Waiting for user tap.");

        return START_NOT_STICKY;
    }

    /**
     * Reads the Group Name from settings and flags it for the Accessibility Service.
     */
    private void armAccessibilityService() {
        SharedPreferences settings = getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        String groupName = settings.getString(KEY_WHATSAPP_GROUP, "");

        if (groupName != null && !groupName.isEmpty()) {
            // Write this to the shared memory bridge
            SharedPreferences accessPrefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
            accessPrefs.edit()
                    .putString(KEY_TARGET_GROUP, groupName)
                    .putBoolean(KEY_JOB_PENDING, true)
                    .apply();
            Log.d(TAG, "Accessibility Armed for Group: " + groupName);
        } else {
            Log.w(TAG, "No WhatsApp Group set in Settings. Automation will be skipped.");
        }
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled Send Notifications",
                    NotificationManager.IMPORTANCE_HIGH
            );
            serviceChannel.setDescription("Alerts when a photo is ready to be sent via WhatsApp");
            serviceChannel.enableVibration(true);
            
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
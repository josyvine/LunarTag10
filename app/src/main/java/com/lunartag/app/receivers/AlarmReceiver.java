package com.lunartag.app.receivers;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;
import androidx.core.content.FileProvider;

import com.lunartag.app.R;

import java.io.File;

/**
 * The "Doorbell" Receiver.
 * UPDATED: Fixed Notification Overwriting (Queue Collision) and Full-Auto Triggering.
 */
public class AlarmReceiver extends BroadcastReceiver {

    private static final String TAG = "AlarmReceiver";

    // Keys to retrieve data from Scheduler
    public static final String EXTRA_FILE_PATH = "com.lunartag.app.EXTRA_FILE_PATH";
    // NEW: We receive the Photo ID to create unique notifications
    public static final String EXTRA_PHOTO_ID = "com.lunartag.app.EXTRA_PHOTO_ID";

    // Settings Prefs (To read "Target" group name)
    private static final String PREFS_SETTINGS = "LunarTagSettings";
    private static final String KEY_WHATSAPP_GROUP = "whatsapp_group";

    // Bridge Prefs (To write commands for the Robot)
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    // NEW: Needed to check if we should skip notification
    private static final String KEY_AUTO_MODE = "automation_mode";

    private static final String CHANNEL_ID = "SendServiceChannel"; 

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "Alarm Received! Waking up...");

        String filePath = intent.getStringExtra(EXTRA_FILE_PATH);
        // Default to current time if ID is missing, ensuring uniqueness
        long photoId = intent.getLongExtra(EXTRA_PHOTO_ID, System.currentTimeMillis());

        if (filePath == null || filePath.isEmpty()) {
            Log.e(TAG, "No file path provided in Alarm Intent.");
            return;
        }

        // 1. Validate File & Get URI (Handles both SD Card & Internal)
        Uri imageUri;
        try {
            if (filePath.startsWith("content://")) {
                // Custom Folder (SD Card / SAF)
                imageUri = Uri.parse(filePath);
                // We trust SAF URIs persist, but we verify access permissions logically
                context.getContentResolver().takePersistableUriPermission(imageUri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            } else {
                // Internal Storage
                File file = new File(filePath);
                if (!file.exists()) {
                    Log.e(TAG, "File missing at: " + filePath);
                    return;
                }
                // Secure File Provider URI
                imageUri = FileProvider.getUriForFile(
                        context,
                        context.getPackageName() + ".fileprovider",
                        file
                );
            }
        } catch (Exception e) {
            Log.e(TAG, "URI Parse Error: " + e.getMessage());
            return;
        }

        // 2. Arm the Accessibility Bridge (So the robot knows what to do)
        armAccessibilityService(context);

        // --- NEW LOGIC: CHECK MODE SEPARATION ---
        SharedPreferences accessPrefs = context.getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = accessPrefs.getString(KEY_AUTO_MODE, "semi");

        if ("full".equals(mode)) {
            // 3A. FULL AUTOMATIC: DIRECT LAUNCH (ZERO CLICK)
            // Opens WhatsApp Package directly, triggering the Clone/Original dialog instantly.
            launchDirectlyForFullAuto(context, imageUri);
        } else {
            // 3B. SEMI AUTOMATIC: SHOW NOTIFICATION (ORIGINAL LOGIC)
            // We pass the unique photoId so notifications don't overwrite each other.
            showNotification(context, imageUri, (int) photoId);
        }
    }

    /**
     * FULL AUTO EXCLUSIVE: Launches WhatsApp directly without user interaction.
     */
    private void launchDirectlyForFullAuto(Context context, Uri imageUri) {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("image/*");
            intent.putExtra(Intent.EXTRA_STREAM, imageUri);
            
            // MAGIC FIX: Force the intent to only see WhatsApp.
            // This causes Android to open the "Select App" dialog showing only Original and Clone.
            intent.setPackage("com.whatsapp");
            
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Required when starting activity from Receiver
            
            context.startActivity(intent);
            Log.d(TAG, "Full Auto: Direct Launch Fired.");
        } catch (Exception e) {
            Log.e(TAG, "Full Auto Launch Failed: " + e.getMessage());
            // Fallback: If direct launch fails, show notification
            showNotification(context, imageUri, 999);
        }
    }

    /**
     * Writes the Target Group Name to persistent memory so the
     * Accessibility Service can read it whenever WhatsApp finally opens.
     */
    private void armAccessibilityService(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE);
        String groupName = settings.getString(KEY_WHATSAPP_GROUP, "");

        if (groupName != null && !groupName.isEmpty()) {
            SharedPreferences accessPrefs = context.getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
            accessPrefs.edit()
                    .putString(KEY_TARGET_GROUP, groupName)
                    .putBoolean(KEY_JOB_PENDING, true) // TELLS ROBOT: "WAKE UP"
                    .apply();
            Log.d(TAG, "Bridge Armed for Group: " + groupName);
        }
    }

    /**
     * Posts the high-priority notification.
     * Uses Intent.createChooser() to allow selecting Clone Apps.
     */
    private void showNotification(Context context, Uri imageUri, int notificationId) {
        createNotificationChannel(context);

        // A. The Share Intent
        Intent shareIntent = new Intent(Intent.ACTION_SEND);
        shareIntent.setType("image/*");
        shareIntent.putExtra(Intent.EXTRA_STREAM, imageUri);
        shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        // B. The Chooser Intent (Forces the "Select App" menu)
        // This title "Select WhatsApp..." helps the Robot know where it is.
        Intent chooserIntent = Intent.createChooser(shareIntent, "Select WhatsApp to Send...");

        // C. The PendingIntent
        // CRITICAL: We use notificationId as request code to ensure unique PendingIntents
        PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                notificationId, 
                chooserIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        // D. The Notification
        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_camera) 
                .setContentTitle("Photo Ready to Send")
                .setContentText("Scheduled Upload #" + notificationId)
                .setPriority(NotificationCompat.PRIORITY_MAX) // Max Priority for Heads-up
                .setCategory(NotificationCompat.CATEGORY_ALARM) // Bypass DND
                .setContentIntent(pendingIntent)
                .setFullScreenIntent(pendingIntent, true) // Try to pop up immediately if allowed
                .setAutoCancel(true); // Remove when clicked

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            // FIX: Use unique notificationId instead of constant 999
            manager.notify(notificationId, builder.build());
            Log.d(TAG, "Notification Posted ID: " + notificationId);
        }
    }

    private void createNotificationChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "Scheduled Sends",
                    NotificationManager.IMPORTANCE_HIGH // High importance for pop-ups
            );
            channel.setDescription("Notifications for scheduled photo uploads");
            channel.enableVibration(true);
            channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);

            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(channel);
            }
        }
    }
}
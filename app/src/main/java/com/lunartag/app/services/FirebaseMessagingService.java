package com.lunartag.app.services;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.firebase.messaging.RemoteMessage;

import java.util.Map;

/**
 * This service handles incoming Firebase Cloud Messages (FCM).
 * Its primary purpose is to listen for silent, data-only messages that are used
 * to remotely enable or disable application features.
 */
public class FirebaseMessagingService extends com.google.firebase.messaging.FirebaseMessagingService {

    private static final String TAG = "FCMService";

    private static final String PREFS_NAME = "LunarTagFeatureToggles";
    private static final String KEY_CUSTOM_TIMESTAMP_ENABLED = "customTimestampEnabled";
    private static final String KEY_WHATSAPP_GROUP_NAME = "whatsappGroupName";

    @Override
    public void onMessageReceived(@NonNull RemoteMessage remoteMessage) {
        super.onMessageReceived(remoteMessage);

        Log.d(TAG, "FCM Message Received from: " + remoteMessage.getFrom());

        // Check if the message contains a data payload.
        if (remoteMessage.getData().size() > 0) {
            Map<String, String> data = remoteMessage.getData();
            Log.d(TAG, "Message data payload: " + data);

            // Get the SharedPreferences editor to save the new settings.
            SharedPreferences prefs = getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            SharedPreferences.Editor editor = prefs.edit();

            // Check for the 'customTimestampEnabled' key
            if (data.containsKey(KEY_CUSTOM_TIMESTAMP_ENABLED)) {
                // The value will be a string "true" or "false"
                boolean isEnabled = Boolean.parseBoolean(data.get(KEY_CUSTOM_TIMESTAMP_ENABLED));
                editor.putBoolean(KEY_CUSTOM_TIMESTAMP_ENABLED, isEnabled);
                Log.d(TAG, "Remote toggle received: customTimestampEnabled set to " + isEnabled);
            }

            // Check for the 'whatsappGroupName' key
            if (data.containsKey(KEY_WHATSAPP_GROUP_NAME)) {
                String groupName = data.get(KEY_WHATSAPP_GROUP_NAME);
                editor.putString(KEY_WHATSAPP_GROUP_NAME, groupName);
                Log.d(TAG, "Remote config received: whatsappGroupName set to " + groupName);
            }

            // Apply the changes.
            editor.apply();
        }
    }

    @Override
    public void onNewToken(@NonNull String token) {
        super.onNewToken(token);
        Log.d(TAG, "Refreshed FCM token: " + token);
        // In a user-based system, you would send this token to your server.
        // For this app, it is not necessary as we are using topics.
    }
                  }

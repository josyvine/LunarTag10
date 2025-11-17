package com.lunartag.app;

import android.app.Application;

// Import the core FirebaseApp class
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;

/**
 * The custom Application class for Lunar Tag.
 * This is the entry point of the application process.
 */
public class LunarTagApplication extends Application {

    private static final String FCM_TOPIC_FEATURE_TOGGLES = "feature_toggles";

    @Override
    public void onCreate() {
        super.onCreate();

        // Manually initialize Firebase. This MUST be the first Firebase call.
        FirebaseApp.initializeApp(this);

        // Now that Firebase is initialized, it is safe to use its services.
        // Subscribe to the remote feature toggle topic. This is a "fire and forget" call.
        FirebaseMessaging.getInstance().subscribeToTopic(FCM_TOPIC_FEATURE_TOGGLES);
    }
}
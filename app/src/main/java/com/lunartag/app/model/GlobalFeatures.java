package com.lunartag.app.model;

/**
 * A simple data model class (POJO) to represent the global feature toggles
 * that are controlled remotely. This structure matches the data sent via a silent FCM push message
 * and is stored locally in SharedPreferences.
 */
public class GlobalFeatures {

    private boolean customTimestampEnabled;
    private String whatsappGroupName;

    // A no-argument constructor is helpful for various data mapping scenarios
    public GlobalFeatures() {}

    // --- Getters and Setters for all fields ---

    public boolean isCustomTimestampEnabled() {
        return customTimestampEnabled;
    }

    public void setCustomTimestampEnabled(boolean customTimestampEnabled) {
        this.customTimestampEnabled = customTimestampEnabled;
    }

    public String getWhatsappGroupName() {
        return whatsappGroupName;
    }

    public void setWhatsappGroupName(String whatsappGroupName) {
        this.whatsappGroupName = whatsappGroupName;
    }
}
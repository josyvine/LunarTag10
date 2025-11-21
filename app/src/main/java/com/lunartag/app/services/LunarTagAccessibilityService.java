package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * The Automation Brain.
 * UPDATED: Supports "Semi-Automatic" (User clicks share) and "Full-Automatic" (Zero Click).
 * Handles Notification clicking, App Selection (Clones), and WhatsApp Sending.
 */
public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String TAG = "AccessibilityService";

    // --- Shared Memory Constants ---
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    
    // Keys must match what we will save in RobotFragment and AppsFragment
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode"; // "semi" or "full"
    private static final String KEY_TARGET_APP_LABEL = "target_app_label"; // e.g., "WhatsApp" or "WhatsApp (Clone)"

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 1. Check if we have a pending job. If not, do nothing.
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        boolean isJobPending = prefs.getBoolean(KEY_JOB_PENDING, false);

        if (!isJobPending) {
            return;
        }

        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetGroupName = prefs.getString(KEY_TARGET_GROUP, "");
        String targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp");

        // ---------------------------------------------------------
        // STEP 1: HANDLE NOTIFICATION (Full Automatic Only)
        // ---------------------------------------------------------
        if (mode.equals("full") && event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            // Check if this notification is from OUR app
            if (event.getPackageName() != null && event.getPackageName().toString().equals(getPackageName())) {
                Parcelable data = event.getParcelableData();
                if (data instanceof Notification) {
                    showLiveLog("Auto: Notification Detected. Clicking...");
                    try {
                        Notification notification = (Notification) data;
                        if (notification.contentIntent != null) {
                            notification.contentIntent.send();
                            return; // Wait for the next screen (Share Sheet)
                        }
                    } catch (PendingIntent.CanceledException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        // Get the screen content
        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        CharSequence packageNameSeq = event.getPackageName();
        String packageName = (packageNameSeq != null) ? packageNameSeq.toString().toLowerCase() : "";

        // ---------------------------------------------------------
        // STEP 2: HANDLE SHARE SHEET / APP SELECTION (Full Automatic Only)
        // ---------------------------------------------------------
        // If we are in "Full" mode, we need to find the App the user selected (e.g., "WhatsApp Clone")
        // System share sheets usually run under 'android' or 'com.android...' packages.
        if (mode.equals("full")) {
            // We assume we are in the chooser if we are NOT in WhatsApp yet
            if (!packageName.contains("whatsapp")) {
                List<AccessibilityNodeInfo> appNodes = rootNode.findAccessibilityNodeInfosByText(targetAppLabel);
                if (appNodes != null && !appNodes.isEmpty()) {
                    for (AccessibilityNodeInfo node : appNodes) {
                        if (node.isClickable()) {
                            showLiveLog("Auto: Found App '" + targetAppLabel + "'. Clicking...");
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            rootNode.recycle();
                            return; 
                        } else {
                            // Sometimes the text is inside a parent that is clickable
                            AccessibilityNodeInfo parent = node.getParent();
                            if (parent != null && parent.isClickable()) {
                                showLiveLog("Auto: Found App '" + targetAppLabel + "'. Clicking...");
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                                rootNode.recycle();
                                return;
                            }
                        }
                    }
                }
            }
        }

        // ---------------------------------------------------------
        // STEP 3: HANDLE WHATSAPP (Both Semi & Full)
        // ---------------------------------------------------------
        // Only proceed if the active app is WhatsApp (or a clone package containing 'whatsapp')
        if (packageName.contains("whatsapp")) {
            
            // A. Priority: Find "Send" Button FIRST (To finish the job and stop loops)
            List<AccessibilityNodeInfo> sendButtonNodes = rootNode.findAccessibilityNodeInfosByText("Send");
            if (sendButtonNodes != null && !sendButtonNodes.isEmpty()) {
                for (AccessibilityNodeInfo node : sendButtonNodes) {
                    if (node.isClickable()) {
                        showLiveLog("Auto: Found 'Send' Button. Clicking...");
                        node.performAction(AccessibilityNodeInfo.ACTION_CLICK);

                        // --- JOB COMPLETE: Turn off the flag ---
                        prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                        showLiveLog("Auto-Send Complete! Job Finished.");

                        rootNode.recycle();
                        return; // Done.
                    }
                }
            }

            // B. Secondary: Find the Group Name (e.g., "Love")
            if (!targetGroupName.isEmpty()) {
                List<AccessibilityNodeInfo> groupNodes = rootNode.findAccessibilityNodeInfosByText(targetGroupName);
                if (groupNodes != null && !groupNodes.isEmpty()) {
                    for (AccessibilityNodeInfo node : groupNodes) {
                        // Check hierarchy for clickability
                        AccessibilityNodeInfo clickableNode = node;
                        if (!clickableNode.isClickable()) {
                            clickableNode = node.getParent();
                        }
                        
                        if (clickableNode != null && clickableNode.isClickable()) {
                            showLiveLog("Auto: Found Group '" + targetGroupName + "'. Clicking...");
                            clickableNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                            rootNode.recycle();
                            return;
                        }
                    }
                }
            }
        }

        // Clean up
        rootNode.recycle();
    }

    /**
     * Live Log Helper: Shows visual confirmation of background actions on screen.
     */
    private void showLiveLog(String message) {
        new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_SHORT).show()
        );
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted.");
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        showLiveLog("LunarTag Automation Ready");
    }
}
package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * LUNARTAG ROBOT - FINAL "HARD RESET" EDITION
 * 
 * FIXES:
 * 1. "One Time Only" -> Fixed by resetting state every time App Package changes.
 * 2. "Silent Log" -> Fixed by using Application Context and Debug Toasts.
 * 3. "Full Auto" -> Fixed by using raw Notification Intents.
 */
public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";

    // States
    private static final int STATE_IDLE = 0;
    private static final int STATE_WAITING_FOR_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private String lastPackageName = "";
    private boolean isScrolling = false;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        
        // Configure to listen to EVERYTHING
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0; 
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        
        currentState = STATE_IDLE;
        
        // 1. DEBUG TOAST: PROOF OF LIFE
        // If you do not see this Toast when you turn the switch ON, the service is broken.
        new Handler(Looper.getMainLooper()).post(() -> 
            Toast.makeText(getApplicationContext(), "🤖 ROBOT CONNECTED & LISTENING", Toast.LENGTH_LONG).show());

        performBroadcastLog("🔴 SYSTEM READY. Mode: Checking...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        
        // 1. TRACK PACKAGE CHANGES (The "Everytime" Fix)
        String pkgName = "unknown";
        if (event.getPackageName() != null) {
            pkgName = event.getPackageName().toString().toLowerCase();
        }

        // IF APP CHANGED, HARD RESET THE BRAIN
        if (!pkgName.equals(lastPackageName)) {
            if (!lastPackageName.isEmpty()) {
                performBroadcastLog("🔄 App Switch Detected: " + pkgName);
                currentState = STATE_IDLE; // RESET STATE MACHINE
            }
            lastPackageName = pkgName;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ====================================================================
        // FULL AUTOMATIC: NOTIFICATION LISTENER
        // ====================================================================
        if (mode.equals("full")) {
            // Check Event Text for "Photo Ready"
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence t : texts) {
                    String textStr = t.toString().toLowerCase();
                    if (textStr.contains("photo ready")) {
                        performBroadcastLog("🔔 NOTIFICATION DETECTED: " + textStr);
                        
                        // METHOD 1: Fire the Intent directly (Most Reliable)
                        Parcelable data = event.getParcelableData();
                        if (data instanceof Notification) {
                            try {
                                ((Notification) data).contentIntent.send();
                                performBroadcastLog("🚀 Notification Opened via Intent.");
                                currentState = STATE_WAITING_FOR_SHARE_SHEET;
                                return;
                            } catch (Exception e) {
                                performBroadcastLog("❌ Intent Failed: " + e.getMessage());
                            }
                        }
                        
                        // METHOD 2: Click the Banner Text (Backup)
                        if (root != null) {
                             if (scanAndClick(root, "Photo Ready")) {
                                 performBroadcastLog("👆 Clicked Banner Text.");
                                 currentState = STATE_WAITING_FOR_SHARE_SHEET;
                                 return;
                             }
                        }
                    }
                }
            }

            // SHARE SHEET LOGIC (Full Auto Only)
            if (currentState == STATE_WAITING_FOR_SHARE_SHEET && root != null) {
                 // 1. Clone
                if (scanAndClick(root, "WhatsApp (Clone)")) {
                    performBroadcastLog("✅ Selected Clone.");
                    currentState = STATE_SEARCHING_GROUP;
                    return;
                }
                // 2. Main WhatsApp
                if (!pkgName.contains("whatsapp")) {
                    if (scanAndClick(root, "WhatsApp")) {
                        performBroadcastLog("👆 Clicked WhatsApp. Waiting for Clone Dialog...");
                        // Stay in this state
                        return;
                    }
                }
            }
        }

        // ====================================================================
        // SEMI-AUTOMATIC & FULL: WHATSAPP HANDLING
        // ====================================================================
        if (pkgName.contains("whatsapp")) {
            
            // SEMI-AUTO WAKE UP TRIGGER
            // If we are in Semi mode, and just entered WhatsApp, Force Search Mode.
            if (mode.equals("semi")) {
                // We check if we are seeing the "Send to..." list to confirm we are sharing
                if (currentState == STATE_IDLE) {
                    performBroadcastLog("⚡ Semi-Auto: WhatsApp Detected. Starting Search...");
                    currentState = STATE_SEARCHING_GROUP;
                }
            }

            // EXECUTE SEARCH
            if (currentState == STATE_SEARCHING_GROUP) {
                if (root == null) return;
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");
                
                if (targetGroup.isEmpty()) {
                    performBroadcastLog("⚠️ Error: No Group Name Saved!");
                    return;
                }

                // Try finding the group
                if (scanAndClick(root, targetGroup)) {
                    performBroadcastLog("✅ Found Group: " + targetGroup);
                    currentState = STATE_CLICKING_SEND; // Move to next step
                    return;
                }

                // Scroll and retry
                performBroadcastLog("🔎 Searching for group...");
                performScroll(root);
            }

            // EXECUTE SEND
            else if (currentState == STATE_CLICKING_SEND) {
                if (root == null) return;
                
                boolean sent = false;
                // 1. Check Content Description
                if (scanAndClickContentDesc(root, "Send")) sent = true;
                
                // 2. Check View ID
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (!nodes.isEmpty()) {
                        performClick(nodes.get(0));
                        sent = true;
                    }
                }

                if (sent) {
                    performBroadcastLog("🚀 SENT! Job Complete.");
                    currentState = STATE_IDLE; // Reset for the next photo
                }
            }
        }
    }

    // ====================================================================
    // UTILITIES
    // ====================================================================

    private void performBroadcastLog(String msg) {
        try {
            // Log to System Out just in case Broadcast fails
            System.out.println("LUNARTAG_LOG: " + msg);
            
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            // Use Application Context to ensure stability
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        // Case insensitive search
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        // Fallback for partial matches manually
        return recursiveSearch(root, text);
    }

    private boolean recursiveSearch(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) {
            return performClick(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveSearch(node.getChild(i), text)) return true;
        }
        return false;
    }

    private boolean scanAndClickContentDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null || desc == null) return false;
        if (root.getContentDescription() != null && 
            root.getContentDescription().toString().equalsIgnoreCase(desc)) {
            return performClick(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanAndClickContentDesc(root.getChild(i), desc)) return true;
        }
        return false;
    }

    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int attempts = 0;
        while (target != null && attempts < 6) {
            if (target.isClickable()) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            target = target.getParent();
            attempts++;
        }
        return false;
    }

    private void performScroll(AccessibilityNodeInfo root) {
        if (isScrolling) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            isScrolling = true;
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 600);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo res = findScrollable(node.getChild(i));
            if (res != null) return res;
        }
        return null;
    }

    @Override
    public void onInterrupt() {
        performBroadcastLog("⚠️ Interrupted");
    }
}
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
 * 4. FIXED: "One Time Only" in Semi -> Added window event reset + retry count.
 * 5. FIXED: Group not clicking every time -> ContentDesc search + scroll retry.
 * 6. FIXED: Clone not clicked -> Label match for "(clone)".
 * 7. FIXED: Send not found/enabled -> Enabled check + text fallback.
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
    private int scrollRetries = 0; // FIXED: Track retries to avoid infinite scroll

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
        scrollRetries = 0; // FIXED: Reset retries on start

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
                // Don't reset if we are just transitioning to share sheet or system resolver
                if (!pkgName.equals("android") && !pkgName.contains("launcher") && !pkgName.contains("resolver")) {
                     currentState = STATE_IDLE; 
                     scrollRetries = 0; // FIXED: Reset retries on package change
                }
            }
            lastPackageName = pkgName;
        }

        // FIXED: Reset state on window content change (for WhatsApp internal events)
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED && currentState != STATE_IDLE) {
            performBroadcastLog("🔄 Window changed → Resetting state for retry");
            currentState = STATE_IDLE;
            scrollRetries = 0;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // ====================================================================
        // FULL AUTOMATIC: CLONE SELECTOR (DIRECT LAUNCH HANDLING)
        // ====================================================================
        if (mode.equals("full")) {

            // NOTE: The AlarmReceiver has already fired the Direct Intent.
            // We are now looking at the System Dialog showing "Original vs Clone".

            if (!pkgName.contains("whatsapp") && root != null) {
                 // Search for "WhatsApp" text in the dialog
                 List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText("WhatsApp");

                 if (nodes != null && !nodes.isEmpty()) {
                     // FIXED CLONE DETECTION: Search for "(clone)" label instead of index
                     boolean clickedClone = false;
                     for (AccessibilityNodeInfo node : nodes) {
                         String label = "";
                         if (node.getText() != null) label = node.getText().toString();
                         else if (node.getContentDescription() != null) label = node.getContentDescription().toString();
                         if (label.toLowerCase().contains("(clone)")) {
                             performBroadcastLog("✅ Full Auto: Found WhatsApp (clone). Clicking...");
                             performClick(node);
                             clickedClone = true;
                             break;
                         }
                     }
                     if (!clickedClone) {
                         // Fallback to old logic if no "(clone)" found
                         if (nodes.size() >= 2) {
                             performBroadcastLog("✅ Full Auto: 2 WhatsApps Found. Clicking Index 1 (Clone)...");
                             performClick(nodes.get(1)); // Click the second one
                         } else if (nodes.size() == 1) {
                             performBroadcastLog("✅ Full Auto: 1 WhatsApp Found. Clicking Index 0...");
                             performClick(nodes.get(0));
                         }
                     }
                     currentState = STATE_SEARCHING_GROUP;
                     return;
                 }
            }
        }

        // ====================================================================
        // SEMI-AUTOMATIC & FULL: WHATSAPP HANDLING (UNTOUCHED)
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
                if (root == null) {
                    performBroadcastLog("⚠️ Root null in WhatsApp - retrying...");
                    return;
                }
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

                if (targetGroup.isEmpty()) {
                    performBroadcastLog("⚠️ Error: No Group Name Saved!");
                    return;
                }

                // FIXED: Debug log for root
                performBroadcastLog("🔍 DEBUG: Searching for '" + targetGroup + "' in root with " + root.getChildCount() + " children.");

                // Try finding the group
                if (scanAndClick(root, targetGroup)) {
                    performBroadcastLog("✅ Found Group: " + targetGroup);
                    currentState = STATE_CLICKING_SEND; // Move to next step
                    scrollRetries = 0; // FIXED: Reset retries on success
                    return;
                }

                // FIXED: Scroll and retry with limit (avoid infinite)
                if (scrollRetries < 5) { // Max 5 scrolls
                    performBroadcastLog("🔎 Scroll retry " + (scrollRetries + 1) + "/5 for group...");
                    performScroll(root);
                    scrollRetries++;
                } else {
                    performBroadcastLog("⚠️ Max scrolls reached - resetting for next attempt");
                    currentState = STATE_IDLE;
                    scrollRetries = 0;
                }
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
                    if (!nodes.isEmpty() && nodes.get(0).isEnabled()) { // FIXED: Check enabled
                        performClick(nodes.get(0));
                        sent = true;
                    } else {
                        // FIXED: Fallback search for "Send" text if ID fails
                        List<AccessibilityNodeInfo> fallbackNodes = root.findAccessibilityNodeInfosByText("Send");
                        if (!fallbackNodes.isEmpty()) {
                            performClick(fallbackNodes.get(0));
                            sent = true;
                        }
                    }
                }

                if (sent) {
                    performBroadcastLog("🚀 SENT! Job Complete.");
                    currentState = STATE_IDLE; // Reset for the next photo
                    scrollRetries = 0; // FIXED: Reset retries
                } else {
                    performBroadcastLog("⚠️ DEBUG: Send button not found or not enabled.");
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
        // FIXED: Check BOTH text AND contentDescription (2025 WhatsApp groups use contentDesc)
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) {
            performBroadcastLog("✅ DEBUG: Group found in text → Clicking.");
            return performClick(node);
        }
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(text.toLowerCase())) {
            performBroadcastLog("✅ DEBUG: Group found in contentDescription → Clicking.");
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
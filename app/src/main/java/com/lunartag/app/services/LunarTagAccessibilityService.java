package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    // STATES
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isScrolling = false;
    private boolean isClickingPending = false; 

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK; 
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 0; 
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS | 
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);
        
        // Start Red Light Service
        try {
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentState = STATE_IDLE;
        performBroadcastLog("🔴 ROBOT ONLINE. AGGRESSIVE MODE.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        
        // IGNORE KEYBOARD ONLY
        String pkgName = event.getPackageName().toString().toLowerCase();
        if (pkgName.contains("inputmethod")) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        
        // READ SETTINGS
        String targetAppName = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp(Clone)");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

        // IF WAITING FOR RED LIGHT BLINK, FREEZE
        if (isClickingPending) return;

        // ====================================================================
        // LOGIC: SEARCH EVERYWHERE (NO SAFETY CHECKS)
        // ====================================================================

        // 1. ALWAYS SCAN FOR TARGET APP (SHARE SHEET LOGIC)
        // We do not check for "Share" or "Cancel". We just look for the icon text.
        // This runs on EVERY screen, ensuring we catch the share sheet instantly.
        if (findMarkerAndClick(root, targetAppName, true)) {
            performBroadcastLog("✅ Found App '" + targetAppName + "'. RED LIGHT + CLICK.");
            currentState = STATE_SEARCHING_GROUP;
            return; // Stop processing this event if we found the app
        }

        // 2. ALWAYS SCAN FOR "SEND TO" (WHATSAPP ENTRY DETECTION)
        if (hasText(root, "Send to")) {
             if (currentState != STATE_SEARCHING_GROUP && currentState != STATE_CLICKING_SEND) {
                 performBroadcastLog("⚡ WhatsApp Detected. Switching to Group Search.");
                 currentState = STATE_SEARCHING_GROUP;
             }
        }

        // 3. SEARCH FOR GROUP NAME
        if (currentState == STATE_SEARCHING_GROUP) {
            if (!targetGroup.isEmpty()) {
                if (findMarkerAndClick(root, targetGroup, true)) {
                    performBroadcastLog("✅ Group Found. RED LIGHT + CLICK.");
                    currentState = STATE_CLICKING_SEND;
                    return;
                }
            }
            // Scroll only if we are inside WhatsApp (checked by package or Send to)
            // to avoid scrolling the home screen randomly.
            if (pkgName.contains("whatsapp") && !isScrolling) {
                performScroll(root);
            }
        }

        // 4. SEARCH FOR SEND BUTTON
        if (currentState == STATE_CLICKING_SEND) {
            boolean found = false;
            if (findMarkerAndClickID(root, "com.whatsapp:id/conversation_send_arrow")) found = true;
            if (!found && findMarkerAndClickID(root, "com.whatsapp:id/send")) found = true;
            if (!found && findMarkerAndClick(root, "Send", false)) found = true;

            if (found) {
                performBroadcastLog("🚀 SENT! Job Done.");
                currentState = STATE_IDLE;
            }
        }
        
        // 5. FALLBACK SCROLL FOR SHARE SHEET (FULL AUTO ONLY)
        // If we are in Full Auto, and we see "Cancel" (Share Sheet) but NOT the app, Scroll.
        if (mode.equals("full") && hasText(root, "Cancel") && !isScrolling) {
             // Double check we aren't in WhatsApp before scrolling the share sheet
             if (!pkgName.contains("whatsapp")) {
                 performScroll(root);
             }
        }
    }

    // ====================================================================
    // UTILITIES
    // ====================================================================

    private String cleanText(String input) {
        if (input == null) return "";
        // Remove spaces, newlines, brackets () to fix "WhatsApp(Clone )" mismatch
        return input.toLowerCase()
                .replace(" ", "")
                .replace("\n", "")
                .replace("(", "")
                .replace(")", "")
                .trim();
    }

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        String target = cleanText(text);
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(target); // Android basic search
        if (nodes != null && !nodes.isEmpty()) return true;
        return recursiveCheckText(root, target); // Deep search
    }

    private boolean recursiveCheckText(AccessibilityNodeInfo node, String targetClean) {
        if (node == null) return false;
        
        CharSequence nodeText = node.getText();
        CharSequence nodeDesc = node.getContentDescription();
        
        if (nodeText != null && cleanText(nodeText.toString()).contains(targetClean)) return true;
        if (nodeDesc != null && cleanText(nodeDesc.toString()).contains(targetClean)) return true;
        
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveCheckText(node.getChild(i), targetClean)) return true;
        }
        return false;
    }

    private boolean findMarkerAndClick(AccessibilityNodeInfo root, String text, boolean isTextSearch) {
        if (root == null || text == null || text.isEmpty()) return false;
        
        // 1. FAST SEARCH (Android API)
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || node.getParent().isClickable()) {
                    executeVisualClick(node);
                    return true;
                }
            }
        }
        // 2. DEEP SEARCH (Recursive Clean Match)
        return recursiveSearchAndClick(root, text);
    }

    private boolean findMarkerAndClickID(AccessibilityNodeInfo root, String viewId) {
        if (root == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId(viewId);
        if (nodes != null && !nodes.isEmpty()) {
            executeVisualClick(nodes.get(0));
            return true;
        }
        return false;
    }

    private boolean recursiveSearchAndClick(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        boolean match = false;
        
        String targetClean = cleanText(text);
        
        // Check Text
        if (node.getText() != null) {
            if (cleanText(node.getText().toString()).contains(targetClean)) match = true;
        }
        // Check Content Description
        if (!match && node.getContentDescription() != null) {
            if (cleanText(node.getContentDescription().toString()).contains(targetClean)) match = true;
        }
        
        if (match) {
            AccessibilityNodeInfo clickable = node;
            while (clickable != null && !clickable.isClickable()) {
                clickable = clickable.getParent();
            }
            if (clickable != null) {
                executeVisualClick(clickable);
                return true;
            }
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveSearchAndClick(node.getChild(i), text)) return true;
        }
        return false;
    }

    private void executeVisualClick(AccessibilityNodeInfo node) {
        if (isClickingPending) return;
        isClickingPending = true;

        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);

        // SHOW RED LIGHT
        if (OverlayService.getInstance() != null) {
            OverlayService.getInstance().showMarkerAt(bounds);
        }

        // WAIT THEN CLICK
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            performClick(node);
            isClickingPending = false;
        }, 500); 
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
            new Handler(Looper.getMainLooper()).postDelayed(() -> isScrolling = false, 800);
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

    private void performBroadcastLog(String msg) {
        try {
            System.out.println("LUNARTAG_LOG: " + msg);
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            getApplicationContext().sendBroadcast(intent);
        } catch (Exception e) {}
    }

    @Override
    public void onInterrupt() {
        currentState = STATE_IDLE;
        if (OverlayService.getInstance() != null) OverlayService.getInstance().hideMarker();
    }
}
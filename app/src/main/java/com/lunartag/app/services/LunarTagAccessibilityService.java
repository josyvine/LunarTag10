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
        
        try {
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentState = STATE_IDLE;
        performBroadcastLog("🔴 ROBOT ONLINE. SMART CONTEXT ACTIVE.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        
        String pkgName = event.getPackageName().toString().toLowerCase();
        if (pkgName.contains("inputmethod") || pkgName.contains("systemui")) return;

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetAppName = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp(Clone)");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

        if (isClickingPending) return;

        // ====================================================================
        // 1. CONTEXT VALIDATION (THE SMART LOCK)
        // ====================================================================
        // We scan the screen for keywords that prove we are in a "Share" or "Send" flow.
        // If these words are missing, we assume we are on the Home Screen and DO NOTHING.
        
        boolean isShareSheet = hasText(root, "Share") || hasText(root, "Apps") || 
                               hasText(root, "Complete action") || hasText(root, "Nearby");
                               
        boolean isWhatsApp = pkgName.contains("whatsapp") || 
                             hasText(root, "Send to") || hasText(root, "Recent chats");

        boolean isMyApp = pkgName.contains("lunartag");

        // SAFETY CHECK: If it's not an App we know, and it doesn't look like a Share Sheet...
        if (!isShareSheet && !isWhatsApp && !isMyApp) {
            if (currentState != STATE_IDLE) {
                currentState = STATE_IDLE;
                if (OverlayService.getInstance() != null) OverlayService.getInstance().hideMarker();
            }
            return; // STOP. Do not click random icons on Home Screen.
        }

        // ====================================================================
        // 2. FULL AUTO: SHARE SHEET LOGIC
        // ====================================================================
        if (mode.equals("full") && isShareSheet) {
            
            if (currentState == STATE_IDLE) currentState = STATE_SEARCHING_SHARE_SHEET;

            if (currentState == STATE_SEARCHING_SHARE_SHEET) {
                // Try to find target (e.g. "WhatsApp(Clone)")
                if (findMarkerAndClick(root, targetAppName, true)) {
                    performBroadcastLog("✅ Share Sheet: Found '" + targetAppName + "'. Clicking...");
                    currentState = STATE_SEARCHING_GROUP;
                } else {
                    // Only scroll if we are definitely in a list
                    if (!isScrolling) performScroll(root);
                }
            }
        }

        // ====================================================================
        // 3. WHATSAPP LOGIC
        // ====================================================================
        if (isWhatsApp) {
            
            // A. TRIGGER
            if (hasText(root, "Send to")) {
                 if (currentState == STATE_IDLE || currentState == STATE_SEARCHING_SHARE_SHEET) {
                     performBroadcastLog("⚡ WhatsApp Detected. Searching Group...");
                     currentState = STATE_SEARCHING_GROUP;
                 }
            }

            // B. SEARCH GROUP
            if (currentState == STATE_SEARCHING_GROUP) {
                if (targetGroup.isEmpty()) return;

                if (findMarkerAndClick(root, targetGroup, true)) {
                    performBroadcastLog("✅ Found Group. Blinking...");
                    currentState = STATE_CLICKING_SEND;
                    return;
                }

                if (!isScrolling) performScroll(root);
            }

            // C. CLICK SEND
            else if (currentState == STATE_CLICKING_SEND) {
                boolean found = false;
                // Try visual IDs
                if (findMarkerAndClickID(root, "com.whatsapp:id/conversation_send_arrow")) found = true;
                if (!found && findMarkerAndClickID(root, "com.whatsapp:id/send")) found = true;
                
                // Try Text (Fallback)
                if (!found && findMarkerAndClick(root, "Send", false)) found = true;

                if (found) {
                    performBroadcastLog("🚀 SENT! Job Done.");
                    currentState = STATE_IDLE;
                }
            }
        }
    }

    // ====================================================================
    // UTILITIES
    // ====================================================================

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        return nodes != null && !nodes.isEmpty();
    }

    private boolean findMarkerAndClick(AccessibilityNodeInfo root, String text, boolean isTextSearch) {
        if (root == null || text == null || text.isEmpty()) return false;
        
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (node.isClickable() || node.getParent().isClickable()) {
                    executeVisualClick(node);
                    return true;
                }
            }
        }
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
        
        String cleanTarget = text.toLowerCase().replace(" ", "");
        
        if (node.getText() != null) {
            String nodeText = node.getText().toString().toLowerCase().replace(" ", "");
            if (nodeText.contains(cleanTarget)) match = true;
        }
        
        if (!match && node.getContentDescription() != null) {
            String desc = node.getContentDescription().toString().toLowerCase().replace(" ", "");
            if (desc.contains(cleanTarget)) match = true;
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

        if (OverlayService.getInstance() != null) {
            OverlayService.getInstance().showMarkerAt(bounds);
        }

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
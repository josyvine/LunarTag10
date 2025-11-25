package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_AUTO_MODE = "automation_mode"; 
    private static final String KEY_TARGET_GROUP = "target_group_name";
    
    // Coordinates
    private static final String KEY_ICON_X = "share_icon_x";
    private static final String KEY_ICON_Y = "share_icon_y";

    // JOB CONTROL TOKENS
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_FORCE_RESET = "force_reset_logic";

    // STATES
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isClickingPending = false; 
    
    // TIMEOUT HANDLER (Fixes Zombie State)
    private final Handler timeoutHandler = new Handler(Looper.getMainLooper());
    private final Runnable resetRunnable = () -> {
        if (currentState != STATE_IDLE) {
            performBroadcastLog("⚠️ TIMEOUT: Robot stuck. Forcing Reset.");
            currentState = STATE_IDLE;
            isClickingPending = false;
        }
    };

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

        // Force Start Overlay
        try {
            Intent intent = new Intent(this, OverlayService.class);
            startService(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }

        currentState = STATE_IDLE;
        performBroadcastLog("🔴 ROBOT ONLINE. CHROME BLOCKER ACTIVE.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkgName = event.getPackageName().toString().toLowerCase();

        // ====================================================================
        // 1. STRICT SECURITY FILTER (Fixes Chrome Clicking)
        // ====================================================================
        // If it is NOT WhatsApp, and NOT Android System, STOP IMMEDIATELY.
        boolean isSafePackage = pkgName.contains("whatsapp") || 
                                pkgName.equals("android") || 
                                pkgName.contains("chooser") || 
                                pkgName.contains("systemui");

        if (!isSafePackage) {
            return; // IGNORE Chrome, YouTube, etc.
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");

        // ====================================================================
        // 2. BRAIN WIPE (From SendService)
        // ====================================================================
        if (prefs.getBoolean(KEY_FORCE_RESET, false)) {
            currentState = STATE_IDLE;
            isClickingPending = false;
            prefs.edit().putBoolean(KEY_FORCE_RESET, false).apply();
            performBroadcastLog("🔄 NEW JOB: Memory Wiped.");
            
            // Start a safety timer. If job isn't done in 15 seconds, reset.
            timeoutHandler.removeCallbacks(resetRunnable);
            timeoutHandler.postDelayed(resetRunnable, 15000);
        }

        if (root == null) return;
        if (isClickingPending) return;

        // ====================================================================
        // 3. SHARE SHEET LOGIC (Coordinate Click)
        // ====================================================================
        // STRICT TRIGGER: Only "Cancel" text OR "android" package
        boolean isShareSheet = hasText(root, "Cancel") || pkgName.equals("android") || pkgName.contains("chooser");

        if (mode.equals("full") && isShareSheet && !pkgName.contains("whatsapp")) {

            // JOB TOKEN CHECK
            if (!prefs.getBoolean(KEY_JOB_PENDING, false)) {
                return; // No ticket, no click.
            }

            int x = prefs.getInt(KEY_ICON_X, 0);
            int y = prefs.getInt(KEY_ICON_Y, 0);

            if (x > 0 && y > 0) {
                // A. VISUAL BLINK
                if (OverlayService.getInstance() != null) {
                    OverlayService.getInstance().showMarkerAtCoordinate(x, y);
                }

                // B. DELAYED CLICK (Fixes "Blink but No Click")
                // Wait 500ms for animation to stop, THEN click.
                performBroadcastLog("✅ Share Sheet. Waiting 500ms to click...");
                isClickingPending = true;
                
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    performBroadcastLog("👉 FIRING CLICK at " + x + "," + y);
                    dispatchGesture(createClickGesture(x, y), null, null);
                    
                    // DISABLE TOKEN IMMEDIATELY
                    prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                    
                    currentState = STATE_SEARCHING_GROUP;
                    isClickingPending = false; 
                }, 500); // <--- 500ms DELAY ADDED HERE

                return;
            }
        }

        // ====================================================================
        // 4. WHATSAPP LOGIC (Text Logic)
        // ====================================================================
        // STRICT CHECK: ONLY RUN THIS IF PACKAGE IS WHATSAPP
        if (pkgName.contains("whatsapp")) {

            if (currentState == STATE_IDLE || currentState == STATE_SEARCHING_SHARE_SHEET) {
                currentState = STATE_SEARCHING_GROUP;
            }

            // SEARCH FOR GROUP
            if (currentState == STATE_SEARCHING_GROUP) {
                if (targetGroup.isEmpty()) return;

                if (findMarkerAndClick(root, targetGroup, true)) {
                    performBroadcastLog("✅ Group Found. Clicking...");
                    currentState = STATE_CLICKING_SEND;
                    return;
                }
            }

            // CLICK SEND
            else if (currentState == STATE_CLICKING_SEND) {
                boolean found = false;
                if (findMarkerAndClickID(root, "com.whatsapp:id/conversation_send_arrow")) found = true;
                if (!found && findMarkerAndClickID(root, "com.whatsapp:id/send")) found = true;
                if (!found && findMarkerAndClick(root, "Send", false)) found = true;

                if (found) {
                    performBroadcastLog("🚀 SENT! Job Done.");
                    
                    // SUCCESS RESET
                    currentState = STATE_IDLE;
                    prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                    timeoutHandler.removeCallbacks(resetRunnable); // Stop the safety timer

                    new Handler(Looper.getMainLooper()).post(() -> 
                        Toast.makeText(getApplicationContext(), "🚀 JOB DONE", Toast.LENGTH_SHORT).show());
                }
            }
        }
    }

    // ====================================================================
    // UTILITIES
    // ====================================================================

    private GestureDescription createClickGesture(int x, int y) {
        Path clickPath = new Path();
        clickPath.moveTo(x, y);
        // Hold for 70ms to register firmly
        GestureDescription.StrokeDescription clickStroke = 
                new GestureDescription.StrokeDescription(clickPath, 0, 70);
        GestureDescription.Builder clickBuilder = new GestureDescription.Builder();
        clickBuilder.addStroke(clickStroke);
        return clickBuilder.build();
    }

    private boolean hasText(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        String cleanTarget = cleanString(text);
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(cleanTarget);
        if (nodes != null && !nodes.isEmpty()) return true;
        return recursiveCheckText(root, cleanTarget);
    }

    private boolean recursiveCheckText(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && cleanString(node.getText().toString()).contains(text)) return true;
        if (node.getContentDescription() != null && cleanString(node.getContentDescription().toString()).contains(text)) return true;
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveCheckText(node.getChild(i), text)) return true;
        }
        return false;
    }

    // Helper for WhatsApp Internal Logic
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
        String cleanTarget = cleanString(text);
        if (node.getText() != null && cleanString(node.getText().toString()).contains(cleanTarget)) match = true;
        if (!match && node.getContentDescription() != null && cleanString(node.getContentDescription().toString()).contains(cleanTarget)) match = true;

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

    private String cleanString(String input) {
        if (input == null) return "";
        return input.toLowerCase().replace(" ", "").replace("\n", "").trim();
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
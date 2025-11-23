package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
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

    // STATES
    private static final int STATE_IDLE = 0;
    private static final int STATE_SEARCHING_SHARE_SHEET = 1;
    private static final int STATE_SEARCHING_GROUP = 2;
    private static final int STATE_CLICKING_SEND = 3;

    private int currentState = STATE_IDLE;
    private boolean isScrolling = false;
    
    // SOURCE TRACKING
    private String previousAppPackage = ""; 

    // AI HELPER
    private AiClicker aiClicker;

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
        
        // INITIALIZE THE AI EYES
        aiClicker = new AiClicker(this);

        currentState = STATE_IDLE;
        performBroadcastLog("🤖 ROBOT ONLINE. AI READY.");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null) return;
        String pkgName = event.getPackageName().toString().toLowerCase();

        // 1. IGNORE SYSTEM NOISE
        if (pkgName.contains("inputmethod") || pkgName.contains("systemui")) return;

        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        AccessibilityNodeInfo root = getRootInActiveWindow();

        // 2. DEFINE TERRITORY
        boolean isWhatsApp = pkgName.contains("whatsapp");
        boolean isShareSheet = pkgName.equals("android") || pkgName.contains("ui") || pkgName.contains("resolver");
        boolean isMyApp = pkgName.contains("lunartag");

        // 3. PERSONAL SAFETY (SOURCE TRACKING)
        if (!isWhatsApp && !isShareSheet) {
            previousAppPackage = pkgName;
            // If user switches to a foreign app, STOP.
            if (!isMyApp && currentState != STATE_IDLE) {
                performBroadcastLog("🛑 Switched App. Robot Stopped.");
                currentState = STATE_IDLE;
            }
            return; 
        }

        // ====================================================================
        // 4. FULL AUTOMATIC: SHARE SHEET (AI POWERED)
        // ====================================================================
        if (mode.equals("full") && isShareSheet) {
            
            if (currentState == STATE_IDLE) currentState = STATE_SEARCHING_SHARE_SHEET;

            if (currentState == STATE_SEARCHING_SHARE_SHEET) {
                // CHECK ANDROID VERSION (AI needs Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    
                    // USE AI VISION
                    aiClicker.scanAndClickVisual("Clone", (success) -> {
                        if (success) {
                            performBroadcastLog("✅ AI: Saw 'Clone' and Clicked.");
                            currentState = STATE_SEARCHING_GROUP;
                        } else {
                            // AI didn't see it. It must be hidden. SCROLL.
                            if (!isScrolling) {
                                performBroadcastLog("📜 AI didn't see target. Scrolling...");
                                performScroll(root);
                            }
                        }
                    });

                } else {
                    // FALLBACK FOR OLD PHONES (Standard Logic)
                    if (scanAndClick(root, "Clone")) {
                        currentState = STATE_SEARCHING_GROUP;
                    } else if (!isScrolling) {
                        performScroll(root);
                    }
                }
            }
        }

        // ====================================================================
        // 5. WHATSAPP LOGIC (HYBRID)
        // ====================================================================
        if (isWhatsApp) {
            
            // PERSONAL SAFETY CHECK
            if (previousAppPackage.contains("launcher") || previousAppPackage.contains("home")) {
                if (currentState == STATE_IDLE) return; 
            }

            if (root == null) return;

            // A. TRIGGER: "SEND TO..." (Text detection is faster than AI for triggers)
            List<AccessibilityNodeInfo> headers = root.findAccessibilityNodeInfosByText("Send to");
            if (headers != null && !headers.isEmpty()) {
                 if (currentState == STATE_IDLE || currentState == STATE_SEARCHING_SHARE_SHEET) {
                     performBroadcastLog("⚡ 'Send to' detected. AI Search Started.");
                     currentState = STATE_SEARCHING_GROUP;
                 }
            }

            // B. SEARCH GROUP (Standard - You confirmed this works)
            if (currentState == STATE_SEARCHING_GROUP) {
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");
                if (targetGroup.isEmpty()) return;

                if (scanAndClick(root, targetGroup)) {
                    performBroadcastLog("✅ Found Group: " + targetGroup);
                    currentState = STATE_CLICKING_SEND;
                    return;
                }

                if (!isScrolling) performScroll(root);
            }

            // C. CLICK SEND (FIXED: Uses View IDs, NOT AI)
            else if (currentState == STATE_CLICKING_SEND) {
                boolean sent = false;

                // 1. Try Content Description (Standard)
                if (scanAndClickContentDesc(root, "Send")) sent = true;

                // 2. Try Standard ID (Old WhatsApp)
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (!nodes.isEmpty()) { 
                        performClick(nodes.get(0)); 
                        sent = true; 
                    }
                }

                // 3. Try New ID (New WhatsApp 2024/2025)
                // This is likely why it failed before. The ID changed.
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/conversation_send_arrow");
                    if (!nodes.isEmpty()) { 
                        performClick(nodes.get(0)); 
                        sent = true; 
                    }
                }

                if (sent) {
                    performBroadcastLog("🚀 SENT! Job Done.");
                    currentState = STATE_IDLE;
                }
            }
        }
    }

    // ====================================================================
    // STANDARD UTILITIES
    // ====================================================================

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        return recursiveSearch(root, text);
    }

    private boolean recursiveSearch(AccessibilityNodeInfo node, String text) {
        if (node == null) return false;
        if (node.getText() != null && node.getText().toString().toLowerCase().contains(text.toLowerCase())) {
            return performClick(node);
        }
        if (node.getContentDescription() != null && node.getContentDescription().toString().toLowerCase().contains(text.toLowerCase())) {
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
            // VISUAL WAIT: Essential for AI to have time to see the new screen
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
    }
}
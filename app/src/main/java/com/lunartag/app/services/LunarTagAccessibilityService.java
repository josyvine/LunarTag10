package com.lunartag.app.services;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String TAG = "LunarTagRobot";

    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode";
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    private boolean isScrolling = false;
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS |
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS |
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        showDebugToast("LunarTag Robot Ready & Waiting...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        boolean isJobPending = prefs.getBoolean(KEY_JOB_PENDING, false);
        if (!isJobPending) return;

        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetGroupName = prefs.getString(KEY_TARGET_GROUP, "").trim();
        String targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp");

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return;
        }

        AccessibilityNodeInfo rootNode = getRootInActiveWindow();
        if (rootNode == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString().toLowerCase() : "";

        // FULL AUTO: Click our notification
        if (mode.equals("full") && eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            if (packageName.contains(getPackageName())) {
                Parcelable data = event.getParcelableData();
                if (data instanceof Notification) {
                    Notification notification = (Notification) data;
                    if (notification.contentIntent != null) {
                        try {
                            notification.contentIntent.send();
                            showDebugToast("Full Auto: Notification Clicked");
                            return;
                        } catch (Exception e) {
                            showDebugToast("Notif click failed");
                        }
                    }
                }
            }
        }

        // SHARE SHEET: Find WhatsApp or WhatsApp (Clone)
        if (!packageName.contains("whatsapp")) {
            boolean clicked = scanAndClick(rootNode, targetAppLabel);

            if (!clicked && targetAppLabel.toLowerCase().contains("clone")) {
                clicked = scanAndClick(rootNode, "WhatsApp");
                if (clicked) showDebugToast("Clone not found → Clicked main WhatsApp");
            }

            if (clicked) {
                showDebugToast("WhatsApp Selected");
                return;
            }

            // Only scroll if not found
            performSmartScroll(rootNode);
            return;
        }

        // WHATSAPP OPENED
        if (packageName.contains("whatsapp")) {

            // 1. SEND BUTTON FIRST
            if (scanAndClickContentDesc(rootNode, "Send")) {
                showDebugToast("SENT! Job Complete.");
                prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                return;
            }

            // 2. Already in chat? Wait
            if (findNodeById(rootNode, "com.whatsapp:id/entry") != null) {
                showDebugToast("In chat. Type message & send manually");
                return;
            }

            // 3. Find group
            if (!targetGroupName.isEmpty()) {
                if (scanAndClick(rootNode, targetGroupName)) {
                    showDebugToast("Group Found & Clicked: " + targetGroupName);
                    return;
                }

                if (scanListItemsManually(rootNode, targetGroupName)) {
                    showDebugToast("Manual Scan Found: " + targetGroupName);
                    return;
                }

                // 4. Scroll only if not found
                performSmartScroll(rootNode);
            }
        }
    }

    private void performSmartScroll(AccessibilityNodeInfo root) {
        if (isScrolling) return;
        if (System.currentTimeMillis() % 2000 < 1000) return; // Simple debounce

        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        if (scrollable != null) {
            isScrolling = true;
            showDebugToast("Scrolling down...");
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);

            handler.postDelayed(() -> isScrolling = false, 1600);
        }
    }

    // YOUR ORIGINAL GENIUS HELPERS — 100% PRESERVED
    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (tryClickingHierarchy(node)) return true;
            }
        }
        return false;
    }

    private boolean scanAndClickContentDesc(AccessibilityNodeInfo root, String desc) {
        if (root == null) return false;
        if (root.getContentDescription() != null &&
            root.getContentDescription().toString().equalsIgnoreCase(desc)) {
            return tryClickingHierarchy(root);
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanAndClickContentDesc(root.getChild(i), desc)) return true;
        }
        return false;
    }

    private boolean scanListItemsManually(AccessibilityNodeInfo root, String targetText) {
        if (root == null) return false;

        if (root.getClassName() != null &&
           (root.getClassName().toString().contains("RecyclerView") ||
            root.getClassName().toString().contains("ListView"))) {

            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo child = root.getChild(i);
                if (child != null && recursiveTextCheck(child, targetText)) {
                    return true;
                }
            }
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            if (scanListItemsManually(root.getChild(i), targetText)) return true;
        }
        return false;
    }

    private boolean recursiveTextCheck(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;

        if (node.getText() != null &&
            node.getText().toString().toLowerCase().contains(target.toLowerCase())) {
            return tryClickingHierarchy(node);
        }

        if (node.getContentDescription() != null &&
            node.getContentDescription().toString().toLowerCase().contains(target.toLowerCase())) {
            return tryClickingHierarchy(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveTextCheck(node.getChild(i), target)) return true;
        }
        return false;
    }

    private boolean tryClickingHierarchy(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int depth = 0;
        while (target != null && depth < 7) {
            if (target.isClickable()) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            target = target.getParent();
            depth++;
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo result = findScrollableNode(node.getChild(i));
            if (result != null) return result;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeById(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
        return list != null && !list.isEmpty() ? list.get(0) : null;
    }

    private void showDebugToast(String message) {
        handler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {
        isScrolling = false;
    }
}
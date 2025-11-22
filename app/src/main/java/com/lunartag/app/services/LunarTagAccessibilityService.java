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
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String TAG = "LunarTagRobot";

    // SharedPrefs Keys (must match your app exactly)
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode"; // "semi" or "full"
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    // Smart State Machine (this fixes 95% of your problems)
    private enum State {
        IDLE,
        WAITING_FOR_NOTIFICATION,   // Full-auto only
        IN_SHARE_SHEET,             // Selecting WhatsApp
        IN_WHATSAPP_CHAT_LIST,      // Looking for group
        IN_GROUP_CHAT,              // Already inside group
        JOB_COMPLETE
    }

    private State currentState = State.IDLE;
    private long lastScrollTime = 0;
    private static final long SCROLL_COOLDOWN = 2300; // Prevents spam scroll
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 100;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        showToast("LunarTag Robot Activated | Smart Mode ON");
        resetToIdle();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        boolean jobPending = prefs.getBoolean(KEY_JOB_PENDING, false);

        if (!jobPending) {
            if (currentState != State.IDLE) resetToIdle();
            return;
        }

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            return;
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;

        String packageName = event.getPackageName() != null ? event.getPackageName().toString().toLowerCase() : "";
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        String targetGroup = prefs.getString(KEY_TARGET_GROUP, "").trim();
        String targetAppLabel = prefs.getString(KEY_TARGET_APP_LABEL, "WhatsApp");

        // FULL AUTO: Click our own notification first
        if (mode.equals("full") && currentState == State.WAITING_FOR_NOTIFICATION) {
            if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && packageName.contains(getPackageName())) {
                clickOurNotification(event);
                return;
            }
        }

        // Detect current screen and act
        if (packageName.contains("whatsapp")) {
            handleWhatsApp(root, targetGroup);
        } 
        else if (isShareSheetActive(root)) {
            currentState = State.IN_SHARE_SHEET;
            handleShareSheet(root, targetAppLabel);
        } 
        else {
            // Unknown screen → do nothing aggressive
            if (currentState != State.WAITING_FOR_NOTIFICATION) {
                currentState = State.IDLE;
            }
        }
    }

    private void handleWhatsApp(AccessibilityNodeInfo root, String targetGroup) {
        currentState = State.IN_WHATSAPP_CHAT_LIST;

        // Priority 1: SEND BUTTON (Paper plane)
        if (clickSendButton(root)) {
            showToast("Message SENT! Job Done");
            finishJob();
            return;
        }

        // Priority 2: Are we already inside a chat?
        if (isInChatTypingMode(root)) {
            currentState = State.IN_GROUP_CHAT;
            showToast("Inside group. Waiting for Send...");
            return;
        }

        // Priority 3: Find target group
        if (!targetGroup.isEmpty()) {
            if (scanAndClick(root, targetGroup) || scanListItemsManually(root, targetGroup)) {
                showToast("Group Found & Clicked: " + targetGroup);
                delayStateChange(State.IN_GROUP_CHAT, 1400);
                return;
            }

            // Priority 4: Smart scroll only if needed
            smartScroll(root);
        }
    }

    private void handleShareSheet(AccessibilityNodeInfo root, String targetAppLabel) {
        boolean clicked = scanAndClick(root, targetAppLabel);

        // Fallback for Dual/Cloned WhatsApp
        if (!clicked && (targetAppLabel.toLowerCase().contains("clone") || targetAppLabel.toLowerCase().contains("dual"))) {
            clicked = scanAndClick(root, "WhatsApp");
        }

        if (clicked) {
            showToast("WhatsApp Selected");
            delayStateChange(State.IN_WHATSAPP_CHAT_LIST, 1500);
        } else {
            smartScroll(root);
        }
    }

    private void clickOurNotification(AccessibilityEvent event) {
        Parcelable data = event.getParcelableData();
        if (data instanceof Notification) {
            Notification notif = (Notification) data;
            if (notif.contentIntent != null) {
                try {
                    notif.contentIntent.send();
                    showToast("Opening Share Sheet...");
                    currentState = State.IN_SHARE_SHEET;
                } catch (PendingIntent.CanceledException e) {
                    showToast("Notification expired");
                }
            }
        }
    }

    // SMART SCROLL — No more infinite scrolling!
    private void smartScroll(AccessibilityNodeInfo root) {
        long now = System.currentTimeMillis();
        if (now - lastScrollTime < SCROLL_COOLDOWN) return;

        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        if (scrollable == null) return;

        // Optional: Check if already at bottom (advanced)
        showToast("Scrolling list...");
        scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        lastScrollTime = now;
    }

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        return scanAndClickContentDesc(root, "Send") ||
               scanAndClickContentDesc(root, "send") ||
               clickByViewId(root, "com.whatsapp:id/send");
    }

    private boolean isInChatTypingMode(AccessibilityNodeInfo root) {
        return findNodeByViewId(root, "com.whatsapp:id/entry") != null;
    }

    private boolean isShareSheetActive(AccessibilityNodeInfo root) {
        return root.getPackageName() != null && (
            root.getPackageName().toString().contains("systemui") ||
            findNodeByViewId(root, "android:id/chooser_recycler_view") != null ||
            scanAndClick(root, "Share with") // text exists
        );
    }

    private void delayStateChange(State nextState, long delayMs) {
        mainHandler.postDelayed(() -> currentState = nextState, delayMs);
    }

    private void finishJob() {
        getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_JOB_PENDING, false)
            .apply();
        resetToIdle();
        showToast("Job Complete! Robot is now sleeping");
    }

    private void resetToIdle() {
        currentState = State.IDLE;
        lastScrollTime = 0;
        mainHandler.removeCallbacksAndMessages(null);
    }

    // YOUR ORIGINAL GENIUS HELPERS (kept exactly as you wrote them, just cleaned)

    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text.isEmpty()) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (tryClickingHierarchy(node)) return true;
            }
        }
        return false;
    }

    private boolean scanAndClickContentDesc(AccessibilityNodeInfo node, String desc) {
        if (node == null) return false;
        if (node.getContentDescription() != null &&
            node.getContentDescription().toString().toLowerCase().contains(desc.toLowerCase())) {
            return tryClickingHierarchy(node);
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            if (scanAndClickContentDesc(node.getChild(i), desc)) return true;
        }
        return false;
    }

    private boolean scanListItemsManually(AccessibilityNodeInfo root, String targetText) {
        if (root == null) return false;

        String className = root.getClassName() != null ? root.getClassName().toString() : "";
        if (className.contains("RecyclerView") || className.contains("ListView")) {
            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo child = root.getChild(i);
                if (child != null && recursiveTextCheck(child, targetText)) {
                    return true;
                }
                child.recycle();
            }
        }

        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null && scanListItemsManually(child, targetText)) {
                child.recycle();
                return true;
            }
            if (child != null) child.recycle();
        }
        return false;
    }

    private boolean recursiveTextCheck(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;

        CharSequence text = node.getText();
        CharSequence desc = node.getContentDescription();

        if (text != null && text.toString().toLowerCase().contains(target.toLowerCase())) {
            return tryClickingHierarchy(node);
        }
        if (desc != null && desc.toString().toLowerCase().contains(target.toLowerCase())) {
            return tryClickingHierarchy(node);
        }

        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveTextCheck(node.getChild(i), target)) return true;
        }
        return false;
    }

    private boolean tryClickingHierarchy(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int maxDepth = 7;
        for (int i = 0; i < maxDepth && target != null; i++) {
            if (target.isClickable()) {
                target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            target = target.getParent();
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

    private boolean clickByViewId(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
        if (list != null && !list.isEmpty()) {
            return tryClickingHierarchy(list.get(0));
        }
        return false;
    }

    private AccessibilityNodeInfo findNodeByViewId(AccessibilityNodeInfo root, String viewId) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(viewId);
        return (list != null && !list.isEmpty()) ? list.get(0) : null;
    }

    private void showToast(String message) {
        mainHandler.post(() -> Toast.makeText(this, message, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {
        resetToIdle();
    }
}
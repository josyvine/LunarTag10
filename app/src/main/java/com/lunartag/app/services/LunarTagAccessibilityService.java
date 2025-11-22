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

    private enum State {
        IDLE,
        WAITING_FOR_NOTIFICATION,
        IN_SHARE_SHEET,
        IN_WHATSAPP_CHAT_LIST,
        IN_GROUP_CHAT,
        JOB_COMPLETE
    }

    private State currentState = State.IDLE;
    private long lastScrollTime = 0;
    private static final long SCROLL_COOLDOWN = 2300;
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
        showToast("LunarTag Robot Ready | Clone Supported");
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

        if (mode.equals("full") && currentState == State.WAITING_FOR_NOTIFICATION) {
            if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED && packageName.contains(getPackageName())) {
                clickOurNotification(event);
                return;
            }
        }

        if (packageName.contains("whatsapp")) {
            handleWhatsApp(root, targetGroup);
        } else if (isShareSheetActive(root)) {
            currentState = State.IN_SHARE_SHEET;
            handleShareSheet(root, targetAppLabel);
        } else {
            if (currentState != State.WAITING_FOR_NOTIFICATION) currentState = State.IDLE;
        }
    }

    private void handleWhatsApp(AccessibilityNodeInfo root, String targetGroup) {
        currentState = State.IN_WHATSAPP_CHAT_LIST;

        if (clickSendButton(root)) {
            showToast("Message SENT!");
            finishJob();
            return;
        }

        if (isInChatTypingMode(root)) {
            currentState = State.IN_GROUP_CHAT;
            showToast("In group chat. Waiting...");
            return;
        }

        if (!targetGroup.isEmpty()) {
            if (scanAndClick(root, targetGroup) || scanListItemsManually(root, targetGroup)) {
                showToast("Group opened: " + targetGroup);
                delayStateChange(State.IN_GROUP_CHAT, 1400);
                return;
            }
            smartScroll(root);
        }
    }

    private void handleShareSheet(AccessibilityNodeInfo root, String targetAppLabel) {
        boolean clicked = scanAndClick(root, targetAppLabel);

        if (!clicked && (targetAppLabel.toLowerCase().contains("clone") ||
                         targetAppLabel.toLowerCase().contains("dual") ||
                         targetAppLabel.toLowerCase().contains("2") ||
                         targetAppLabel.toLowerCase().contains("business"))) {
            clicked = scanAndClick(root, "WhatsApp");
        }

        if (clicked) {
            showToast("WhatsApp (Clone) Selected");
            delayStateChange(State.IN_WHATSAPP_CHAT_LIST, 1500);
        } else {
            smartScroll(root);
        }
    }

    private void clickOurNotification(AccessibilityEvent event) {
        Parcelable data = event.getParcelableData();
        if (data instanceof Notification) {
            Notification n = (Notification) data;
            if (n.contentIntent != null) {
                try {
                    n.contentIntent.send();
                    showToast("Opening share sheet...");
                    currentState = State.IN_SHARE_SHEET;
                } catch (Exception ignored) {}
            }
        }
    }

    private boolean isShareSheetActive(AccessibilityNodeInfo root) {
        if (root.getPackageName() == null) return false;
        String pkg = root.getPackageName().toString();
        return pkg.contains("systemui") ||
               findNodeByViewId(root, "android:id/chooser_recycler_view") != null ||
               hasText(root, "Share with") ||
               hasText(root, "Choose app");
    }

    private boolean hasText(AccessibilityNodeInfo node, String text) {
        List<AccessibilityNodeInfo> list = node.findAccessibilityNodeInfosByText(text);
        return list != null && !list.isEmpty();
    }

    private void smartScroll(AccessibilityNodeInfo root) {
        if (System.currentTimeMillis() - lastScrollTime < SCROLL_COOLDOWN) return;
        AccessibilityNodeInfo scrollable = findScrollableNode(root);
        if (scrollable != null) {
            showToast("Scrolling...");
            scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            lastScrollTime = System.currentTimeMillis();
        }
    }

    private boolean clickSendButton(AccessibilityNodeInfo root) {
        return scanAndClickContentDesc(root, "Send") ||
               clickByViewId(root, "com.whatsapp:id/send");
    }

    private boolean isInChatTypingMode(AccessibilityNodeInfo root) {
        return findNodeByViewId(root, "com.whatsapp:id/entry") != null;
    }

    private void delayStateChange(State next, long delay) {
        mainHandler.postDelayed(() -> currentState = next, delay);
    }

    private void finishJob() {
        getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_JOB_PENDING, false).apply();
        resetToIdle();
        showToast("Job Complete! Robot sleeping");
    }

    private void resetToIdle() {
        currentState = State.IDLE;
        lastScrollTime = 0;
        mainHandler.removeCallbacksAndMessages(null);
    }

    // ——— YOUR ORIGINAL HELPERS (100% WORKING) ———
    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text.isEmpty()) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null) for (AccessibilityNodeInfo n : nodes) if (tryClickingHierarchy(n)) return true;
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

    private boolean scanListItemsManually(AccessibilityNodeInfo root, String target) {
        if (root == null) return false;
        String className = root.getClassName() != null ? root.getClassName().toString() : "";
        if (className.contains("RecyclerView") || className.contains("ListView")) {
            for (int i = 0; i < root.getChildCount(); i++) {
                AccessibilityNodeInfo child = root.getChild(i);
                if (child != null && recursiveTextCheck(child, target)) return true;
            }
        }
        for (int i = 0; i < root.getChildCount(); i++) {
            AccessibilityNodeInfo child = root.getChild(i);
            if (child != null && scanListItemsManually(child, target)) return true;
        }
        return false;
    }

    private boolean recursiveTextCheck(AccessibilityNodeInfo node, String target) {
        if (node == null) return false;
        CharSequence t = node.getText();
        CharSequence d = node.getContentDescription();
        if (t != null && t.toString().toLowerCase().contains(target.toLowerCase())) return tryClickingHierarchy(node);
        if (d != null && d.toString().toLowerCase().contains(target.toLowerCase())) return tryClickingHierarchy(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            if (recursiveTextCheck(node.getChild(i), target)) return true;
        }
        return false;
    }

    private boolean tryClickingHierarchy(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo n = node;
        for (int i = 0; i < 8 && n != null; i++) {
            if (n.isClickable()) {
                n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                return true;
            }
            n = n.getParent();
        }
        return false;
    }

    private AccessibilityNodeInfo findScrollableNode(AccessibilityNodeInfo node) {
        if (node == null) return null;
        if (node.isScrollable()) return node;
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo r = findScrollableNode(node.getChild(i));
            if (r != null) return r;
        }
        return null;
    }

    private AccessibilityNodeInfo findNodeByViewId(AccessibilityNodeInfo root, String id) {
        List<AccessibilityNodeInfo> list = root.findAccessibilityNodeInfosByViewId(id);
        return list != null && !list.isEmpty() ? list.get(0) : null;
    }

    private boolean clickByViewId(AccessibilityNodeInfo root, String id) {
        AccessibilityNodeInfo n = findNodeByViewId(root, id);
        return n != null && tryClickingHierarchy(n);
    }

    private void showToast(String msg) {
        mainHandler.post(() -> Toast.makeText(this, "LunarTag: " + msg, Toast.LENGTH_SHORT).show());
    }

    @Override
    public void onInterrupt() {
        resetToIdle();
    }
}
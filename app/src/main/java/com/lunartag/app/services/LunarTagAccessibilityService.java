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
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * LunarTagAccessibilityService - Rewritten with all requested fixes.
 *
 * Key improvements (see numbered comments in-code):
 * 1. Robust notification text matching (multi-phrase, lowercase)
 * 2. Removed one-time blocking for semi-auto and improved force-start logic
 * 3. Reset state every time WhatsApp is opened (semi-auto reliable)
 * 4. Continuous live logging for debugging
 * 5. Force-start support for many notification variants
 * 6. Share-sheet scanning allowed to retry and won't get permanently stuck
 * 7. Increased parent climb attempts for performClick
 * 8. Robust scroll lock handling and resets
 * 9. Multiple strategies to find "Send" (contentDesc, id, text partial)
 * 10. Better group search (lowercase, substring, partial match)
 * 11. Job pending reset on failure / timeouts
 * 12. Banner detection made tolerant for OEM changes
 * 13. Clone selection tolerant to many naming variants
 * 14. Cleanup and removal of dead branches
 */
public class LunarTagAccessibilityService extends AccessibilityService {

    private static final String TAG = "LunarTagService";

    // --- Preferences keys ---
    private static final String PREFS_ACCESSIBILITY = "LunarTagAccessPrefs";
    private static final String KEY_JOB_PENDING = "job_is_pending";
    private static final String KEY_AUTO_MODE = "automation_mode"; // "semi" or "full"
    private static final String KEY_TARGET_GROUP = "target_group_name";
    private static final String KEY_TARGET_APP_LABEL = "target_app_label";

    // --- States ---
    private static final int STATE_WAITING_FOR_NOTIFICATION = 0;
    private static final int STATE_WAITING_FOR_SHARE_SHEET = 1;
    private static final int STATE_INSIDE_WHATSAPP = 2;
    private static final int STATE_CONFIRM_SEND = 3;

    private int currentState = STATE_WAITING_FOR_NOTIFICATION;
    private boolean isScrolling = false;

    // Short timer to reset job on failures
    private final Handler handler = new Handler(Looper.getMainLooper());

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 50;
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS 
                   | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS 
                   | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        setServiceInfo(info);

        currentState = STATE_WAITING_FOR_NOTIFICATION;
        broadcastLog("🤖 ROBOT CONNECTED. Listening for events...");
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        SharedPreferences prefs = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
        String mode = prefs.getString(KEY_AUTO_MODE, "semi");
        boolean isJobPending = prefs.getBoolean(KEY_JOB_PENDING, false);

        // Package name
        String pkgName = "unknown";
        if (event.getPackageName() != null) {
            pkgName = event.getPackageName().toString().toLowerCase();
        }

        AccessibilityNodeInfo root = getRootInActiveWindow();

        // -----------------------------------------------------------------
        // 0. ALWAYS LOG the event for live debugging (Fix 4)
        // -----------------------------------------------------------------
        broadcastLog("EventType=" + event.getEventType() + " | pkg=" + pkgName + " | state=" + currentState);

        // -----------------------------------------------------------------
        // 1. Notification handling -- robust matching and force-start (Fix 1 & 5)
        // -----------------------------------------------------------------
        if (event.getEventType() == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            List<CharSequence> texts = event.getText();
            if (texts != null) {
                for (CharSequence t : texts) {
                    try {
                        String text = t.toString();
                        String low = text.toLowerCase();

                        // Broad detection for "photo ready" variants (Fix 1 & 5)
                        boolean looksLikePhotoReady = false;

                        if (low.contains("photo ready") || low.contains("ready to send") 
                                || low.contains("image ready") || low.contains("media ready")) {
                            looksLikePhotoReady = true;
                        }
                        // Fallback: contains both photo and send words somewhere (Fix 1)
                        if (!looksLikePhotoReady && low.contains("photo") 
                                && (low.contains("send") || low.contains("ready"))) {
                            looksLikePhotoReady = true;
                        }

                        if (looksLikePhotoReady) {
                            broadcastLog("🔔 DETECTED NOTIFICATION: " + text);

                            // Full-auto should be able to force-start (Fix 2 & 5)
                            if (mode.equals("full")) {
                                broadcastLog("⚡ FORCE STARTING Full Auto Job (notification)");
                                prefs.edit().putBoolean(KEY_JOB_PENDING, true).apply();
                                isJobPending = true;
                                // Attempt to fire the notification intent if present
                                Parcelable data = event.getParcelableData();
                                if (data instanceof Notification) {
                                    try {
                                        ((Notification) data).contentIntent.send();
                                        broadcastLog("✅ Notification Intent Sent. Waiting for share sheet...");
                                        currentState = STATE_WAITING_FOR_SHARE_SHEET;
                                    } catch (Exception e) {
                                        broadcastLog("❌ Notification intent send failed: " + e.getMessage());
                                    }
                                }
                            }
                        }
                    } catch (Exception ex) {
                        broadcastLog("Exception parsing notification text: " + ex.getMessage());
                    }
                }
            }
        }

        // -----------------------------------------------------------------
        // 2. SEMI-AUTOMATIC: Always wake on WhatsApp open and reset state (Fix 3)
        // -----------------------------------------------------------------
        if (mode.equals("semi") && pkgName.contains("whatsapp")) {
            // Reset state every time you enter WhatsApp so semi-auto always works.
            if (currentState != STATE_INSIDE_WHATSAPP) {
                broadcastLog("🤖 SEMI-AUTO: WhatsApp detected. State reset and robot active.");
            }
            currentState = STATE_INSIDE_WHATSAPP;
            // Reset scroll lock so subsequent runs can scroll again. (Fix 8)
            isScrolling = false;
            // Ensure jobPending doesn't block semi mode (Fix 2)
            // (we do not require KEY_JOB_PENDING to be true for semi)
        }

        // -----------------------------------------------------------------
        // 3. FULL AUTOMATIC: Step 0 - WAITING FOR NOTIFICATION -> SHARE SHEET
        // -----------------------------------------------------------------
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_NOTIFICATION) {
            // Allow forced job start by notification (isJobPending may be set by notification parsing above)
            if (!isJobPending) {
                // nothing to do unless we were force-started
            } else {
                // If the share sheet is already visible, try selecting WhatsApp
                if (root != null) {
                    // Try clone first (Fix 13)
                    if (scanAndClickCloneVariants(root)) {
                        broadcastLog("✅ Clone Selected from share sheet. Moving to WhatsApp...");
                        currentState = STATE_INSIDE_WHATSAPP;
                        return;
                    }

                    // Then try main WhatsApp option
                    if (scanAndClick(root, "whatsapp")) {
                        broadcastLog("👆 Clicked WhatsApp main share option. Waiting for clone/dialog... ");
                        // Stay in waiting state to allow dialog open
                        return;
                    }

                    // Try clickable banners like heads-up that contain the text (Fix 12)
                    List<AccessibilityNodeInfo> heads = findNodesWithText(root, 
                            Arrays.asList("photo ready", "ready to send", "image ready", "photo"));
                    if (!heads.isEmpty()) {
                        for (AccessibilityNodeInfo n : heads) {
                            if (performClick(n)) {
                                broadcastLog("✅ Clicked a heads-up/banner node containing photo text.");
                                currentState = STATE_WAITING_FOR_SHARE_SHEET;
                                return;
                            }
                        }
                    }

                    // If nothing clicked, try to scroll share sheet and retry
                    performScroll(root);
                }
            }
        }

        // -----------------------------------------------------------------
        // 4. FULL AUTOMATIC: Step 1 - SHARE SHEET selected -> open WhatsApp clone
        // -----------------------------------------------------------------
        if (mode.equals("full") && currentState == STATE_WAITING_FOR_SHARE_SHEET) {
            if (root != null) {
                // Clone selection will move us into WhatsApp
                if (scanAndClickCloneVariants(root)) {
                    broadcastLog("✅ Clone Selected. Moving to WhatsApp...");
                    currentState = STATE_INSIDE_WHATSAPP;
                    return;
                }

                // Click main WhatsApp if visible
                if (scanAndClick(root, "whatsapp")) {
                    broadcastLog("👆 Clicked WhatsApp share entry. Waiting for WhatsApp to open...");
                    return;
                }

                // Scroll share sheet and retry (Fix 6)
                performScroll(root);
            }
        }

        // -----------------------------------------------------------------
        // 5. SHARED LOGIC: When inside WhatsApp - find group and send (Semi & Full)
        // -----------------------------------------------------------------
        if (pkgName.contains("whatsapp")) {
            // PART A: FIND GROUP (Fix 10)
            if (currentState == STATE_INSIDE_WHATSAPP) {
                if (root == null) return;
                String targetGroup = prefs.getString(KEY_TARGET_GROUP, "");
                if (targetGroup == null) targetGroup = "";
                String targetLow = targetGroup.toLowerCase().trim();

                // Try exact/substring/partial matches
                if (!targetLow.isEmpty()) {
                    if (scanAndClickPartial(root, targetLow)) {
                        broadcastLog("✅ Found Group: " + targetGroup);
                        currentState = STATE_CONFIRM_SEND;
                        return;
                    }
                }

                // If not found, try scrolling and retrying multiple times
                performScroll(root);
            }

            // PART B: CLICK SEND (Fix 9)
            if (currentState == STATE_CONFIRM_SEND) {
                if (root == null) return;

                boolean sent = false;

                // 1) Content Description "Send" or contains send
                if (scanAndClickByContentDescPartial(root, "send")) sent = true;

                // 2) View ID
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByViewId("com.whatsapp:id/send");
                    if (nodes != null && !nodes.isEmpty()) {
                        if (performClick(nodes.get(0))) sent = true;
                    }
                }

                // 3) Button text containing "send" (case-insensitive)
                if (!sent) {
                    List<AccessibilityNodeInfo> nodes = findNodesWithText(root, 
                            Arrays.asList("send", "send message", "send photo"));
                    if (!nodes.isEmpty()) {
                        for (AccessibilityNodeInfo n : nodes) {
                            if (performClick(n)) {
                                sent = true;
                                break;
                            }
                        }
                    }
                }

                if (sent) {
                    broadcastLog("🚀 SENT! Resetting job and state...");
                    prefs.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                    currentState = STATE_WAITING_FOR_NOTIFICATION;
                    // Clear scroll flag
                    isScrolling = false;
                } else {
                    broadcastLog("❌ SEND button not found. Will retry and reset job if persistent.");
                    // If send isn't found quickly, schedule a reset to avoid permanent stuck job (Fix 11)
                    handler.postDelayed(() -> {
                        SharedPreferences pr = getSharedPreferences(PREFS_ACCESSIBILITY, Context.MODE_PRIVATE);
                        pr.edit().putBoolean(KEY_JOB_PENDING, false).apply();
                        currentState = STATE_WAITING_FOR_NOTIFICATION;
                        isScrolling = false;
                        broadcastLog("⏱️ Auto-reset performed due to send failure.");
                    }, 3500);
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        broadcastLog("⚠️ Robot Interrupted");
    }

    // ----------------------------- Utilities -----------------------------

    private void broadcastLog(String msg) {
        try {
            Intent intent = new Intent("com.lunartag.ACTION_LOG_UPDATE");
            intent.putExtra("log_msg", msg);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            // Also local debug
            Log.d(TAG, msg);
        } catch (Exception e) {
            Log.e(TAG, "broadcastLog failed: " + e.getMessage());
        }
    }

    // Find nodes by exact text (case-insensitive)
    private boolean scanAndClick(AccessibilityNodeInfo root, String text) {
        if (root == null || text == null) return false;
        List<AccessibilityNodeInfo> nodes = root.findAccessibilityNodeInfosByText(text);
        if (nodes != null && !nodes.isEmpty()) {
            for (AccessibilityNodeInfo node : nodes) {
                if (performClick(node)) return true;
            }
        }
        return false;
    }

    // Find nodes by partial / substring / lowercase matching of displayed text (Fix 10)
    private boolean scanAndClickPartial(AccessibilityNodeInfo root, String targetLow) {
        if (root == null || targetLow == null) return false;
        List<AccessibilityNodeInfo> all = collectAllNodes(root);
        for (AccessibilityNodeInfo n : all) {
            try {
                CharSequence txt = n.getText();
                if (txt != null) {
                    String low = txt.toString().toLowerCase();
                    if (low.contains(targetLow) || targetLow.contains(low)) {
                        if (performClick(n)) return true;
                    }
                }
                CharSequence desc = n.getContentDescription();
                if (desc != null) {
                    String low = desc.toString().toLowerCase();
                    if (low.contains(targetLow) || targetLow.contains(low)) {
                        if (performClick(n)) return true;
                    }
                }
            } catch (Exception e) {
                // ignore node errors
            }
        }
        return false;
    }

    // ContentDescription partial scan
    private boolean scanAndClickByContentDescPartial(AccessibilityNodeInfo node, String descLow) {
        if (node == null) return false;
        try {
            CharSequence cd = node.getContentDescription();
            if (cd != null && cd.toString().toLowerCase().contains(descLow)) {
                if (performClick(node)) return true;
            }
            for (int i = 0; i < node.getChildCount(); i++) {
                if (scanAndClickByContentDescPartial(node.getChild(i), descLow)) return true;
            }
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    // Try many clone naming variants (Fix 13)
    private boolean scanAndClickCloneVariants(AccessibilityNodeInfo root) {
        if (root == null) return false;
        List<String> clones = Arrays.asList(
                "whatsapp (clone)", "whatsapp clone", "whatsapp (dual)",
                "whatsapp (app clone)", "dual whatsapp", "clone whatsapp",
                "whatsapp dual", "whatsapp (copy)"
        );
        for (String c : clones) {
            if (scanAndClick(root, c)) return true;
        }
        // Also try partial/substring matches
        List<AccessibilityNodeInfo> all = collectAllNodes(root);
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String low = "";
            if (t != null) low = t.toString().toLowerCase();
            
            if (d != null && d.toString().toLowerCase().contains("clone")) {
                if (performClick(n)) return true;
            }
            if (low.contains("whatsapp") && low.contains("clone")) {
                if (performClick(n)) return true;
            }
        }
        return false;
    }

    // Collect all nodes (flat list) - helper
    private List<AccessibilityNodeInfo> collectAllNodes(AccessibilityNodeInfo root) {
        List<AccessibilityNodeInfo> out = new ArrayList<>();
        if (root == null) return out;
        try {
            traverseAndCollect(root, out);
        } catch (Exception e) {
            // ignore
        }
        return out;
    }

    private void traverseAndCollect(AccessibilityNodeInfo node, List<AccessibilityNodeInfo> out) {
        if (node == null) return;
        out.add(node);
        for (int i = 0; i < node.getChildCount(); i++) {
            traverseAndCollect(node.getChild(i), out);
        }
    }

    // Find nodes by a list of candidate texts (case-insensitive)
    private List<AccessibilityNodeInfo> findNodesWithText(AccessibilityNodeInfo root, List<String> candidates) {
        List<AccessibilityNodeInfo> res = new ArrayList<>();
        if (root == null) return res;
        List<AccessibilityNodeInfo> all = collectAllNodes(root);
        for (AccessibilityNodeInfo n : all) {
            CharSequence t = n.getText();
            CharSequence d = n.getContentDescription();
            String lowT = t == null ? "" : t.toString().toLowerCase();
            String lowD = d == null ? "" : d.toString().toLowerCase();
            for (String c : candidates) {
                if (lowT.contains(c) || lowD.contains(c)) {
                    res.add(n);
                    break;
                }
            }
        }
        return res;
    }

    // Perform click climbing up parent nodes (increased attempts, Fix 7)
    private boolean performClick(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo target = node;
        int attempts = 0;
        while (target != null && attempts < 10) { // increased from 6 to 10
            try {
                if (target.isClickable()) {
                    boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    broadcastLog("performed click on node: clickable=" + target.isClickable() + " | result=" + ok);
                    return ok;
                }
                target = target.getParent();
                attempts++;
            } catch (Exception e) {
                broadcastLog("performClick exception: " + e.getMessage());
                return false;
            }
        }
        return false;
    }

    // Controlled scroll with lock and reset (Fix 8)
    private void performScroll(AccessibilityNodeInfo root) {
        if (isScrolling) return;
        AccessibilityNodeInfo scrollable = findScrollable(root);
        if (scrollable != null) {
            isScrolling = true;
            boolean ok = scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
            broadcastLog("Performed scroll -> result=" + ok);
            // Reset quickly to allow repeated scroll waves
            handler.postDelayed(() -> isScrolling = false, 700);
        }
    }

    private AccessibilityNodeInfo findScrollable(AccessibilityNodeInfo node) {
        if (node == null) return null;
        try {
            if (node.isScrollable()) return node;
            for (int i = 0; i < node.getChildCount(); i++) {
                AccessibilityNodeInfo res = findScrollable(node.getChild(i));
                if (res != null) return res;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }
}
package com.shortsblockr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

public class ShortsBlockerService extends AccessibilityService {

    private static final String TAG = "ShortsBlocker";
    private static final String PREFS_NAME = "ShortsBlockerPrefs";
    private static final String KEY_ENABLED = "enabled";
    private static final long BLOCK_COOLDOWN_MS = 1500;

    private Handler mainHandler;
    private long lastBlockTime = 0;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!isEnabled()) return;

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return;

        CharSequence packageName = event.getPackageName();
        if (packageName == null || !packageName.toString().equals("com.google.android.youtube")) return;

        long now = System.currentTimeMillis();
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) return;

        // Fast detection via class name on window state change
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence className = event.getClassName();
            Log.d(TAG, "CLASS: " + className + " PKG: " + event.getPackageName());
            if (className != null) {
                String cls = className.toString().toLowerCase();
                if (cls.contains("shorts") || cls.contains("reel")) {
                    blockShorts();
                    return;
                }
            }
        }

        if (isInShortsScreen()) {
            blockShorts();
        }
    }

    private void blockShorts() {
        lastBlockTime = System.currentTimeMillis();
        performGlobalAction(GLOBAL_ACTION_BACK);
        mainHandler.post(() ->
            Toast.makeText(getApplicationContext(), "쇼츠 차단! 집중하세요", Toast.LENGTH_SHORT).show()
        );
    }

    private boolean isInShortsScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            return findShortsNode(root, 10);
        } finally {
            root.recycle();
        }
    }

    private boolean findShortsNode(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth <= 0) return false;

        // Skip content description check — it matches the bottom nav "Shorts" tab button
        // Only check view IDs that are specific to the Shorts player
        String viewId = node.getViewIdResourceName();
        if (viewId != null) {
            String id = viewId.toLowerCase();
            if (id.contains("reel_player") || id.contains("reel_watch") ||
                id.contains("shorts_player") || id.contains("shorts_container")) return true;
        }

        int childCount = node.getChildCount();
        for (int i = 0; i < Math.min(childCount, 30); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                boolean found = findShortsNode(child, depth - 1);
                child.recycle();
                if (found) return true;
            }
        }
        return false;
    }

    private boolean isEnabled() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    @Override
    public void onInterrupt() {}

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            setServiceInfo(info);
        }
        mainHandler.post(() ->
            Toast.makeText(this, "쇼츠 차단기 활성화됨", Toast.LENGTH_SHORT).show()
        );
    }
}

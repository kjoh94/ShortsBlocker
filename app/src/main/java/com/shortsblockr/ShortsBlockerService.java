package com.shortsblockr;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

import java.util.List;

/**
 * Blocks YouTube Shorts with Back, except channels on the allowlist.
 *
 * <p>Heuristics, in order, once a full-screen Shorts player is detected
 * ({@link ShortsSignals}): a player / pager / page-container view id, a
 * player class such as {@code ReelPlayerView}, or a Reel watch
 * activity/fragment. The Home Shorts shelf ({@code shorts_container},
 * {@code reel_recycler}) and the Shorts tab do not count.
 *
 * <ol>
 *   <li>Read the accessibility tree: text, content description, and channel-like
 *       view IDs. Also query known channel view IDs and allowlist strings directly
 *       so a deep label is not missed when the walk hits its node cap.</li>
 *   <li>On-screen nodes win over off-screen pager pages. A node counts as on-screen
 *       when {@code isVisibleToUser} is set or most of its bounds intersect the
 *       display. If nothing looks on-screen, visibility is treated as unavailable
 *       and every node counts.</li>
 *   <li>Allowlisted name, {@code @handle}, or channel ID → do not press Back and
 *       do not toast. A visible allowlist hit wins over a different channel that
 *       is also on screen.</li>
 *   <li>A different channel, seen as an {@code @handle}, a subscribe / "open channel"
 *       label, or the text of a channel view ID → Back immediately.</li>
 *   <li>Identity still unknown → retry for a short window instead of backing out.
 *       That protects the owner's Short while its name is still inflating. If the
 *       label never appears, Back runs anyway so other creators are not left playing.
 *       An empty allowlist skips the wait and blocks immediately.</li>
 * </ol>
 *
 * <p>YouTube often omits the channel ID and @handle on the Shorts overlay, so the
 * display name does most of the work. View IDs differ between YouTube versions.
 */
public class ShortsBlockerService extends AccessibilityService {

    private static final String TAG = "ShortsBlocker";
    private static final String KEY_ENABLED = "enabled";
    private static final String KEY_DISABLED_AT = "disabled_at";
    private static final String KEY_DURATION_MS = "duration_ms";
    private static final String YOUTUBE_PACKAGE = "com.google.android.youtube";
    private static final long BLOCK_COOLDOWN_MS = 1500;
    private static final long DEFAULT_DURATION_MS = 60_000L;

    /** Skip repeat scans while an allowlisted Short is confirmed. */
    private static final long ALLOWLIST_HOLD_MS = 250;
    /**
     * After an allowlisted Short was just confirmed, unknown frames (a flicker, or
     * the next Short's label not drawn yet) do not block. A positively different
     * channel still blocks on the next scan.
     */
    private static final long ALLOWLIST_UNKNOWN_GRACE_MS = 450;
    /** Unidentified Shorts are blocked after this, measured from the first unknown frame. */
    private static final long UNKNOWN_GIVE_UP_MS = 800;
    private static final long UNKNOWN_RETRY_MS = 200;
    /** Class-name Shorts detection stays valid for deferred retries until a non-Shorts window event. */
    private static final long CLASS_SIGNAL_MS = 1200;
    private static final long SCAN_INTERVAL_MS = 200;

    private static final int MAX_DEPTH = 16;
    private static final int MAX_CHILDREN = 48;
    private static final int MAX_NODES = 800;
    /** A tree this large with no player IDs contradicts a stale class-name signal. */
    private static final int POPULATED_TREE_NODES = 40;

    private static final String YT_ID = "com.google.android.youtube:id/";
    /**
     * Best-effort IDs from YouTube Shorts overlays across recent releases.
     * The walk still treats any name-bearing ID (see {@link AllowlistMatcher}).
     */
    private static final String[] CHANNEL_VIEW_IDS = {
            "channel_name",
            "channel_title",
            "channel_handle",
            "reel_channel_name",
            "reel_channel_title",
            "reel_channel_handle",
            "reel_channel_bar",
            "reel_channel_profile",
            "shorts_channel_bar",
            "shorts_channel_name",
            "owner_name",
            "author_name",
            "byline",
            "subscribe_button",
            "subscription_button",
            "reel_subscribe_button"
    };

    private Handler mainHandler;
    private long lastBlockTime = 0;
    private long lastScanAt = 0;
    private long lastAllowlistAt = 0;
    private long unknownSince = 0;
    private long classShortsAt = 0;
    /** True once this Shorts session was confirmed via player view IDs, not class name alone. */
    private boolean playerIdsSeen = false;
    private boolean deferredScheduled = false;

    private String allowlistCacheKey = null;
    private ChannelAllowlist allowlist;

    private final Runnable deferredRetry = new Runnable() {
        @Override
        public void run() {
            deferredScheduled = false;
            if (!isEnabled()) {
                unknownSince = 0;
                return;
            }
            handleShorts(false);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onDestroy() {
        cancelDeferred();
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) return;
        if (!isEnabled()) {
            cancelDeferred();
            unknownSince = 0;
            return;
        }

        int eventType = event.getEventType();
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return;
        }

        CharSequence packageName = event.getPackageName();
        if (!isYoutubePackage(packageName)) return;

        long now = System.currentTimeMillis();
        boolean classSaysShorts = false;
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // Only the window class. Child player classes are read in the tree walk,
            // which stays on the scan interval so content events do not walk every frame.
            classSaysShorts = ShortsSignals.isFullScreenPlayerClass(event.getClassName());
            classShortsAt = classSaysShorts ? now : 0;
            Log.d(TAG, "CLASS: " + event.getClassName() + " PKG: " + packageName);
        }

        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) return;
        // Class-name transitions are the fast path and must still run the allowlist check.
        if (!classSaysShorts && lastAllowlistAt > 0 && now - lastAllowlistAt < ALLOWLIST_HOLD_MS) {
            return;
        }
        if (!classSaysShorts && now - lastScanAt < SCAN_INTERVAL_MS) return;
        lastScanAt = now;
        handleShorts(classSaysShorts);
    }

    /**
     * @param classSaysShorts true only for the window-state event whose class name
     *                        itself denotes Shorts. Deferred retries pass false and
     *                        use {@link #classSignalFresh(long)} instead.
     */
    private void handleShorts(boolean classSaysShorts) {
        if (mainHandler == null) {
            mainHandler = new Handler(Looper.getMainLooper());
        }
        long now = System.currentTimeMillis();
        if (now - lastBlockTime < BLOCK_COOLDOWN_MS) return;

        ChannelAllowlist list = currentAllowlist();
        AccessibilityNodeInfo root = null;
        try {
            root = getRootInActiveWindow();
            if (root != null && !isYoutubePackage(root.getPackageName())) {
                leaveShorts();
                return;
            }

            Scan scan = root == null ? new Scan() : scanTree(root, classSaysShorts, list);
            if (scan.shortsPlayer) {
                playerIdsSeen = true;
            } else if (root != null && !classSaysShorts
                    && scan.nodesVisited >= POPULATED_TREE_NODES && playerIdsSeen) {
                // Player IDs were on screen and a populated tree no longer has them: left Shorts.
                // Class-name-only sessions (player IDs never seen) keep the class signal so the
                // fast path still blocks after the allowlist check.
                classShortsAt = 0;
                playerIdsSeen = false;
            }

            boolean onShorts = scan.shortsPlayer || classSaysShorts || classSignalFresh(now);
            if (!onShorts) {
                leaveShorts();
                return;
            }

            if (list.isEmpty()) {
                Log.d(TAG, "Allowlist empty — blocking");
                blockShorts();
                return;
            }

            if (root != null) {
                resolveChannel(root, scan, list);
            }
            if (scan.allowlisted) {
                noteAllowlisted(now);
                return;
            }
            if (scan.otherChannel) {
                Log.d(TAG, "Other channel — blocking");
                blockShorts();
                return;
            }
            deferOrBlock(now);
        } catch (RuntimeException ex) {
            Log.w(TAG, "Shorts scan failed", ex);
        } finally {
            if (root != null) {
                root.recycle();
            }
        }
    }

    private void resolveChannel(AccessibilityNodeInfo root, Scan scan, ChannelAllowlist list) {
        applyVisibility(scan);
        if (scan.allowlisted) return;

        boolean requireVisible = scan.visibleTextNodes > 0;
        if (probeAllowlistText(root, list, requireVisible)) {
            scan.allowlisted = true;
            scan.otherChannel = false;
            return;
        }
        if (!scan.otherChannel) {
            probeKnownChannelIds(root, scan, list, requireVisible);
            applyVisibility(scan);
        }
    }

    private void deferOrBlock(long now) {
        if (lastAllowlistAt > 0 && now - lastAllowlistAt < ALLOWLIST_UNKNOWN_GRACE_MS) {
            scheduleDeferred();
            return;
        }
        if (unknownSince == 0) {
            unknownSince = now;
            Log.d(TAG, "Channel unknown — waiting before block");
        }
        if (now - unknownSince >= UNKNOWN_GIVE_UP_MS) {
            Log.d(TAG, "Channel still unknown — blocking");
            blockShorts();
            return;
        }
        scheduleDeferred();
    }

    private void noteAllowlisted(long now) {
        cancelDeferred();
        unknownSince = 0;
        if (lastAllowlistAt == 0 || now - lastAllowlistAt >= ALLOWLIST_HOLD_MS) {
            Log.d(TAG, "Allowlisted channel — not blocking");
        }
        lastAllowlistAt = now;
    }

    private void leaveShorts() {
        cancelDeferred();
        unknownSince = 0;
        lastAllowlistAt = 0;
        playerIdsSeen = false;
    }

    private void blockShorts() {
        cancelDeferred();
        unknownSince = 0;
        lastAllowlistAt = 0;
        playerIdsSeen = false;
        lastBlockTime = System.currentTimeMillis();
        performGlobalAction(GLOBAL_ACTION_BACK);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), "쇼츠 차단! 집중하세요", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private Scan scanTree(AccessibilityNodeInfo root, boolean classSaysShorts, ChannelAllowlist list) {
        Scan scan = new Scan();
        int[] budget = new int[]{MAX_NODES};
        walk(root, MAX_DEPTH, budget, classSaysShorts, list, scan);
        applyVisibility(scan);
        return scan;
    }

    /** @return true when the walk can stop (visible allowlist hit on a Shorts screen) */
    private boolean walk(AccessibilityNodeInfo node, int depth, int[] budget,
                         boolean classSaysShorts, ChannelAllowlist list, Scan scan) {
        if (node == null || depth < 0 || budget[0] <= 0) return false;
        budget[0]--;
        scan.nodesVisited++;

        String viewId = node.getViewIdResourceName();
        if (ShortsSignals.isFullScreenPlayerId(viewId)
                || ShortsSignals.isFullScreenPlayerClass(node.getClassName())) {
            scan.shortsPlayer = true;
        }
        boolean visible = isShown(node);
        boolean nameBearing = AllowlistMatcher.isNameBearingViewId(viewId);
        considerText(scan, chars(node.getText()), visible, nameBearing, list);
        considerText(scan, chars(node.getContentDescription()), visible, nameBearing, list);
        if (scan.allowlistedVisible && (scan.shortsPlayer || classSaysShorts)) return true;

        int childCount = Math.min(node.getChildCount(), MAX_CHILDREN);
        for (int i = 0; i < childCount; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (walk(child, depth - 1, budget, classSaysShorts, list, scan)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
    }

    private void considerText(Scan scan, String raw, boolean visible, boolean nameBearing,
                              ChannelAllowlist list) {
        String text = AllowlistMatcher.clean(raw);
        if (text.isEmpty()) return;
        if (visible) scan.visibleTextNodes++;

        boolean allow = list.matches(text);
        boolean other = false;
        if (!allow) {
            String attributed = AllowlistMatcher.extractAttributedChannel(text);
            if (attributed != null) {
                allow = list.matches(attributed);
                other = !allow;
            }
            if (!allow && !other && list.isExactForeignHandle(text)) {
                other = true;
            }
            if (!allow && !other && nameBearing && AllowlistMatcher.looksLikeChannelName(text)) {
                other = true;
            }
        }
        if (allow) {
            scan.allowlistedAny = true;
            if (visible) scan.allowlistedVisible = true;
        } else if (other) {
            scan.otherAny = true;
            if (visible) scan.otherVisible = true;
        }
    }

    private void applyVisibility(Scan scan) {
        boolean useVisible = scan.visibleTextNodes > 0;
        scan.allowlisted = useVisible ? scan.allowlistedVisible : scan.allowlistedAny;
        scan.otherChannel = useVisible ? scan.otherVisible : scan.otherAny;
        if (scan.allowlisted) scan.otherChannel = false;
    }

    private boolean probeAllowlistText(AccessibilityNodeInfo root, ChannelAllowlist list,
                                       boolean requireVisible) {
        List<String> queries = list.textQueries();
        for (int q = 0; q < queries.size(); q++) {
            List<AccessibilityNodeInfo> found = root.findAccessibilityNodeInfosByText(queries.get(q));
            if (found == null) continue;
            try {
                for (int i = 0; i < found.size(); i++) {
                    AccessibilityNodeInfo node = found.get(i);
                    if (node == null) continue;
                    if (requireVisible && !isShown(node)) continue;
                    if (list.matches(chars(node.getText()))
                            || list.matches(chars(node.getContentDescription()))) {
                        return true;
                    }
                }
            } finally {
                recycleAll(found);
            }
        }
        return false;
    }

    private void probeKnownChannelIds(AccessibilityNodeInfo root, Scan scan, ChannelAllowlist list,
                                      boolean requireVisible) {
        for (int idIndex = 0; idIndex < CHANNEL_VIEW_IDS.length; idIndex++) {
            List<AccessibilityNodeInfo> found =
                    root.findAccessibilityNodeInfosByViewId(YT_ID + CHANNEL_VIEW_IDS[idIndex]);
            if (found == null) continue;
            try {
                for (int i = 0; i < found.size(); i++) {
                    AccessibilityNodeInfo node = found.get(i);
                    if (node == null) continue;
                    if (requireVisible && !isShown(node)) continue;
                    considerText(scan, chars(node.getText()), true, true, list);
                    considerText(scan, chars(node.getContentDescription()), true, true, list);
                }
            } finally {
                recycleAll(found);
            }
        }
    }

    private static void recycleAll(List<AccessibilityNodeInfo> nodes) {
        for (int i = 0; i < nodes.size(); i++) {
            AccessibilityNodeInfo node = nodes.get(i);
            if (node != null) node.recycle();
        }
    }

    /**
     * On-screen if the framework says so, or if most of the node's screen bounds
     * intersect the display. Preloaded Shorts sit fully above or below the screen;
     * {@code isVisibleToUser} alone is sometimes false for on-screen labels.
     */
    private boolean isShown(AccessibilityNodeInfo node) {
        if (node.isVisibleToUser()) return true;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        if (bounds.width() <= 0 || bounds.height() <= 0) return false;
        int screenW = getResources().getDisplayMetrics().widthPixels;
        int screenH = getResources().getDisplayMetrics().heightPixels;
        if (screenW <= 0 || screenH <= 0) return false;
        int visibleW = Math.min(bounds.right, screenW) - Math.max(bounds.left, 0);
        int visibleH = Math.min(bounds.bottom, screenH) - Math.max(bounds.top, 0);
        if (visibleW <= 0 || visibleH <= 0) return false;
        long visibleArea = (long) visibleW * visibleH;
        long area = (long) bounds.width() * bounds.height();
        return visibleArea * 2 >= area;
    }

    private static boolean isYoutubePackage(CharSequence packageName) {
        return packageName != null && YOUTUBE_PACKAGE.contentEquals(packageName);
    }

    private static String chars(CharSequence value) {
        return value == null ? "" : value.toString();
    }

    private boolean classSignalFresh(long now) {
        return classShortsAt > 0 && now - classShortsAt <= CLASS_SIGNAL_MS;
    }

    private void scheduleDeferred() {
        if (deferredScheduled || mainHandler == null) return;
        deferredScheduled = true;
        mainHandler.postDelayed(deferredRetry, UNKNOWN_RETRY_MS);
    }

    private void cancelDeferred() {
        deferredScheduled = false;
        if (mainHandler != null) mainHandler.removeCallbacks(deferredRetry);
    }

    private ChannelAllowlist currentAllowlist() {
        SharedPreferences prefs = getSharedPreferences(ChannelAllowlist.PREFS_NAME, MODE_PRIVATE);
        String key = prefs.contains(ChannelAllowlist.KEY_ALLOWLIST)
                ? "custom:" + prefs.getString(ChannelAllowlist.KEY_ALLOWLIST, "")
                : "defaults";
        if (allowlist == null || !key.equals(allowlistCacheKey)) {
            allowlistCacheKey = key;
            allowlist = ChannelAllowlist.load(this);
        }
        return allowlist;
    }

    private boolean isEnabled() {
        SharedPreferences prefs = getSharedPreferences(ChannelAllowlist.PREFS_NAME, MODE_PRIVATE);
        if (!prefs.getBoolean(KEY_ENABLED, true)) {
            long disabledAt = prefs.getLong(KEY_DISABLED_AT, 0);
            long durationMs = prefs.getLong(KEY_DURATION_MS, DEFAULT_DURATION_MS);
            if (disabledAt > 0 && System.currentTimeMillis() - disabledAt >= durationMs) {
                prefs.edit().putBoolean(KEY_ENABLED, true).remove(KEY_DISABLED_AT).apply();
                return true;
            }
            return false;
        }
        return true;
    }

    @Override
    public void onInterrupt() {
        cancelDeferred();
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            setServiceInfo(info);
        }
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ShortsBlockerService.this, "쇼츠 차단기 활성화됨", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private static final class Scan {
        boolean shortsPlayer;
        int nodesVisited;
        int visibleTextNodes;
        boolean allowlistedVisible;
        boolean allowlistedAny;
        boolean otherVisible;
        boolean otherAny;
        boolean allowlisted;
        boolean otherChannel;
    }
}

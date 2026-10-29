package com.shortsblockr;

import java.util.Locale;

/**
 * Full-screen YouTube Shorts signals from the accessibility tree.
 *
 * <p>No Android types, so the rules run under plain {@code javac}. The service
 * asks these helpers before it will press Back.
 *
 * <p>YouTube keeps Shorts inside the main watch activity, whose class name
 * ({@code WatchWhileActivity}) contains neither {@code shorts} nor {@code reel}.
 * The immersive player shows up as a view id and, often, a child class such as
 * {@code ReelPlayerView}. A Shorts shelf on Home uses different ids
 * ({@code shorts_container}, {@code reel_recycler}, {@code shorts_shelf}) and
 * the bottom-nav tab is a class such as {@code LegacyShortsTabIndicatorView}.
 * Those must not count.
 *
 * <p>Peer lists this is aligned with: Scrolless ({@code reel_player_page_container}),
 * Argus ({@code reel_watch_player}, {@code reel_player_page_container},
 * {@code reel_player_underlay}; shelves {@code shorts_shelf} / {@code reel_shelf}
 * excluded), and undisputedP/ShortsBlocker 1.0.10
 * ({@code shorts_video_pager}, {@code shorts_player}, {@code shorts_player_view},
 * {@code reel_player_underlay_view}). {@code reel_watch_fragment_root} is the
 * other id accessibility blockers have used for the watch surface.
 * Version 1.0.9 of that peer stopped treating {@code recycler} / {@code container}
 * class names as the player after they matched the shelf inside Subscriptions
 * and You.
 */
public final class ShortsSignals {

    /**
     * Suffixes known to belong to the immersive player. The structural rule
     * below also accepts the same shapes when YouTube renames one of them.
     */
    static final String[] PLAYER_ID_SUFFIXES = {
            "reel_player",
            "reel_player_page_container",
            "reel_player_underlay",
            "reel_player_underlay_view",
            "reel_watch_player",
            "reel_watch_fragment_root",
            "shorts_player",
            "shorts_player_view",
            "shorts_video_pager"
    };

    private ShortsSignals() {
    }

    /**
     * @return true when {@code viewId} is the immersive Shorts player, including
     *         a {@code package:id/} prefix. Shelf and tab ids return false.
     */
    public static boolean isFullScreenPlayerId(String viewId) {
        String suffix = idSuffix(viewId);
        if (suffix.isEmpty() || isShelfOrChromeId(suffix)) return false;
        if (isListedPlayerId(suffix)) return true;
        if (!hasShortsToken(suffix)) return false;
        return suffix.contains("player")
                || suffix.contains("pager")
                || suffix.contains("page_container")
                || suffix.contains("reel_watch")
                || suffix.contains("shorts_watch");
    }

    /**
     * @return true when the simple class name is the Shorts player
     *         ({@code ReelPlayerView}) or the Reel watch activity/fragment.
     *         The package path is ignored, so {@code ...reel.watch...FrameLayout}
     *         does not match. The Shorts tab and shelf classes do not match.
     */
    public static boolean isFullScreenPlayerClass(CharSequence className) {
        String raw = simpleName(className);
        if (raw.isEmpty()) return false;
        // Keep a '$' so both ReelPlayerView$1 and Outer$ReelPlayerView can match.
        int dollar = raw.indexOf('$');
        if (dollar < 0) return classLooksLikePlayer(raw);
        if (dollar > 0 && classLooksLikePlayer(raw.substring(0, dollar))) return true;
        return dollar < raw.length() - 1 && classLooksLikePlayer(raw.substring(dollar + 1));
    }

    private static boolean classLooksLikePlayer(String simple) {
        if (simple.isEmpty() || isRejectedChrome(simple) || !hasShortsToken(simple)) return false;
        if (simple.contains("player")) return true;
        return simple.contains("watch")
                && (simple.contains("activity") || simple.contains("fragment"));
    }

    private static boolean isListedPlayerId(String suffix) {
        for (int i = 0; i < PLAYER_ID_SUFFIXES.length; i++) {
            if (suffix.equals(PLAYER_ID_SUFFIXES[i])) return true;
        }
        return false;
    }

    /**
     * Ids that appear on the Home / Subscriptions / You Shorts shelf. They are
     * not enough to press Back, even when the name also contains a player token.
     */
    private static boolean isShelfOrChromeId(String suffix) {
        if (suffix.contains("shelf")) return true;
        if (suffix.contains("shorts_container")) return true;
        if (suffix.contains("reel_recycler")) return true;
        return false;
    }

    /** Tab, shelf, and thumbnail classes are not the playing Short. */
    private static boolean isRejectedChrome(String simpleName) {
        return simpleName.contains("shelf")
                || simpleName.contains("tab")
                || simpleName.contains("indicator")
                || simpleName.contains("pivot")
                || simpleName.contains("thumbnail");
    }

    private static boolean hasShortsToken(String lower) {
        return lower.contains("reel") || lower.contains("shorts");
    }

    static String idSuffix(String viewId) {
        if (viewId == null) return "";
        String id = viewId.trim().toLowerCase(Locale.ROOT);
        if (id.isEmpty()) return "";
        int slash = id.lastIndexOf('/');
        if (slash >= 0 && slash < id.length() - 1) return id.substring(slash + 1);
        return id;
    }

    static String simpleName(CharSequence className) {
        if (className == null) return "";
        String cls = className.toString().trim().toLowerCase(Locale.ROOT);
        if (cls.isEmpty()) return "";
        int dot = cls.lastIndexOf('.');
        if (dot >= 0 && dot < cls.length() - 1) cls = cls.substring(dot + 1);
        return cls;
    }
}

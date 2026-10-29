package com.shortsblockr;

/**
 * Plain Java checks for full-screen Shorts detection. Run from the repo root:
 * javac -encoding UTF-8 -source 8 -target 8 -d /tmp/shorts-signals-test
 *   app/src/main/java/com/shortsblockr/ShortsSignals.java
 *   app/src/test/java/com/shortsblockr/ShortsSignalsTest.java
 * java -cp /tmp/shorts-signals-test com.shortsblockr.ShortsSignalsTest
 */
public class ShortsSignalsTest {

    private static final String YT = "com.google.android.youtube:id/";

    private static int failures = 0;

    public static void main(String[] args) {
        ShortsSignalsTest test = new ShortsSignalsTest();
        test.listedPlayerIdsMatch();
        test.structuralPlayerIdsMatchWithoutAListEdit();
        test.shelfAndRegularVideoIdsDoNotMatch();
        test.oldLooseClassOrDoesNotMatch();
        test.playerClassesMatch();
        test.suffixAndSimpleName();
        if (failures > 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("ShortsSignalsTest OK");
    }

    /** Ids peers treat as the immersive player, including ones the old four substrings missed. */
    private void listedPlayerIdsMatch() {
        String[] playerIds = {
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
        for (int i = 0; i < playerIds.length; i++) {
            String suffix = playerIds[i];
            check(ShortsSignals.isFullScreenPlayerId(YT + suffix), suffix);
            check(ShortsSignals.isFullScreenPlayerId(suffix.toUpperCase()), suffix + " case");
        }
        // The old rule was contains(reel_player | reel_watch | shorts_player | shorts_container).
        // shorts_video_pager contains none of those.
        check(!legacyPlayerId(YT + "shorts_video_pager"), "pager is invisible to the old substrings");
        check(ShortsSignals.isFullScreenPlayerId(YT + "shorts_video_pager"), "pager is a player now");
        for (int i = 0; i < ShortsSignals.PLAYER_ID_SUFFIXES.length; i++) {
            String suffix = ShortsSignals.PLAYER_ID_SUFFIXES[i];
            check(ShortsSignals.isFullScreenPlayerId(YT + suffix), "listed " + suffix);
        }
    }

    /** A renamed player/pager/page-container id still counts; the list is not exhaustive. */
    private void structuralPlayerIdsMatchWithoutAListEdit() {
        check(ShortsSignals.isFullScreenPlayerId(YT + "reel_player_overlay"), "renamed player overlay");
        check(ShortsSignals.isFullScreenPlayerId(YT + "shorts_pager"), "shorts pager");
        check(ShortsSignals.isFullScreenPlayerId(YT + "reel_page_container"), "page container without the word player");
        check(ShortsSignals.isFullScreenPlayerId(YT + "reel_watch_fragment"), "reel watch surface");
        check(ShortsSignals.isFullScreenPlayerId(YT + "shorts_watch_player"), "shorts watch player");
        check(ShortsSignals.isFullScreenPlayerId("reel_player_page_container"), "bare suffix");
    }

    /** Home-feed shelves and ordinary watch chrome must not press Back. */
    private void shelfAndRegularVideoIdsDoNotMatch() {
        String[] notPlayer = {
                "shorts_container",
                "shorts_container_layout",
                "reel_recycler",
                "shorts_shelf",
                "reel_shelf",
                "watch_while_layout",
                "reel_channel_name",
                "reel_channel_bar",
                "shorts_pivot_bar",
                "player_view",
                "video_pager"
        };
        for (int i = 0; i < notPlayer.length; i++) {
            check(!ShortsSignals.isFullScreenPlayerId(YT + notPlayer[i]), "not player: " + notPlayer[i]);
        }
        // Shelf wins even if a later rename glues a player token onto the shelf id.
        check(!ShortsSignals.isFullScreenPlayerId(YT + "shorts_shelf_player"), "shelf player token");
        check(!ShortsSignals.isFullScreenPlayerId(YT + "reel_recycler_player"), "recycler player token");
        check(!ShortsSignals.isFullScreenPlayerId(null), "null id");
        check(!ShortsSignals.isFullScreenPlayerId(""), "empty id");
        check(!ShortsSignals.isFullScreenPlayerId("com.google.android.youtube:id/"), "empty suffix");
        // The old substring rule treated the shelf container as the player.
        check(legacyPlayerId(YT + "shorts_container"), "old rule false-positive on shorts_container");
        check(!ShortsSignals.isFullScreenPlayerId(YT + "shorts_container"), "shelf container is not the player");
    }

    private void oldLooseClassOrDoesNotMatch() {
        String tab = "com.google.android.libraries.youtube.rendering.ui.pivotbar.LegacyShortsTabIndicatorView";
        String shelf = "com.google.android.apps.youtube.app.extensions.reel.feed.ShortsShelfRecyclerView";
        String home = "com.google.android.apps.youtube.app.watchwhile.WatchWhileActivity";
        String layout = "android.widget.FrameLayout";
        check(legacyClass(tab), "old rule matches the Shorts tab");
        check(!ShortsSignals.isFullScreenPlayerClass(tab), "tab is not the player");
        check(legacyClass(shelf), "old rule matches a shelf class");
        check(!ShortsSignals.isFullScreenPlayerClass(shelf), "shelf class is not the player");
        check(!ShortsSignals.isFullScreenPlayerClass(home), "main activity alone");
        check(!ShortsSignals.isFullScreenPlayerClass(layout), "framework layout");
        check(!ShortsSignals.isFullScreenPlayerClass(null), "null class");
        check(!ShortsSignals.isFullScreenPlayerClass(""), "empty class");
        // Package path contains reel.watch; the simple name must not inherit that.
        String pathOnly = "com.google.android.apps.youtube.app.extensions.reel.watch.ui.FrameLayout";
        check(!ShortsSignals.isFullScreenPlayerClass(pathOnly), "package path is not a player class");
    }

    private void playerClassesMatch() {
        check(ShortsSignals.isFullScreenPlayerClass("ReelPlayerView"), "simple player");
        check(ShortsSignals.isFullScreenPlayerClass(
                "com.google.android.apps.youtube.app.extensions.reel.watch.player.ReelPlayerView"),
                "qualified player");
        check(ShortsSignals.isFullScreenPlayerClass("ShortsPlayerView"), "shorts player view");
        check(ShortsSignals.isFullScreenPlayerClass(
                "com.google.android.apps.youtube.app.extensions.reel.watch.activity.ReelWatchActivity"),
                "reel watch activity");
        check(ShortsSignals.isFullScreenPlayerClass("com.example.ReelWatchFragment"), "reel watch fragment");
        check(ShortsSignals.isFullScreenPlayerClass("REELPLAYERVIEW"), "class case");
        check(ShortsSignals.isFullScreenPlayerClass("com.example.ReelPlayerView$1"), "anonymous subclass");
        check(ShortsSignals.isFullScreenPlayerClass("com.example.Outer$ReelPlayerView"), "named inner class");
        check(!ShortsSignals.isFullScreenPlayerClass("ReelWatchHelper"), "watch helper is not a screen");
        check(!ShortsSignals.isFullScreenPlayerClass("ShortsActivity"), "shorts activity without watch/player");
    }

    private void suffixAndSimpleName() {
        expect("shorts_video_pager", ShortsSignals.idSuffix(YT + "Shorts_Video_Pager"), "suffix");
        expect("reelplayerview$1", ShortsSignals.simpleName(
                "com.example.ReelPlayerView$1"), "anonymous suffix kept");
        expect("", ShortsSignals.idSuffix(null), "null suffix");
        expect("", ShortsSignals.simpleName(null), "null simple name");
    }

    /** The previous view-id rule, kept here so the shelf false-positive stays visible. */
    private static boolean legacyPlayerId(String viewId) {
        if (viewId == null) return false;
        String id = viewId.toLowerCase(java.util.Locale.ROOT);
        return id.contains("reel_player")
                || id.contains("reel_watch")
                || id.contains("shorts_player")
                || id.contains("shorts_container");
    }

    /** The previous window-class rule: any class containing shorts or reel. */
    private static boolean legacyClass(String className) {
        if (className == null) return false;
        String cls = className.toLowerCase(java.util.Locale.ROOT);
        return cls.contains("shorts") || cls.contains("reel");
    }

    private static void check(boolean condition, String label) {
        if (!condition) {
            failures++;
            System.err.println("FAIL " + label);
        }
    }

    private static void expect(String expected, String actual, String label) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.err.println("FAIL " + label + " expected=[" + expected + "] actual=[" + actual + "]");
        }
    }
}

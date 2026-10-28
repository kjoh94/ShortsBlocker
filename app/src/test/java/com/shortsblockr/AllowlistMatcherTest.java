package com.shortsblockr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/**
 * Plain Java checks for allowlist matching. Run from the repo root:
 * javac -encoding UTF-8 -source 8 -target 8 -d /tmp/allowlist-test
 *   app/src/main/java/com/shortsblockr/AllowlistMatcher.java
 *   app/src/test/java/com/shortsblockr/AllowlistMatcherTest.java
 * java -cp /tmp/allowlist-test com.shortsblockr.AllowlistMatcherTest
 */
public class AllowlistMatcherTest {

    private static int failures = 0;

    public static void main(String[] args) throws Exception {
        AllowlistMatcherTest test = new AllowlistMatcherTest();
        test.defaultsMatchOwnerSignals();
        test.rejectsNearMisses();
        test.longTextDoesNotMatchDisplayName();
        test.bareHandleMatchesInsideLongText();
        test.emptyAndExtraChannels();
        test.attributionLabels();
        test.channelChromeIds();
        test.ignoredLabelsAndMetricsAreNotNames();
        test.commentsAndBlankLines();
        test.defaultsMatchResourceFile();
        if (failures > 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("AllowlistMatcherTest OK");
    }

    private final AllowlistMatcher owner = AllowlistMatcher.defaults();

    private void defaultsMatchOwnerSignals() {
        check(owner.matches("adrenalGr8"), "exact name");
        check(owner.matches("ADRENALGR8"), "name case");
        check(owner.matches("adrenalGr8님"), "korean particle");
        check(owner.matches("adrenalGr8님을 구독"), "korean subscribe phrase");
        check(owner.matches("Subscribe to adrenalGr8"), "english subscribe");
        check(owner.matches("@adrenalgr8964"), "handle with @");
        check(owner.matches("adrenalgr8964"), "handle without @");
        check(owner.matches("@AdrenalGr8964"), "handle case");
        check(owner.matches("＠adrenalgr8964"), "fullwidth @");
        check(owner.matches("UC6WHTOfNwh-69D_4EXRFgkg"), "channel id");
        check(owner.matches("uc6whtofnwh-69d_4exrfgkg"), "channel id case");
        check(owner.matches("https://www.youtube.com/channel/UC6WHTOfNwh-69D_4EXRFgkg"), "id in url");
        check(owner.matches("adrenalGr8\u00A0"), "nbsp trimmed");

        List<AllowlistMatcher.Entry> entries = owner.entries();
        expect(3, entries.size(), "default count");
        check(entries.get(2).channelId, "id classified");
        check(entries.get(1).explicitHandle, "handle classified");
        check(!entries.get(0).explicitHandle, "name is not a handle");
        check(!entries.get(0).channelId, "name is not an id");
    }

    private void rejectsNearMisses() {
        check(!owner.matches("adrenalGr80"), "name prefix of a longer token");
        check(!owner.matches("xadrenalGr8"), "name glued on the left");
        check(!owner.matches("notadrenalgr8964"), "handle glued on the left");
        check(!owner.matches("@someoneelse"), "other handle");
        check(!owner.matches("MrBeast"), "other name");
        check(!owner.matches(""), "empty");
        check(!owner.matches(null), "null");
        check(owner.isExactForeignHandle("@someoneelse"), "foreign handle signal");
        check(!owner.isExactForeignHandle("@adrenalgr8964"), "own handle is not foreign");
        check(!owner.isExactForeignHandle("thanks @someoneelse"), "mention is not an exact handle");
    }

    private void longTextDoesNotMatchDisplayName() {
        StringBuilder longTitle = new StringBuilder("watch adrenalGr8 later ");
        while (longTitle.length() <= AllowlistMatcher.SHORT_TEXT_MAX) longTitle.append("x");
        check(!owner.matches(longTitle.toString()), "display name inside long text");
        check(owner.matches("adrenalGr8 is great"), "display name inside short text");
    }

    private void bareHandleMatchesInsideLongText() {
        StringBuilder text = new StringBuilder("credits adrenalgr8964 ");
        while (text.length() <= AllowlistMatcher.SHORT_TEXT_MAX) text.append("y");
        check(owner.matches(text.toString()), "bare handle inside long text");
        check(owner.matches("see @adrenalgr8964 for more " + repeat('z', 80)), "@handle inside long text");
    }

    private void emptyAndExtraChannels() {
        AllowlistMatcher empty = AllowlistMatcher.fromLines(Arrays.<String>asList());
        check(!empty.matches("adrenalGr8"), "empty list matches nothing");
        check(empty.isEmpty(), "empty");

        AllowlistMatcher extra = AllowlistMatcher.fromLines(Arrays.asList(
                "adrenalGr8",
                "@adrenalgr8964",
                "UC6WHTOfNwh-69D_4EXRFgkg",
                "Second Creator"));
        check(extra.matches("Second Creator"), "added display name");
        check(extra.matches("subscribe to Second Creator"), "added name in short label");
        check(!owner.matches("Second Creator"), "default list ignores the extra name");
    }

    private void attributionLabels() {
        expect("adrenalGr8", AllowlistMatcher.extractAttributedChannel("Subscribe to adrenalGr8"), "en subscribe");
        expect("adrenalGr8", AllowlistMatcher.extractAttributedChannel("Go to channel adrenalGr8"), "en go to");
        expect("adrenalGr8", AllowlistMatcher.extractAttributedChannel("adrenalGr8 채널로 이동"), "ko open");
        expect("adrenalGr8", AllowlistMatcher.extractAttributedChannel("adrenalGr8님을 구독"), "ko subscribe");
        expect("MrBeast", AllowlistMatcher.extractAttributedChannel("Subscribe to MrBeast"), "other creator");
        check(AllowlistMatcher.extractAttributedChannel("구독") == null, "button without a name");
        check(AllowlistMatcher.extractAttributedChannel("Subscribe") == null, "english button");
        check(owner.matches(AllowlistMatcher.extractAttributedChannel("Subscribe to adrenalGr8")),
                "attributed owner matches");
        String other = AllowlistMatcher.extractAttributedChannel("Subscribe to MrBeast");
        check(other != null && !owner.matches(other), "attributed other does not match");
    }

    private void channelChromeIds() {
        check(AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/channel_name"), "channel_name");
        check(AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/reel_channel_bar"), "reel bar");
        check(AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/reel_owner_avatar"), "reel avatar");
        check(!AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/subscribe_button"), "button excluded");
        check(!AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/you_tab_avatar"), "account avatar");
        check(!AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/channel_subscriber_count"), "count");
        check(!AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/reel_player_page_container"), "player");
        check(!AllowlistMatcher.isNameBearingViewId("com.google.android.youtube:id/shorts_container"), "shorts container");
        check(!AllowlistMatcher.isNameBearingViewId(null), "null id");
    }

    private void ignoredLabelsAndMetricsAreNotNames() {
        check(!AllowlistMatcher.looksLikeChannelName("구독"), "subscribe ko");
        check(!AllowlistMatcher.looksLikeChannelName("구독 중"), "subscribed ko");
        check(!AllowlistMatcher.looksLikeChannelName("Subscribe"), "subscribe en");
        check(!AllowlistMatcher.looksLikeChannelName("Shorts"), "tab label");
        check(!AllowlistMatcher.looksLikeChannelName("12만"), "count");
        check(!AllowlistMatcher.looksLikeChannelName("구독자 10만명"), "subscriber count");
        check(AllowlistMatcher.looksLikeChannelName("adrenalGr8"), "owner name");
        check(AllowlistMatcher.looksLikeChannelName("김철수"), "korean name");
        check(AllowlistMatcher.looksLikeChannelName("MrBeast"), "other name shape");
        check(AllowlistMatcher.isIgnoredLabel("구독 중"), "normalized ignore");
    }

    private void commentsAndBlankLines() {
        List<String> lines = AllowlistMatcher.splitLines("\n adrenalGr8 \n\n# note\n@adrenalgr8964\n");
        expect(2, lines.size(), "skipped blanks and comments");
        check(AllowlistMatcher.fromLines(lines).matches("@adrenalgr8964"), "parsed handle");
    }

    private void defaultsMatchResourceFile() throws Exception {
        Path xml = Paths.get("app/src/main/res/values/arrays.xml");
        if (!Files.exists(xml)) xml = Paths.get("src/main/res/values/arrays.xml");
        String text = new String(Files.readAllBytes(xml), StandardCharsets.UTF_8);
        for (int i = 0; i < AllowlistMatcher.DEFAULT_ENTRIES.length; i++) {
            String entry = AllowlistMatcher.DEFAULT_ENTRIES[i];
            check(text.contains("<item>" + entry + "</item>"), "arrays.xml contains " + entry);
        }
    }

    private static String repeat(char c, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, c);
        return new String(chars);
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

    private static void expect(int expected, int actual, String label) {
        if (expected != actual) {
            failures++;
            System.err.println("FAIL " + label + " expected=" + expected + " actual=" + actual);
        }
    }
}

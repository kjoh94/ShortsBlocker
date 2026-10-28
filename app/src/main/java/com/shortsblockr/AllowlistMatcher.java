package com.shortsblockr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Case-insensitive matching of YouTube channel names, @handles, and channel IDs.
 *
 * <p>The Shorts player usually exposes the display name (a short TextView or content
 * description) and only sometimes the @handle. The channel ID is rarely in the
 * accessibility tree; it is still matched when a share sheet or link includes it.
 * Handles match with or without a leading '@'. Comparison uses {@link Locale#ROOT}.
 *
 * <p>Token boundaries are ASCII identifier characters ({@code A-Z a-z 0-9 _ . - @}).
 * A Korean particle glued to a name ({@code adrenalGr8님}) still matches, while
 * {@code adrenalGr80} does not. Display names match as a whole token only in short
 * text (channel chrome, subscribe labels). @handles and channel IDs also match inside
 * longer text because they are specific. An empty allowlist matches nothing.
 *
 * <p>Defaults live here and in {@code res/values/arrays.xml} (keep those lists in sync).
 * SharedPreferences overrides them when the user edits the list.
 */
public final class AllowlistMatcher {

    /** Keep in sync with res/values/arrays.xml default_channel_allowlist. */
    public static final String[] DEFAULT_ENTRIES = {
            "adrenalGr8",
            "@adrenalgr8964",
            "UC6WHTOfNwh-69D_4EXRFgkg"
    };

    /** Display-name / subscribe-label text longer than this is not scanned for names. */
    static final int SHORT_TEXT_MAX = 80;

    private static final Pattern CHANNEL_ID = Pattern.compile("uc[a-z0-9_-]{22}");
    private static final Pattern EXACT_HANDLE = Pattern.compile("@[A-Za-z0-9._-]{3,30}");

    private static final Pattern[] ATTRIBUTION = new Pattern[]{
            Pattern.compile("(?i)^subscribe\\s+to\\s+(.+)$"),
            Pattern.compile("(?i)^subscribed\\s+to\\s+(.+)$"),
            Pattern.compile("(?i)^go\\s+to\\s+(?:the\\s+)?channel\\s+(.+)$"),
            Pattern.compile("(?i)^visit\\s+(.+?)(?:'s)?\\s+channel$"),
            Pattern.compile("^(.+?)\\s*채널로\\s*이동$"),
            Pattern.compile("^(.+?)\\s*님을\\s*구독(?:하기|합니다)?$"),
            Pattern.compile("^(.+?)\\s*님\\s*구독(?:하기)?$"),
            Pattern.compile("^(.+?)\\s*채널\\s*구독(?:하기)?$")
    };

    private static final String[] NAME_ID_PARTS = {
            "channel", "author", "owner", "byline", "attribution", "creator", "handle"
    };
    private static final String[] REEL_AVATAR_PARTS = {
            "avatar", "profile"
    };
    private static final String[] EXCLUDE_ID_PARTS = {
            "container", "layout", "root", "recycler", "shelf", "count", "subscriber",
            "icon", "logo", "banner", "button", "pager", "overlay", "background",
            "scrim", "header", "navigation", "pivot", "thumbnail_overlay"
    };

    private static final Set<String> IGNORED_LABELS = ignoredLabels();

    private final List<Entry> entries;

    public AllowlistMatcher(List<Entry> entries) {
        this.entries = entries == null
                ? Collections.<Entry>emptyList()
                : Collections.unmodifiableList(new ArrayList<Entry>(entries));
    }

    public static AllowlistMatcher fromLines(List<String> lines) {
        return new AllowlistMatcher(parse(lines));
    }

    public static AllowlistMatcher defaults() {
        List<String> lines = new ArrayList<String>();
        Collections.addAll(lines, DEFAULT_ENTRIES);
        return fromLines(lines);
    }

    public List<Entry> entries() {
        return entries;
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /** Search strings for {@code findAccessibilityNodeInfosByText}. Length at least 4. */
    public List<String> textQueries() {
        List<String> queries = new ArrayList<String>();
        for (int i = 0; i < entries.size(); i++) {
            String token = entries.get(i).token;
            if (token.length() >= 4 && !queries.contains(token)) {
                queries.add(token);
            }
        }
        return queries;
    }

    /**
     * @return true when {@code text} contains an allowlisted channel ID, @handle,
     *         or (if the text is short or equal to the entry) display name
     */
    public boolean matches(String text) {
        String hay = normalizeHaystack(text);
        if (hay.isEmpty() || entries.isEmpty()) return false;
        boolean shortText = hay.length() <= SHORT_TEXT_MAX;
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (entry.token.isEmpty()) continue;
            if (entry.channelId && containsToken(hay, entry.token)) return true;
            // @handle entries also match the bare handle anywhere. Display names do not,
            // so a long title that merely mentions the name is not enough.
            if (entry.explicitHandle && containsToken(hay, entry.token)) return true;
            if (entry.singleToken && containsToken(hay, "@" + entry.token)) return true;
            if (hay.equals(entry.token)) return true;
            if (shortText && containsToken(hay, entry.token)) return true;
        }
        return false;
    }

    /** The whole node text is an @handle that is not allowlisted. */
    public boolean isExactForeignHandle(String text) {
        if (text == null) return false;
        String trimmed = clean(text);
        if (!EXACT_HANDLE.matcher(trimmed).matches()) return false;
        return !matches(trimmed);
    }

    /**
     * Pulls the channel name out of subscribe / open-channel labels in English or Korean.
     * Returns null when the string is not one of those labels or the captured name
     * does not look like a channel name or @handle.
     */
    public static String extractAttributedChannel(String text) {
        String trimmed = clean(text);
        if (trimmed.isEmpty()) return null;
        for (int i = 0; i < ATTRIBUTION.length; i++) {
            Matcher matcher = ATTRIBUTION[i].matcher(trimmed);
            if (!matcher.matches()) continue;
            String captured = matcher.group(1).replaceAll("[\\s.!,，。]+$", "").trim();
            if (captured.isEmpty()) continue;
            if (looksLikeChannelName(captured) || EXACT_HANDLE.matcher(captured).matches()
                    || captured.matches("[A-Za-z0-9._-]{3,30}")) {
                return captured;
            }
        }
        return null;
    }

    /**
     * View IDs that typically carry the channel name on a Shorts overlay.
     * Containers, counts, and the bottom-bar avatar are excluded so a preloaded
     * shelf or the signed-in account chip is not treated as the playing channel.
     */
    public static boolean isNameBearingViewId(String viewId) {
        if (viewId == null || viewId.isEmpty()) return false;
        String id = viewId.toLowerCase(Locale.ROOT);
        int slash = id.lastIndexOf('/');
        if (slash >= 0 && slash < id.length() - 1) id = id.substring(slash + 1);
        if (containsAny(id, EXCLUDE_ID_PARTS)) return false;
        if (containsAny(id, NAME_ID_PARTS)) return true;
        return containsAny(id, REEL_AVATAR_PARTS)
                && (id.contains("reel") || id.contains("short"));
    }

    /** Plain channel-name text, not a button label or a subscriber/view count. */
    public static boolean looksLikeChannelName(String raw) {
        String text = clean(raw);
        if (text.length() < 2 || text.length() > 50) return false;
        if (text.indexOf('\n') >= 0) return false;
        if (isIgnoredLabel(text)) return false;
        if (isMetrics(text)) return false;
        int letters = 0;
        int digits = 0;
        int spaces = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetter(cp)) letters++;
            else if (Character.isDigit(cp)) digits++;
            else if (cp == ' ') spaces++;
            i += Character.charCount(cp);
        }
        if (letters < 2) return false;
        if (digits > letters) return false;
        return spaces <= 4;
    }

    public static boolean isIgnoredLabel(String raw) {
        String label = normalizeLabel(raw);
        return !label.isEmpty() && IGNORED_LABELS.contains(label);
    }

    public static List<String> splitLines(String raw) {
        List<String> lines = new ArrayList<String>();
        if (raw == null || raw.isEmpty()) return lines;
        String[] parts = raw.split("\n", -1);
        for (int i = 0; i < parts.length; i++) {
            String line = clean(parts[i]);
            if (line.isEmpty() || line.charAt(0) == '#') continue;
            lines.add(line);
        }
        return lines;
    }

    public static List<Entry> parse(List<String> lines) {
        List<Entry> out = new ArrayList<Entry>();
        if (lines == null) return out;
        for (int i = 0; i < lines.size(); i++) {
            Entry entry = parseEntry(lines.get(i));
            if (entry != null) out.add(entry);
        }
        return out;
    }

    public static String clean(String raw) {
        if (raw == null) return "";
        return raw.replace('\u00A0', ' ').replace('\uFF20', '@').trim();
    }

    static boolean containsToken(String haystack, String needle) {
        if (needle == null || needle.isEmpty() || haystack == null) return false;
        int from = 0;
        while (from <= haystack.length() - needle.length()) {
            int index = haystack.indexOf(needle, from);
            if (index < 0) return false;
            if (boundaryBefore(haystack, index) && boundaryAfter(haystack, index + needle.length())) {
                return true;
            }
            from = index + 1;
        }
        return false;
    }

    public static final class Entry {
        public final String display;
        public final String token;
        public final boolean channelId;
        public final boolean singleToken;
        /** True when the saved line was written as {@code @handle}. */
        public final boolean explicitHandle;

        Entry(String display, String token, boolean channelId, boolean singleToken, boolean explicitHandle) {
            this.display = display;
            this.token = token;
            this.channelId = channelId;
            this.singleToken = singleToken;
            this.explicitHandle = explicitHandle;
        }
    }

    private static Entry parseEntry(String line) {
        String display = clean(line);
        if (display.isEmpty() || display.charAt(0) == '#') return null;
        String token = display.toLowerCase(Locale.ROOT);
        if (token.charAt(0) == '@') {
            token = token.substring(1).trim();
        }
        if (token.isEmpty()) return null;
        boolean explicitHandle = display.charAt(0) == '@';
        boolean channelId = CHANNEL_ID.matcher(token).matches();
        boolean singleToken = token.indexOf(' ') < 0 && token.indexOf('\t') < 0;
        return new Entry(display, token, channelId, singleToken, explicitHandle);
    }

    private static String normalizeHaystack(String text) {
        return clean(text).toLowerCase(Locale.ROOT);
    }

    private static boolean boundaryBefore(String haystack, int index) {
        return index <= 0 || !isIdentChar(haystack.charAt(index - 1));
    }

    private static boolean boundaryAfter(String haystack, int index) {
        return index >= haystack.length() || !isIdentChar(haystack.charAt(index));
    }

    private static boolean isIdentChar(char c) {
        return (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9')
                || c == '_' || c == '.' || c == '-' || c == '@';
    }

    private static boolean containsAny(String id, String[] parts) {
        for (int i = 0; i < parts.length; i++) {
            if (id.contains(parts[i])) return true;
        }
        return false;
    }

    private static boolean isMetrics(String text) {
        String lower = text.toLowerCase(Locale.ROOT);
        if (lower.contains("subscriber") || lower.contains("구독자") || lower.contains("조회")) {
            return true;
        }
        if (lower.contains(" views") || lower.contains(" watching") || lower.contains("명 시청")) {
            return true;
        }
        return text.matches("[0-9][0-9\\s.,kKmMbB만억명회+]*%?");
    }

    private static String normalizeLabel(String raw) {
        String text = clean(raw);
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (!Character.isWhitespace(cp) && cp != '.' && cp != '!' && cp != ',') {
                sb.appendCodePoint(Character.toLowerCase(cp));
            }
            i += Character.charCount(cp);
        }
        return sb.toString();
    }

    private static Set<String> ignoredLabels() {
        String[] labels = {
                "subscribe", "subscribed", "unsubscribe", "join", "joined", "shorts", "short",
                "home", "library", "you", "subscriptions", "like", "liked", "dislike",
                "comment", "comments", "share", "remix", "more", "search", "back", "next",
                "previous", "mute", "unmute", "play", "pause", "shop", "send", "report",
                "description", "audio", "save", "saved", "follow", "following", "live",
                "video", "videos", "watch", "later", "profile", "profilephoto",
                "profilepicture", "avatar", "channel",
                "구독", "구독중", "구독됨", "구독취소", "구독하기", "가입", "쇼츠", "홈", "나",
                "보관함", "좋아요", "싫어요", "댓글", "공유", "리믹스", "더보기", "검색", "뒤로",
                "음소거", "일시정지", "재생", "저장", "나중에", "신고", "설명", "채널", "팔로우",
                "팔로잉", "라이브", "동영상", "조회수", "구독자", "프로필", "프로필사진", "아바타",
                "채널사진", "채널이미지"
        };
        Set<String> set = new HashSet<String>();
        for (int i = 0; i < labels.length; i++) set.add(labels[i]);
        return set;
    }
}

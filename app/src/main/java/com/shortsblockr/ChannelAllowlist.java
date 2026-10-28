package com.shortsblockr;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.List;

/**
 * Allowlist stored in {@code ShortsBlockerPrefs / channel_allowlist}.
 * One channel per line (display name, {@code @handle}, or {@code UC…} id).
 * When the key is absent, {@code res/values/arrays.xml} is used, then
 * {@link AllowlistMatcher#DEFAULT_ENTRIES}. An empty saved value blocks every Short.
 */
public final class ChannelAllowlist {

    public static final String PREFS_NAME = "ShortsBlockerPrefs";
    public static final String KEY_ALLOWLIST = "channel_allowlist";

    private final List<String> lines;
    private final AllowlistMatcher matcher;
    private final boolean usingDefaults;

    private ChannelAllowlist(List<String> lines, boolean usingDefaults) {
        this.lines = lines;
        this.matcher = AllowlistMatcher.fromLines(lines);
        this.usingDefaults = usingDefaults;
    }

    public static ChannelAllowlist load(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        if (!prefs.contains(KEY_ALLOWLIST)) {
            return new ChannelAllowlist(defaultLines(context), true);
        }
        return new ChannelAllowlist(AllowlistMatcher.splitLines(prefs.getString(KEY_ALLOWLIST, "")), false);
    }

    public static void save(Context context, String raw) {
        String stored = join(AllowlistMatcher.splitLines(raw));
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ALLOWLIST, stored)
                .apply();
    }

    public static void restoreDefaults(Context context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .remove(KEY_ALLOWLIST)
                .apply();
    }

    public boolean matches(String text) {
        return matcher.matches(text);
    }

    public boolean isExactForeignHandle(String text) {
        return matcher.isExactForeignHandle(text);
    }

    public boolean isEmpty() {
        return matcher.isEmpty();
    }

    public boolean isUsingDefaults() {
        return usingDefaults;
    }

    public List<String> textQueries() {
        return matcher.textQueries();
    }

    public String editorText() {
        return join(lines);
    }

    private static List<String> defaultLines(Context context) {
        String[] fromRes = context.getResources().getStringArray(R.array.default_channel_allowlist);
        if (fromRes.length > 0) {
            return Arrays.asList(fromRes);
        }
        return Arrays.asList(AllowlistMatcher.DEFAULT_ENTRIES);
    }

    private static String join(List<String> lines) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(lines.get(i));
        }
        return sb.toString();
    }
}

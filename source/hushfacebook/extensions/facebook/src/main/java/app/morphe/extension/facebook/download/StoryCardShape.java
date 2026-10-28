/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;

/**
 * Debug logging only: the shape of a story card whose save found no video, so a shared video's
 * story can be read where it keeps the video (#207). Its card has no tree with a playable address,
 * Facebook titles its own item Save photo, and the save took the still, play button and all.
 *
 * <p>Each GraphQL tree the card holds is logged through its own debug text, and the card's media
 * object field by field. Every value goes: keys, braces, short numbers, booleans and words written
 * in capitals stay, an address becomes its kind ({@code <video-url>}, {@code <image-url>} and the
 * like), a long number or hex run becomes {@code <id>}, and any other text becomes
 * {@code <text>}. A type name after {@code __typename} stays too. With debug logging off it returns
 * at once, before the expensive text is asked for.
 */
final class StoryCardShape {
    static final String PREFIX = "Story card shape ";
    static final int PART = 800;
    /** A card holds a few trees. More than this is a walk gone wrong. */
    static final int MAX_TREES = 6;
    /** Per tree: 32 KB of shape is far past where the video would sit. */
    static final int MAX_PARTS = 40;

    private static final Pattern KEY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");
    private static final Pattern CAPS = Pattern.compile("[A-Z][A-Z0-9_]{0,63}");
    private static final Pattern TYPE_NAME = Pattern.compile("[A-Z][A-Za-z0-9_]{0,63}");
    private static final Pattern ID_LIKE = Pattern.compile(".*([0-9]{5,}|[0-9a-fA-F]{8,}).*");
    private static final String DEBUG_TEXT = "toExpensiveHumanReadableDebugString";
    private static final String TYPE = "getTypeName";

    private StoryCardShape() {
    }

    private static boolean enabled() {
        try {
            return Utils.settingsReady() && BaseSettings.DEBUG.get();
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Log [card]'s shape, and [media]'s when it isn't null. Never throws. */
    static void log(Object card, Object media) {
        if (card == null || !enabled()) return;
        try {
            debug(PREFIX + "of " + card.getClass().getName());
            if (media != null) debug(PREFIX + "media " + fieldsOf(media));
            List<Object> trees = PostDetails.treesIn(card);
            debug(PREFIX + "trees " + trees.size());
            int count = 0;
            for (Object tree : trees) {
                if (++count > MAX_TREES) break;
                logTree(count, tree);
            }
        } catch (Throwable unreadable) {
            debug(PREFIX + "unreadable: " + unreadable.getClass().getSimpleName());
        }
    }

    private static void logTree(int number, Object tree) {
        if (!PostDetails.isLiveTree(tree)) {
            debug(PREFIX + "tree " + number + " released");
            return;
        }
        String type = text(tree, TYPE);
        String raw = text(tree, DEBUG_TEXT);
        String shape = raw == null ? "no debug text" : redact(raw);
        int parts = Math.min(MAX_PARTS, (shape.length() + PART - 1) / PART);
        String head = PREFIX + "tree " + number + " " + (type == null || !TYPE_NAME.matcher(type).matches() ? "?" : type);
        for (int i = 0; i < parts; i++) {
            String piece = shape.substring(i * PART, Math.min(shape.length(), (i + 1) * PART));
            debug(head + " part " + (i + 1) + "/" + parts + ": " + piece);
        }
    }

    private static String text(Object tree, String method) {
        try {
            Object value = tree.getClass().getMethod(method).invoke(tree);
            return value instanceof String ? (String) value : null;
        } catch (Throwable unavailable) {
            return null;
        }
    }

    /** Each declared field of [media] with the kind of what it holds. */
    static String fieldsOf(Object media) {
        StringBuilder out = new StringBuilder(media.getClass().getName()).append(" {");
        for (Class<?> type = media.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                Object value;
                try {
                    field.setAccessible(true);
                    value = field.get(media);
                } catch (Throwable unreadable) {
                    continue;
                }
                out.append(' ').append(field.getName()).append('=').append(kindOf(value)).append(',');
            }
        }
        return out.append(" }").toString();
    }

    private static String kindOf(Object value) {
        if (value == null) return "null";
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) {
            String digits = value.toString();
            return digits.replace("-", "").length() >= 5 ? "<id>" : digits;
        }
        if (value instanceof CharSequence) return valueKind(value.toString(), false);
        return value.getClass().getName();
    }

    /**
     * [text] with every value taken out, its whitespace folded. Pure, for the tests.
     */
    static String redact(String text) {
        StringBuilder out = new StringBuilder(Math.min(text.length(), 1 << 16));
        String lastKey = null;
        int i = 0;
        int n = text.length();
        while (i < n) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                while (i < n && Character.isWhitespace(text.charAt(i))) i++;
                if (out.length() > 0 && out.charAt(out.length() - 1) != ' ') out.append(' ');
                continue;
            }
            if (c == '"') {
                int end = i + 1;
                StringBuilder content = new StringBuilder();
                while (end < n && text.charAt(end) != '"') {
                    if (text.charAt(end) == '\\' && end + 1 < n) end++;
                    content.append(text.charAt(end));
                    end++;
                }
                i = Math.min(n, end + 1);
                String inside = content.toString();
                if (isKeyAhead(text, i) && KEY.matcher(inside).matches()) {
                    out.append('"').append(inside).append('"');
                    lastKey = inside;
                } else {
                    out.append(isTypeKey(lastKey) && TYPE_NAME.matcher(inside).matches()
                        ? '"' + inside + '"' : valueKind(inside, true));
                    lastKey = null;
                }
                continue;
            }
            if (startsUrl(text, i)) {
                int end = i;
                while (end < n && !Character.isWhitespace(text.charAt(end)) && ",}]\"'".indexOf(text.charAt(end)) < 0) end++;
                out.append(valueKind(text.substring(i, end), false));
                i = end;
                lastKey = null;
                continue;
            }
            if (Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.') {
                int end = i;
                while (end < n && (Character.isLetterOrDigit(text.charAt(end)) || "_-.".indexOf(text.charAt(end)) >= 0)) end++;
                String word = text.substring(i, end);
                i = end;
                if (isKeyAhead(text, i) && KEY.matcher(word).matches()) {
                    out.append(word);
                    lastKey = word;
                } else {
                    out.append(bareKind(word, lastKey));
                    lastKey = null;
                }
                continue;
            }
            out.append(c);
            i++;
        }
        return collapse(out.toString());
    }

    /** Whether the next mark after [from] opens a value or a body: the word before it names one. */
    private static boolean isKeyAhead(String text, int from) {
        int i = from;
        while (i < text.length() && text.charAt(i) == ' ') i++;
        return i < text.length() && ":={(".indexOf(text.charAt(i)) >= 0;
    }

    private static boolean isTypeKey(String key) {
        return key != null && (key.equals("__typename") || key.equals("typename") || key.equals("type"));
    }

    private static boolean startsUrl(String text, int at) {
        return text.regionMatches(true, at, "http://", 0, 7) || text.regionMatches(true, at, "https://", 0, 8);
    }

    private static String bareKind(String word, String lastKey) {
        if (word.equals("true") || word.equals("false") || word.equals("null")) return word;
        if (word.matches("-?[0-9]+(\\.[0-9]+)?")) return word.replace("-", "").replace(".", "").length() >= 5 ? "<id>" : word;
        if (ID_LIKE.matcher(word).matches()) return "<id>";
        if (CAPS.matcher(word).matches()) return word;
        if (isTypeKey(lastKey) && TYPE_NAME.matcher(word).matches()) return word;
        return "<text>";
    }

    /** The kind of a value that was text: an address's kind, an id, a word in capitals, or text. */
    static String valueKind(String value, boolean quoted) {
        if (value.isEmpty()) return quoted ? "\"\"" : "<text>";
        if (RenditionPicker.isHttpUrl(value)) return urlKind(value);
        if (value.contains("<MPD")) return "<manifest>";
        if (value.matches("-?[0-9]+(\\.[0-9]+)?")) {
            return value.replace("-", "").replace(".", "").length() >= 5 ? "<id>" : value;
        }
        if (ID_LIKE.matcher(value).matches()) return "<id>";
        if (CAPS.matcher(value).matches()) return quoted ? '"' + value + '"' : value;
        return "<text>";
    }

    /** What an address points at, by its path and host, thumbnails included. */
    private static String urlKind(String url) {
        String lower = url.toLowerCase(Locale.US);
        if (RenditionPicker.isManifest(url)) return "<manifest-url>";
        int query = lower.indexOf('?');
        String path = query < 0 ? lower : lower.substring(0, query);
        if (path.endsWith(".mp4") || path.endsWith(".m4v") || path.endsWith(".mov") || lower.contains("progressive")) {
            return "<video-url>";
        }
        if (path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png") || path.endsWith(".webp")
            || path.endsWith(".heic") || path.endsWith(".gif")) {
            return "<image-url>";
        }
        int start = lower.indexOf("://") + 3;
        int end = start;
        while (end < lower.length() && "/?#:".indexOf(lower.charAt(end)) < 0) end++;
        String host = lower.substring(start, end);
        if (host.endsWith("fbcdn.net")) return "<cdn-url>";
        if (host.equals("facebook.com") || host.endsWith(".facebook.com") || host.equals("fb.watch")) return "<facebook-url>";
        return "<url>";
    }

    /** Runs of <text> fold into one, so a long caption costs one word of the log. */
    private static String collapse(String shape) {
        return shape.replaceAll("<text>(?: ?<text>)+", "<text>").trim();
    }

    private static void debug(String line) {
        try {
            Logger.diagnosticDebug(DiagnosticCategory.DOWNLOADS, MediaDownload.SOURCE, () -> line);
        } catch (Throwable ignored) {
            // A log line must never reach the tap that asked for the save.
        }
    }
}

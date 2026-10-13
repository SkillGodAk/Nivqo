/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import androidx.annotation.Nullable;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a comment's words carry a link to an online shop: the Shopee, Lazada or Amazon links
 * people paste under reels to earn a commission (#118), with or without https:// in front.
 *
 * <p>A host counts when one of its labels names a shop that runs a site per country
 * ({@link #SHOP_NAMES}: shopee.vn, s.shopee.co.id, lazada.com.ph), or when it is, or ends with, a
 * host that only serves shop or affiliate links ({@link #SHOP_HOSTS}: shp.ee, amzn.to). A shop's
 * name written as a plain word, like "I got it on Shopee", has no dot and isn't a link. Nor is one
 * with no space after a full stop, like "Got it on Shopee.Fast shipping": a host with no https://,
 * www. or path counts only when it ends in a domain ending shop links use ({@link #LINK_ENDINGS}),
 * typed in one case.
 */
final class ShopLinks {
    /** Shops with a site per country, matched as any label of a host. */
    static final Set<String> SHOP_NAMES = new HashSet<>(Arrays.asList(
            "shopee", "lazada", "tokopedia", "aliexpress", "temu", "shein"));

    /** Hosts that only serve shop or affiliate links, matched as the host or a host under it. */
    static final Set<String> SHOP_HOSTS = new HashSet<>(Arrays.asList(
            "shp.ee", "shope.ee", "lzd.co", "tokopedia.link", "amzn.to", "amzn.eu", "amzn.asia", "a.co",
            "shop.tiktok.com"));

    /**
     * The endings a shop host written bare, with no https://, www. or path, has to have: the
     * general ones and those of the countries the shops above sell in.
     */
    static final Set<String> LINK_ENDINGS = new HashSet<>(Arrays.asList(
            "com", "net", "org", "co", "io", "me", "app", "page", "link", "shop", "store", "online",
            "site", "xyz", "info", "biz", "asia", "eu", "to", "ee", "us",
            "id", "vn", "ph", "sg", "my", "th", "tw", "hk", "cn", "jp", "kr", "in", "pk", "bd", "lk", "np",
            "br", "mx", "cl", "ar", "pe",
            "pl", "es", "fr", "de", "it", "uk", "nl", "be", "at", "ch", "se", "ie", "pt", "cz", "ro", "hu", "gr",
            "ca", "au", "nz", "za", "ng", "ke", "eg", "sa", "ae", "tr", "ru", "ua"));

    /**
     * A host as it shows in text: labels of letters, digits and hyphens joined by dots, after an
     * optional scheme (group 1). The host is group 2.
     */
    private static final Pattern HOST = Pattern.compile(
            "(?i)(?<![\\p{L}\\p{N}@.-])(https?://)?((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]{0,61}[\\p{L}\\p{N}])?\\.)+\\p{L}{2,24})(?![\\p{L}\\p{N}-])");

    private ShopLinks() {
    }

    /** Whether [text] holds a link to a shop. Never throws. */
    static boolean holds(@Nullable String text) {
        if (text == null || text.indexOf('.') < 0) return false;
        Matcher found = HOST.matcher(text);
        while (found.find()) {
            String host = found.group(2);
            if (!isShop(host.toLowerCase(Locale.ROOT))) continue;
            boolean path = found.end() < text.length() && text.charAt(found.end()) == '/';
            if (found.group(1) != null || path || host.regionMatches(true, 0, "www.", 0, 4) || linkEnding(host)) {
                return true;
            }
        }
        return false;
    }

    /** Whether bare [host] ends the way a link does: a known ending, all lower case or all upper case. */
    private static boolean linkEnding(String host) {
        String ending = host.substring(host.lastIndexOf('.') + 1);
        String lower = ending.toLowerCase(Locale.ROOT);
        boolean oneCase = ending.equals(lower) || ending.equals(ending.toUpperCase(Locale.ROOT));
        return oneCase && LINK_ENDINGS.contains(lower);
    }

    /** Whether [host], in lower case, is a shop's. */
    static boolean isShop(String host) {
        if (host.startsWith("www.")) host = host.substring(4);
        for (String label : host.split("\\.")) {
            if (SHOP_NAMES.contains(label)) return true;
        }
        for (String shop : SHOP_HOSTS) {
            if (host.equals(shop) || host.endsWith("." + shop)) return true;
        }
        return false;
    }
}

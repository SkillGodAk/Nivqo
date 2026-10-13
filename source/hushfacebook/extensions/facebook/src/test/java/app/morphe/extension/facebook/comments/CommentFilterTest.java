/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.facebook.graphql.model.GraphQLComment;
import com.facebook.graphql.model.GraphQLStory;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.security.MessageDigest;

import app.morphe.extension.facebook.settings.FamilyNames;
import app.morphe.extension.facebook.settings.Settings;
import app.morphe.extension.shared.SettingsContextRule;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.HushfacebookPause;
import app.morphe.extension.shared.settings.PauseForTests;

/**
 * Hide comments with shop links and Hide comments with your words (#118): the comment section
 * asks about each comment before drawing it, and a comment whose words link to a shop, or hold a
 * phrase from Words to hide and none from the keep list, is left out while its switch is on. Off,
 * paused, a comment with no words, or anything that isn't a comment, and it's drawn.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
public class CommentFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void start() {
        HookStatus.clear();
        CommentText.cachedMembers = null;
    }

    @After
    public void restore() {
        PauseForTests.resume();
        Settings.HIDE_SHOP_LINK_COMMENTS.resetToDefault();
        Settings.HIDE_COMMENTS_WITH_WORDS.resetToDefault();
        Settings.HIDDEN_WORDS.resetToDefault();
        Settings.KEPT_WORDS.resetToDefault();
        Settings.POST_WORDS_WHOLE_WORDS.resetToDefault();
        CommentText.cachedMembers = null;
        HookStatus.clear();
    }

    private static String statusLine() {
        for (String line : HookStatus.report()) {
            if (line.startsWith(FamilyNames.COMMENT_SHEET_OPTIONS + ":")) return line;
        }
        return null;
    }

    @Test
    public void shopLinksAreHostsNamingAShopWithOrWithoutTheirScheme() {
        assertTrue(ShopLinks.holds("Buy here https://s.shopee.vn/AbC123 cheap"));
        assertTrue(ShopLinks.holds("shopee.co.id/product/1/2"));
        assertTrue(ShopLinks.holds("Link: www.lazada.com.ph/products/x.html"));
        assertTrue(ShopLinks.holds("https://amzn.to/3xYz"));
        assertTrue(ShopLinks.holds("SHP.EE/abc"));
        assertTrue(ShopLinks.holds("go.shop.tiktok.com/view/1"));
        assertFalse("a shop named as a word isn't a link", ShopLinks.holds("I got it on Shopee, so good"));
        assertFalse("another site", ShopLinks.holds("see https://example.com/shopee"));
        assertFalse("a mail address", ShopLinks.holds("write to help@shopee.com"));
        assertFalse("a name inside a longer label", ShopLinks.holds("myshopeeblog.com"));
        assertFalse("no words", ShopLinks.holds(null));
        assertFalse("an abbreviation", ShopLinks.holds("e.g. this one"));
    }

    /**
     * Amazon's share links (a.co/d/...) and Shopee's affiliate ones (shope.ee/...) count too. A shop
     * written with no space after a full stop isn't a link: "Shopee.Fast" read as a host before.
     * A bare host with a real ending, in one case, still counts.
     */
    @Test
    public void shortLinksCountAndAMissingSpaceDoesNot() {
        assertTrue(ShopLinks.holds("got mine here https://a.co/d/0aBcDeF"));
        assertTrue(ShopLinks.holds("a.co/d/0aBcDeF"));
        assertTrue(ShopLinks.holds("shope.ee/9zKsAbC"));
        assertTrue("a bare host with a real ending", ShopLinks.holds("on shopee.vn now"));
        assertTrue("a bare host in capitals", ShopLinks.holds("ON SHOPEE.PH NOW"));
        assertFalse("no space after a full stop", ShopLinks.holds("Got mine on Shopee.Fast shipping"));
        assertFalse("a capital after the stop", ShopLinks.holds("Ordered from Lazada.It came today"));
        assertFalse("not a link ending", ShopLinks.holds("loved temu.never again"));
    }

    @Test
    public void theTypeTagIsTextWithEntitiesNamesMd5() throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest("TextWithEntities".getBytes("UTF-8"));
        int tag = ((digest[0] & 0xff) << 24) | ((digest[1] & 0xff) << 16) | ((digest[2] & 0xff) << 8) | (digest[3] & 0xff);
        assertEquals(tag, CommentText.TEXT_TYPE_TAG);
        assertEquals("582's body accessor asks for this key", 0x2e39a2, CommentText.BODY_KEY);
    }

    @Test
    public void offNothingIsLeftOutAndNoCommentIsRead() {
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("https://s.shopee.vn/x")));
        // Only the hook itself is found: the comment's text reader was never asked.
        assertTrue(statusLine(), statusLine().contains("invoked 1, 1 found, 0 missing"));
    }

    @Test
    public void onACommentLinkingToAShopIsLeftOutAndCounted() {
        Settings.HIDE_SHOP_LINK_COMMENTS.save(true);
        assertTrue(CommentSheetOptions.hidesComment(new GraphQLComment("Mua ở đây https://s.shopee.vn/9zKs")));
        assertFalse("a comment without a link", CommentSheetOptions.hidesComment(new GraphQLComment("So cute!")));
        assertFalse("a comment with no body", CommentSheetOptions.hidesComment(new GraphQLComment(null)));
        assertFalse("a post isn't a comment", CommentSheetOptions.hidesComment(new GraphQLStory()));
        assertFalse("nothing handed over", CommentSheetOptions.hidesComment(null));
        String line = statusLine();
        assertTrue("the hook and the text reader are found: " + line, line.contains("invoked 5, 2 found, 0 missing"));
        assertTrue(line, line.contains(CommentSheetOptions.SHOP_LINK_HIDDEN + " 1"));
        assertFalse(line, line.contains(CommentSheetOptions.WORDS_HIDDEN));
    }

    @Test
    public void onACommentWithAWordToHideIsLeftOutUnlessAKeepWordIsThere() {
        Settings.HIDE_COMMENTS_WITH_WORDS.save(true);
        Settings.HIDDEN_WORDS.save("spoiler");
        Settings.KEPT_WORDS.save("no worries");
        assertTrue(CommentSheetOptions.hidesComment(new GraphQLComment("Huge SPOILER: he wins")));
        assertFalse("a keep word wins", CommentSheetOptions.hidesComment(new GraphQLComment("spoiler, no worries")));
        assertFalse("another comment", CommentSheetOptions.hidesComment(new GraphQLComment("Loved it")));
        assertFalse("the shop switch is off", CommentSheetOptions.hidesComment(new GraphQLComment("shopee.vn/x")));
        assertTrue(statusLine(), statusLine().contains(CommentSheetOptions.WORDS_HIDDEN + " 1"));

        Settings.HIDDEN_WORDS.save("");
        assertFalse("an empty list hides nothing", CommentSheetOptions.hidesComment(new GraphQLComment("spoiler")));
    }

    @Test
    public void pausedEveryCommentIsDrawn() {
        Settings.HIDE_SHOP_LINK_COMMENTS.save(true);
        Settings.HIDE_COMMENTS_WITH_WORDS.save(true);
        Settings.HIDDEN_WORDS.save("spoiler");
        PauseForTests.pause(HushfacebookPause.Reason.SWITCH);
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("shopee.vn/x")));
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("a spoiler")));
    }

    @Test
    public void aBuildMissingAReaderDrawsEveryCommentAndSaysWhatIsMissing() {
        Settings.HIDE_SHOP_LINK_COMMENTS.save(true);
        CommentText.Members found = CommentText.members();
        CommentText.cachedMembers = new CommentText.Members(found.comment, found.text, null, found.cachedString);
        assertNull(CommentText.body(new GraphQLComment("shopee.vn/x")));
        assertFalse(CommentSheetOptions.hidesComment(new GraphQLComment("shopee.vn/x")));
        assertTrue(statusLine(), statusLine().contains("getCachedModel"));
    }
}

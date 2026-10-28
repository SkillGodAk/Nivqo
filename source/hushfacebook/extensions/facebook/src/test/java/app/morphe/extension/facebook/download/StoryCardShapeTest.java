/*
 * Copyright 2026 Hushfacebook contributors
 * https://github.com/SysAdminDoc/Hushfacebook
 */
package app.morphe.extension.facebook.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * The shape a story card's trees are logged with (#207): keys, type names and words in capitals
 * stay, addresses become their kind, ids become {@code <id>}, and every other value, names and
 * captions included, becomes {@code <text>}, in either way a tree may print itself.
 */
public class StoryCardShapeTest {

    @Test
    public void jsonKeepsKeysAndTypesAndDropsValues() {
        String tree = "{\"__typename\": \"Story\", \"id\": \"1234567890123\",\n"
            + "  \"attachments\": [{\"media\": {\"__typename\": \"Video\",\n"
            + "    \"playable_url\": \"https://video.xx.fbcdn.net/v/t42/abc.mp4?efg=1&oh=00_AfBc\",\n"
            + "    \"title\": \"My trip to Paris with Jane Doe\", \"style\": \"SHARED_POST\", \"width\": 1080}}]}";

        assertEquals("{\"__typename\": \"Story\", \"id\": <id>, \"attachments\": [{\"media\": {\"__typename\": \"Video\", "
                + "\"playable_url\": <video-url>, \"title\": <text>, \"style\": \"SHARED_POST\", \"width\": 1080}}]}",
            StoryCardShape.redact(tree));
    }

    @Test
    public void bareTextKeepsKeysAndDropsValues() {
        String tree = "Story { id: 1234567890, message: hello there John Smith, "
            + "url: https://scontent.xx.fbcdn.net/v/t39/a.jpg?stp=x, link: https://www.facebook.com/jane.doe/videos/987654321 }";

        assertEquals("Story { id: <id>, message: <text>, url: <image-url>, link: <facebook-url> }",
            StoryCardShape.redact(tree));
    }

    @Test
    public void namesAndHandlesNeverSurvive() {
        String shape = StoryCardShape.redact("{\"name\": \"Jane Doe\", \"username\": \"jane.doe.77\", "
            + "\"token\": \"a1b2c3d4e5f6a7b8\", \"owner\": {\"name\": jane}}");

        assertFalse(shape, shape.contains("Jane"));
        assertFalse(shape, shape.contains("jane"));
        assertFalse(shape, shape.contains("a1b2"));
        assertTrue(shape, shape.contains("\"owner\": {\"name\": <text>}"));
    }

    @Test
    public void addressKinds() {
        assertEquals("<video-url>", StoryCardShape.valueKind("https://video.xx.fbcdn.net/v/t42/a.mp4", true));
        assertEquals("<video-url>", StoryCardShape.valueKind("https://video.xx.fbcdn.net/v/t42/a?x=progressive", true));
        assertEquals("<image-url>", StoryCardShape.valueKind("https://scontent.xx.fbcdn.net/v/t15/thumb.jpg?stp=dst-jpg_s320x320", true));
        assertEquals("<cdn-url>", StoryCardShape.valueKind("https://scontent.xx.fbcdn.net/v/t39/abc", true));
        assertEquals("<manifest-url>", StoryCardShape.valueKind("https://video.xx.fbcdn.net/v/a.mpd", true));
        assertEquals("<url>", StoryCardShape.valueKind("https://example.com/a", true));
        assertEquals("<manifest>", StoryCardShape.valueKind("<?xml version=\"1.0\"?><MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\">", true));
    }

    /** A media object as 583's X.B76 holds it: plain fields of text, numbers and switches. */
    static final class Media {
        final String a = video();
        final String b = "Jane Doe";
        final int c = 720;
        final long d = 1234567890L;
        final boolean e = true;
        final String f = null;

        private static String video() {
            return "https://video.xx.fbcdn.net/v/t42/a.mp4";
        }
    }

    @Test
    public void mediaFieldsShowTheirKinds() {
        String shape = StoryCardShape.fieldsOf(new Media());

        for (String field : new String[] {" a=<video-url>,", " b=<text>,", " c=720,", " d=<id>,", " e=true,", " f=null,"}) {
            assertTrue(shape, shape.contains(field));
        }
        assertFalse(shape, shape.contains("Jane"));
    }

    @Test
    public void logIsSilentWithoutSettings() {
        // Before the settings are ready debug logging counts as off, and nothing is read.
        StoryCardShape.log(new Object(), new Object());
    }
}

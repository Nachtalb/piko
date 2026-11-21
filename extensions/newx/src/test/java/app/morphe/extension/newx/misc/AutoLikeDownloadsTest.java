package app.morphe.extension.newx.misc;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public final class AutoLikeDownloadsTest {
    @Test
    public void unpatchedBuildTracksNothing() {
        Object postContext = new Object();
        AutoLikeDownloads.track(postContext, new Object());

        assertNull(AutoLikeDownloads.takePending(postContext));
    }

    @Test
    public void likedPostIsRecognized() {
        assertTrue(AutoLikeDownloads.hasUnlike(List.of(action("Reply"), action("Unfavorite"))));
        assertFalse(AutoLikeDownloads.hasUnlike(List.of(action("Reply"), action("Favorite"))));
        assertFalse(AutoLikeDownloads.hasUnlike(null));
    }

    private static Object action(String name) {
        return new Object() {
            @Override
            public String toString() {
                return "InlineActionEntry(actionType=" + name + ", isEnabled=true)";
            }
        };
    }
}

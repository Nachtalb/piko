package app.morphe.extension.newx.misc;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.crimera.settings.SettingsRegistry;
import app.morphe.extension.newx.settings.NewXLogger;

/**
 * Likes a post once a download of its media has actually started. The like goes through X's own
 * post-actions repository, with the same call its like button makes.
 */
@SuppressWarnings("unused")
public final class AutoLikeDownloads {
    private static final String SETTING_ID = "newx.content.inline_download.auto_like";

    // App-wide singleton held by every inline action bar presenter.
    private static volatile Object repository;
    // Inline downloads only carry their filename context to the point they are queued.
    private static final Map<Object, Object> PENDING = Collections.synchronizedMap(new WeakHashMap<>());
    // The post model only shows the like after a round trip; never like the same post twice.
    private static final Map<Object, Boolean> LIKED = Collections.synchronizedMap(new WeakHashMap<>());

    private AutoLikeDownloads() {
    }

    static void remember(Object presenter) {
        if (repository != null || presenter == null || !patched()) return;
        try {
            repository = getPostActionRepository(presenter);
        } catch (RuntimeException exception) {
            NewXLogger.printException(() -> "Failed to read the NewX post-actions repository", exception);
        }
    }

    /** Links an inline download's filename context to its post until a file is queued. */
    static void track(Object postContext, Object post) {
        if (postContext != null && post != null && patched()) PENDING.put(postContext, post);
    }

    /** A file of the tracked inline download was queued or saved. */
    static void downloaded(Object postContext) {
        Object post = takePending(postContext);
        if (post != null) like(post);
    }

    static Object takePending(Object postContext) {
        return postContext == null ? null : PENDING.remove(postContext);
    }

    /** One of X's own download buttons saved media of this timeline post. */
    static void nativeDownloaded(Object timelinePost) {
        if (timelinePost == null || !patched()) return;
        try {
            like(InlineDownloadButton.contextualPostOf(timelinePost));
        } catch (RuntimeException exception) {
            NewXLogger.printException(() -> "Failed to resolve the downloaded NewX post", exception);
        }
    }

    private static void like(Object post) {
        if (post == null) return;
        try {
            if (!SettingsRegistry.getBooleanOrDefault(SETTING_ID, true)) return;
            Object repository = AutoLikeDownloads.repository;
            if (repository == null) {
                NewXLogger.printInfo(() -> "Auto-like skipped: no post-actions repository seen yet");
                return;
            }
            Object entries = getInlineActionEntries(InlineDownloadButton.canonicalPostOf(post));
            if (hasUnlike(entries) || LIKED.put(post, Boolean.TRUE) != null) return;
            likePost(repository, post);
        } catch (RuntimeException exception) {
            NewXLogger.printException(() -> "Failed to like the downloaded NewX post", exception);
        }
    }

    /** A liked post offers Unfavorite where an unliked one offers Favorite. */
    static boolean hasUnlike(Object entries) {
        if (!(entries instanceof Iterable<?> actions)) return false;
        for (Object action : actions) {
            if (action != null && "Unfavorite".equals(InlineActionFilter.inlineActionName(action))) return true;
        }
        return false;
    }

    // Bridges below are filled in at patch time.

    private static boolean patched() {
        return false;
    }

    private static Object getPostActionRepository(Object presenter) {
        throw unpatchedBridge("getPostActionRepository");
    }

    private static Object getInlineActionEntries(Object canonicalPost) {
        throw unpatchedBridge("getInlineActionEntries");
    }

    private static void likePost(Object repository, Object post) {
        throw unpatchedBridge("likePost");
    }

    private static IllegalStateException unpatchedBridge(String name) {
        return new IllegalStateException("NewX auto-like bridge was not patched: " + name);
    }
}

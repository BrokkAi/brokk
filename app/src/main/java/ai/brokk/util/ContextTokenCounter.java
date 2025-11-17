package ai.brokk.util;

import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import javax.swing.*;

/**
 * Global helper for caching approximate token counts per ContextFragment instance.
 *
 * <p>Cache keys are the fragment instances themselves (not their string ids).
 * Call {@link #countTokens(ContextFragment)} to obtain or compute a token count, and
 * {@link #retainFragments(Context)} from {@link ai.brokk.ContextManager} when the
 * live context changes to drop entries for no-longer-referenced fragments.
 */
public final class ContextTokenCounter {

    private static final ConcurrentMap<ContextFragment, Integer> tokensByFragment = new ConcurrentHashMap<>();

    private ContextTokenCounter() {}

    /**
     * Returns the approximate token count for the given fragment, computing and caching it
     * if necessary.
     */
    public static int countTokens(ContextFragment fragment) {
        return tokensByFragment.computeIfAbsent(fragment, ContextTokenCounter::computeTokens);
    }

    /**
     * Recomputes the token count for the given fragment and updates the cache.
     */
    public static int recomputeTokens(ContextFragment fragment) {
        int tokens = computeTokens(fragment);
        tokensByFragment.put(fragment, tokens);
        return tokens;
    }

    /**
     * Retains cache entries only for fragments present in the given live context, dropping
     * all others to avoid unbounded growth.
     */
    public static void retainFragments(Context context) {
        Set<ContextFragment> keep = context.allFragments().collect(Collectors.toSet());
        tokensByFragment.keySet().retainAll(keep);
    }

    private static int computeTokens(ContextFragment fragment) {
        assert !SwingUtilities.isEventDispatchThread();

        if (!fragment.isText() && !fragment.getType().isOutput()) {
            return 0;
        }

        String text;
        if (fragment instanceof ContextFragment.ComputedFragment cf) {
            text = cf.computedText().renderNowOr("");
        } else {
            text = fragment.text();
        }
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Messages.getApproximateTokens(text);
    }
}

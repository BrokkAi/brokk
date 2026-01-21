package ai.brokk;

import ai.brokk.util.ReviewParser;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NullMarked;

/**
 * Data structures for representing a guided code review.
 */
@NullMarked
public interface ICodeReview {

    interface ReviewNavigationListener {
        void onNavigate(@Nullable ReviewParser.CodeExcerpt excerpt);
    }
}

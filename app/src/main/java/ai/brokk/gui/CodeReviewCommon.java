package ai.brokk.gui;

import ai.brokk.ICodeReview.CodeExcerpt;
import org.jspecify.annotations.NullMarked;

@NullMarked
public class CodeReviewCommon {
    public record ParsedExcerpt(CodeExcerpt original, int lineNumber) {}

    public interface ReviewNavigationListener {
        void onNavigate(ParsedExcerpt excerpt);
    }
}

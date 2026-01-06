package ai.brokk;

import ai.brokk.util.Json;
import java.util.List;
import org.jspecify.annotations.NullMarked;

/**
 * Data structures for representing a guided code review.
 */
@NullMarked
public interface ICodeReview {

    interface ReviewNavigationListener {
        void onNavigate(ParsedExcerpt excerpt);
    }

    record CodeExcerpt(String file, String excerpt, String commentary) {}

    record DesignFeedback(String title, String description, List<CodeExcerpt> excerpts, String recommendation) {}

    record GuidedReview(
            String overview,
            List<DesignFeedback> designNotes,
            List<CodeExcerpt> tacticalNotes,
            List<String> additionalTests) {

        public String toJson() {
            return Json.toJson(this);
        }

        public static GuidedReview fromJson(String json) {
            return Json.fromJson(json, GuidedReview.class);
        }
    }

    enum DiffSide {
        OLD,
        NEW
    }

    record ParsedExcerpt(CodeExcerpt original, int lineNumber, DiffSide side) {}
}

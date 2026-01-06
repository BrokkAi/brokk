package ai.brokk;

import ai.brokk.util.Json;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.NullMarked;

/**
 * Data structures for representing a guided code review.
 */
@NullMarked
public interface ICodeReview {

    interface ReviewNavigationListener {
        void onNavigate(CodeExcerpt excerpt);
    }

    record CodeExcerpt(String file, int line, DiffSide side, String excerpt) {
        public CodeExcerpt(String file, String excerpt) {
            this(file, 0, DiffSide.NEW, excerpt);
        }
    }

    record RawDesignFeedback(String title, String description, List<Integer> excerptIds, String recommendation) {}

    record RawTacticalFeedback(String title, int excerptId, String recommendation) {}

    record RawReview(
            String overview,
            List<RawDesignFeedback> designNotes,
            List<RawTacticalFeedback> tacticalNotes,
            List<String> additionalTests) {
        public String toJson() {
            return Json.toJson(this);
        }

        public static RawReview fromJson(String json) {
            return Json.fromJson(json, RawReview.class);
        }
    }

    record DesignFeedback(String title, String description, List<CodeExcerpt> excerpts, String recommendation) {}

    record TacticalFeedback(String title, CodeExcerpt excerpt, String recommendation) {}

    record GuidedReview(
            String overview,
            List<DesignFeedback> designNotes,
            List<TacticalFeedback> tacticalNotes,
            List<String> additionalTests) {

        public String toJson() {
            return Json.toJson(this);
        }

        public static GuidedReview fromJson(String json) {
            return Json.fromJson(json, GuidedReview.class);
        }

        public static GuidedReview fromRaw(RawReview rawReview, Map<Integer, CodeExcerpt> excerpts) {
            return fromRaw(
                    rawReview,
                    excerpts.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().excerpt())),
                    excerpts.entrySet().stream()
                            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, e -> e.getValue().file())),
                    (file, excerpt) -> excerpts.values().stream()
                            .filter(ce -> ce.file().equals(file) && ce.excerpt().equals(excerpt))
                            .findFirst()
                            .orElse(new CodeExcerpt(file, excerpt)));
        }

        public static GuidedReview fromRaw(
                RawReview rawReview,
                Map<Integer, String> excerptContents,
                Map<Integer, String> excerptFiles,
                java.util.function.BiFunction<String, String, CodeExcerpt> resolver) {
            List<DesignFeedback> designNotes = rawReview.designNotes().stream()
                    .map(raw -> new DesignFeedback(
                            raw.title(),
                            raw.description(),
                            raw.excerptIds().stream()
                                    .filter(id -> id >= 0 && excerptContents.containsKey(id))
                                    .map(id -> resolver.apply(excerptFiles.get(id), excerptContents.get(id)))
                                    .toList(),
                            raw.recommendation()))
                    .toList();

            List<TacticalFeedback> tacticalNotes = rawReview.tacticalNotes().stream()
                    .map(raw -> {
                        CodeExcerpt excerpt = (raw.excerptId() >= 0 && excerptContents.containsKey(raw.excerptId()))
                                ? resolver.apply(excerptFiles.get(raw.excerptId()), excerptContents.get(raw.excerptId()))
                                : new CodeExcerpt("unknown", 0, DiffSide.NEW, "");
                        return new TacticalFeedback(raw.title(), excerpt, raw.recommendation());
                    })
                    .toList();

            return new GuidedReview(rawReview.overview(), designNotes, tacticalNotes, rawReview.additionalTests());
        }
    }

    enum DiffSide {
        OLD,
        NEW
    }
}

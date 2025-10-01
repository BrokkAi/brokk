package io.github.jbellis.brokk.context;

import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.CodeUnitType;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.util.FragmentUtils;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.imageio.ImageIO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

/**
 * A frozen representation of a dynamic ContextFragment that captures its state at a point in time without depending on
 * the filesystem or analyzer. This allows ContextHistory to accurately represent what the Workspace looked like when
 * the entry was created. The ID of a FrozenFragment is its content hash.
 *
 * <p>FrozenFragments are never created for non-dynamic ContextFragments, which are already content-addressable.
 *
 * <p>This class is retained solely for reading legacy context histories. New context fragments should not be frozen
 * into instances of this class; instead, their dynamic fields are computed and directly serialized to their original
 * DTO types during history persistence.
 */
public final class FrozenFragment extends ContextFragment.VirtualFragment {
    private static final ConcurrentMap<String, FrozenFragment> INTERN_POOL = new ConcurrentHashMap<>();

    // Captured fragment state for behavior and unfreezing
    private final ContextFragment.FragmentType originalType;

    @Nullable
    private final String descriptionContent; // Full description

    @Nullable
    private final String shortDescriptionContent; // Short description

    @Nullable
    private final String textContent; // Null if image fragment

    @Nullable
    private final byte[] imageBytesContent; // Null if text fragment

    private final boolean isTextFragment;

    @Nullable
    private final String syntaxStyle;

    private final Set<ProjectFile> files; // Files associated at time of freezing

    @Nullable
    private final String reprContent; // snapshot of repr()

    // Metadata for unfreezing
    private final String originalClassName;
    private final Map<String, String> meta; // Type-specific metadata for reconstruction

    /**
     * Private constructor for creating FrozenFragment instances. The ID (contentHash) is passed to the super
     * constructor.
     */
    private FrozenFragment(
            String contentHashAsId,
            IContextManager contextManager,
            ContextFragment.FragmentType originalType,
            @Nullable String description,
            @Nullable String shortDescription,
            @Nullable String textContent,
            @Nullable byte[] imageBytesContent,
            boolean isTextFragment,
            @Nullable String syntaxStyle,
            Set<ProjectFile> files,
            String originalClassName,
            Map<String, String> meta,
            @Nullable String reprContent) {
        super(contentHashAsId, contextManager); // ID is the content hash, must not be null
        this.originalType = originalType;
        this.descriptionContent = description;
        this.shortDescriptionContent = shortDescription;
        this.textContent = textContent;
        this.imageBytesContent = imageBytesContent;
        this.isTextFragment = isTextFragment;
        this.syntaxStyle = syntaxStyle;
        this.files = Set.copyOf(files); // Ensure immutability
        this.originalClassName = originalClassName;
        this.meta = Map.copyOf(meta); // Ensure immutability
        this.reprContent = reprContent;
    }

    @Override
    public ContextFragment.FragmentType getType() {
        return originalType;
    }

    @Override
    public String shortDescription() {
        return requireNonNullElse(shortDescriptionContent, "");
    }

    @Override
    public String description() {
        return requireNonNullElse(descriptionContent, "");
    }

    @Override
    public String text() {
        if (isTextFragment) {
            return requireNonNullElse(textContent, "");
        } else {
            return "[Image content provided out of band]";
        }
    }

    @Override
    public Image image() {
        if (isTextFragment) {
            throw new UnsupportedOperationException("This fragment does not contain image content");
        }
        try {
            return bytesToImage(requireNonNull(imageBytesContent));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String format() {
        return """
               <frozen fragmentid="%s" description="%s" originalType="%s">
               %s
               </frozen>
               """
                .stripIndent()
                .formatted(id(), descriptionContent, originalType.name(), text());
    }

    // id() is inherited from VirtualFragment and returns the contentHash.
    // equals() and hashCode() are inherited from VirtualFragment and use id().

    @Override
    public String repr() {
        return requireNonNullElse(reprContent, "");
    }

    @Override
    public boolean isDynamic() {
        return false;
    }

    @Override
    public boolean isText() {
        return isTextFragment;
    }

    @Override
    public String syntaxStyle() {
        return requireNonNullElse(syntaxStyle, SyntaxConstants.SYNTAX_STYLE_NONE);
    }

    /**
     * ideally we would snapshot the sources of the live fragment, but this is impractical since to do so we need an
     * Analyzer, and one is not always available when we are manipulating workspace Fragments. sources() is otherwise
     * only called by user actions that make sense to block for Analyzer on, so as a compromise, we force callers who
     * want sources to go through the live Fragment instead.
     */
    @Override
    public Set<CodeUnit> sources() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<ProjectFile> files() {
        return files;
    }

    /**
     * Gets the original class name of the frozen fragment.
     *
     * @return The original class name
     */
    public String originalClassName() {
        return originalClassName;
    }

    /**
     * Gets the metadata map for unfreezing.
     *
     * @return The metadata map
     */
    public Map<String, String> meta() {
        return meta;
    }

    /**
     * Gets the image bytes content if this is an image fragment.
     *
     * @return The image bytes, or null if this is a text fragment
     */
    @Nullable
    public byte[] imageBytesContent() {
        return imageBytesContent;
    }

    /**
     * Factory method for creating FrozenFragment from DTO data. The provided 'id' from the DTO is expected to be the
     * content hash. Note: This does not participate in interning by default; primarily for deserialization. If
     * interning of deserialized objects is desired, one might call freeze() on the result, or enhance this to use the
     * INTERN_POOL.
     *
     * @param idFromDto The fragment ID from DTO (expected to be the content hash).
     * @param contextManager The context manager.
     * @param originalType The original fragment type.
     * @param description The fragment description.
     * @param shortDescription The short description.
     * @param textContent The text content (null for image fragments).
     * @param imageBytesContent The image bytes (null for text fragments).
     * @param isTextFragment Whether this is a text fragment.
     * @param syntaxStyle The syntax style.
     * @param files The project files.
     * @param originalClassName The original class name.
     * @param meta The metadata map.
     * @return A new FrozenFragment instance.
     */
    public static FrozenFragment fromDto(
            String idFromDto,
            IContextManager contextManager, // id is String
            ContextFragment.FragmentType originalType,
            @Nullable String description,
            @Nullable String shortDescription,
            @Nullable String textContent,
            @Nullable byte[] imageBytesContent,
            boolean isTextFragment,
            @Nullable String syntaxStyle,
            Set<ProjectFile> files,
            String originalClassName,
            Map<String, String> meta,
            @Nullable String repr) {
        // idFromDto is the contentHash. Use INTERN_POOL to ensure global uniqueness.
        return INTERN_POOL.computeIfAbsent(
                idFromDto,
                hashAsKey -> new FrozenFragment(
                        hashAsKey,
                        contextManager,
                        originalType,
                        description,
                        shortDescription,
                        textContent,
                        imageBytesContent,
                        isTextFragment,
                        syntaxStyle,
                        files,
                        originalClassName,
                        meta,
                        repr));
    }

    /**
     * Recreates a live fragment from this frozen representation.
     *
     * @param cm The context manager for the new live fragment
     * @return A live fragment equivalent to the original
     * @throws IOException If reconstruction fails
     */
    @Override
    public ContextFragment unfreeze(IContextManager cm) throws IOException {
        // Accept both legacy ContextFragment$Foo and current Fragments$Foo class names
        final var classKey = originalClassName.replace(
                "io.github.jbellis.brokk.context.ContextFragment$",
                "io.github.jbellis.brokk.context.Fragments$"
        );

        return switch (classKey) {
            case "io.github.jbellis.brokk.context.Fragments$ProjectPathFragment" -> {
                var repoRoot = meta.get("repoRoot");
                var relPath = meta.get("relPath");
                if (repoRoot == null || relPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ProjectPathFragment");
                }
                var file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                yield new Fragments.ProjectPathFragment(file, cm);
            }
            case "io.github.jbellis.brokk.context.Fragments$ExternalPathFragment" -> {
                var absPath = meta.get("absPath");
                if (absPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ExternalPathFragment");
                }
                var file = new ExternalFile(Path.of(absPath));
                yield new Fragments.ExternalPathFragment(file, cm);
            }
            case "io.github.jbellis.brokk.context.Fragments$ImageFileFragment" -> {
                var absPath = meta.get("absPath");
                if (absPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ImageFileFragment");
                }

                BrokkFile file;
                if ("true".equals(meta.get("isProjectFile"))) {
                    var repoRoot = meta.get("repoRoot");
                    var relPath = meta.get("relPath");
                    if (repoRoot == null || relPath == null) {
                        throw new IllegalArgumentException("Missing ProjectFile metadata for ImageFileFragment");
                    }
                    file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                } else {
                    file = new ExternalFile(Path.of(absPath));
                }
                yield new Fragments.ImageFileFragment(file, cm);
            }
            case "io.github.jbellis.brokk.context.Fragments$SkeletonFragment" -> {
                var targetIdentifiersStr = meta.get("targetIdentifiers");
                var summaryTypeStr = meta.get("summaryType");
                if (targetIdentifiersStr == null || summaryTypeStr == null) {
                    throw new IllegalArgumentException("Missing metadata for SkeletonFragment");
                }
                var targetIdentifiers = Arrays.asList(targetIdentifiersStr.split(";"));
                var summaryType = ContextFragment.SummaryType.valueOf(summaryTypeStr);
                yield new Fragments.SkeletonFragment(cm, targetIdentifiers, summaryType);
            }
            case "io.github.jbellis.brokk.context.Fragments$UsageFragment" -> {
                var targetIdentifier = meta.get("targetIdentifier");
                if (targetIdentifier == null) {
                    throw new IllegalArgumentException("Missing metadata for UsageFragment");
                }
                yield new Fragments.UsageFragment(cm, targetIdentifier);
            }
            case "io.github.jbellis.brokk.context.Fragments$CallGraphFragment" -> {
                var methodName = meta.get("methodName");
                var depthStr = meta.get("depth");
                var isCalleeGraphStr = meta.get("isCalleeGraph");
                if (methodName == null || depthStr == null || isCalleeGraphStr == null) {
                    throw new IllegalArgumentException("Missing metadata for CallGraphFragment");
                }
                var depth = Integer.parseInt(depthStr);
                var isCalleeGraph = Boolean.parseBoolean(isCalleeGraphStr);
                yield new Fragments.CallGraphFragment(cm, methodName, depth, isCalleeGraph);
            }
            case "io.github.jbellis.brokk.context.Fragments$CodeFragment" -> {
                var repoRoot = meta.get("repoRoot");
                var relPath = meta.get("relPath");
                var kindStr = meta.get("kind");
                var packageName = meta.get("packageName");
                var shortName = meta.get("shortName");
                CodeUnit unit;
                if (repoRoot != null
                    && relPath != null
                    && kindStr != null
                    && packageName != null
                    && shortName != null) {
                    var pf = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                    var kind = CodeUnitType.valueOf(kindStr);
                    unit = new CodeUnit(pf, kind, packageName, shortName);
                } else {
                    var fqName = meta.get("fqName");
                    if (fqName == null) {
                        throw new IllegalArgumentException("Missing metadata for CodeFragment");
                    }
                    var analyzer = cm.getAnalyzerUninterrupted();
                    unit = analyzer.getDefinition(fqName)
                            .orElseThrow(() ->
                                                 new IllegalArgumentException("Unable to resolve CodeUnit for fqName: " + fqName));
                }
                yield new Fragments.CodeFragment(cm, unit);
            }
            case "io.github.jbellis.brokk.context.Fragments$BuildFragment" -> {
                var fullText = requireNonNullElse(textContent, "");
                var header = "# CURRENT BUILD STATUS\n\n";
                var content = fullText.startsWith(header) ? fullText.substring(header.length()) : fullText;
                yield new Fragments.BuildFragment(cm, content);
            }
            default -> {
                throw new IllegalArgumentException("Unhandled original class for unfreezing: " + originalClassName
                                                   + ". Implement unfreezing logic if this type needs to become live.");
            }
        };
    }

    /** Clears the internal intern pool. For testing purposes only. */
    public static void clearInternPoolForTesting() {
        INTERN_POOL.clear();
    }

    /**
     * Converts a byte array to an Image.
     *
     * @param bytes The byte array to convert
     * @return The converted image, or null if bytes is null
     * @throws IOException If conversion fails
     */
    public static Image bytesToImage(byte[] bytes) throws IOException {
        try (var bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        }
    }


    @Override
    public String toString() {
        return "FrozenFragment(%s@%s, '%s')".formatted(originalType, id, description());
    }
}

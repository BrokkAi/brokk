package io.github.jbellis.brokk.context;

import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

/**
 * A frozen representation of a ContextFragment that captures its state at a point in time
 * without depending on the filesystem or analyzer. This allows ContextHistory to accurately
 * represent what the Workspace looked like when the entry was created.
 */
public final class FrozenFragment extends ContextFragment.VirtualFragment {

    private static final ConcurrentMap<String, FrozenFragment> INTERN_POOL = new ConcurrentHashMap<>();
    
    private static final ThreadLocal<MessageDigest> SHA256_DIGEST_THREAD_LOCAL = ThreadLocal.withInitial(() -> {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not found", e);
        }
    });

    // Captured fragment state
    private final String contentHash; // SHA-256 hash of the content-defining fields
    private final ContextFragment.FragmentType originalType;
    private final String description;
    private final String textContent;
    private final byte[] imageBytesContent;
    private final boolean isTextFragment;
    private final String syntaxStyle;
    private final Set<ProjectFile> files;
    
    // Metadata for unfreezing
    private final String originalClassName;
    private final Map<String, String> meta;
    
    /**
     * Private constructor for creating FrozenFragment instances.
     *
     * @param contentHash The pre-computed SHA-256 hash of content-defining fields.
     */
    private FrozenFragment(String contentHash,
                           int existingId, IContextManager contextManager,
                           ContextFragment.FragmentType originalType,
                           String description,
                           String textContent,
                           byte[] imageBytesContent,
                           boolean isTextFragment,
                           String syntaxStyle,
                           Set<ProjectFile> files,
                           String originalClassName,
                           Map<String, String> meta)
    {
        super(existingId, contextManager);
        this.contentHash = contentHash;
        this.originalType = originalType;
        this.description = description;
        this.textContent = textContent;
        this.imageBytesContent = imageBytesContent;
        this.isTextFragment = isTextFragment;
        this.syntaxStyle = syntaxStyle;
        this.files = Set.copyOf(files);
        this.originalClassName = originalClassName;
        this.meta = Map.copyOf(meta);
    }
    
    @Override
    public ContextFragment.FragmentType getType() {
        return originalType;
    }
    
    @Override
    public String shortDescription() {
        return description;
    }
    
    @Override
    public String description() {
        return description;
    }
    
    @Override
    public String text() {
        if (isTextFragment) {
            return textContent;
        } else {
            return "[Image content]";
        }
    }
    
    @Override
    public Image image() {
        if (isTextFragment) {
            throw new UnsupportedOperationException("This fragment does not contain image content");
        }
        try {
            return bytesToImage(imageBytesContent);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
    
    @Override
    public String format() {
        return """
               <frozen fragmentid="%d" description="%s" originalType="%s">
               %s
               </frozen>
               """.stripIndent().formatted(id(), description, originalType.name(), text());
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
        return syntaxStyle;
    }

    /**
     * ideally we would snapshot the sources of the live fragment, but this is impractical since to do so we
     * need an Analyzer, and one is not always available when we are manipulating workspace Fragments.
     * sources() is otherwise only called by user actions that make sense to block for Analyzer on, so
     * as a compromise, we force callers who want sources to go through the live Fragment instead.
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
     * Gets the content hash (SHA-256) of this frozen fragment.
     * This hash is based on the content-defining fields and is used for interning.
     *
     * @return The content hash string.
     */
    public String getContentHash() {
        return contentHash;
    }

    /**
     * Gets the image bytes content if this is an image fragment.
     *
     * @return The image bytes, or null if this is a text fragment
     */
    public byte[] imageBytesContent() {
        return imageBytesContent;
    }

    /**
     * Factory method for creating FrozenFragment from DTO data.
     * Note: This does not participate in interning; primarily for deserialization.
     * If interning of deserialized objects is desired, this would need enhancement.
     *
     * @param id The fragment ID
     * @param contextManager The context manager
     * @param originalType The original fragment type
     * @param description The fragment description
     * @param textContent The text content (null for image fragments)
     * @param imageBytesContent The image bytes (null for text fragments)
     * @param isTextFragment Whether this is a text fragment
     * @param syntaxStyle The syntax style
     * @param files The project files
     * @param originalClassName The original class name
     * @param meta The metadata map
     * @return A new FrozenFragment instance
     */
    public static FrozenFragment fromDto(int id, IContextManager contextManager,
                                         ContextFragment.FragmentType originalType,
                                         String description, String textContent, byte[] imageBytesContent,
                                         boolean isTextFragment, String syntaxStyle,
                                         Set<ProjectFile> files,
                                         String originalClassName, Map<String, String> meta)
    {
        String calculatedHash = calculateContentHash(originalType, description, textContent, imageBytesContent,
                                                     isTextFragment, syntaxStyle, files,
                                                     originalClassName, meta);
        return new FrozenFragment(calculatedHash, id, contextManager, originalType, description, textContent,
                                  imageBytesContent, isTextFragment, syntaxStyle, files,
                                  originalClassName, meta);
    }

    /**
     * Creates a frozen, potentially interned, representation of the given live fragment.
     * 
     * @param liveFragment The live fragment to freeze
     * @param contextManagerForFrozenFragment The context manager for the frozen fragment
     * @return A frozen representation of the fragment
     * @throws IOException If reading fragment content fails
     * @throws InterruptedException If interrupted while reading fragment content
     */
    public static FrozenFragment freeze(ContextFragment liveFragment, IContextManager contextManagerForFrozenFragment)
    throws IOException, InterruptedException
    {
        try {
            // Capture basic fragment data
            var type = liveFragment.getType();
            String description;
            if (liveFragment instanceof PasteFragment pf) {
                // For PasteFragments, get the underlying description without "Paste of " prefix,
                // mimicking DtoMapper's logic for PasteImageFragmentDto/PasteTextFragmentDto.
                // This ensures consistency if a PasteFragment is frozen.
                try {
                    description = pf.descriptionFuture.get(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Fallback to the full description which might include "(Summarizing...)" or error messages
                    description = pf.description();
                }
            } else {
                description = liveFragment.description(); // Use live fragment's description directly
            }
            var isText = liveFragment.isText();
            var syntaxStyle = liveFragment.syntaxStyle();
            var files = liveFragment.files();     // These are live files
            var originalClassName = liveFragment.getClass().getName();

            // Capture content
            String textContent = null;
            byte[] imageBytesContent = null;

            if (isText) {
                textContent = liveFragment.text();
            } else {
                var image = liveFragment.image();
                imageBytesContent = imageToBytes(image);
            }

            // Build metadata for unfreezing (specific to the original live fragment type)
            var meta = new HashMap<String, String>();
            switch (liveFragment) {
                case ProjectPathFragment pf -> {
                    meta.put("repoRoot", pf.file().getRoot().toString());
                    meta.put("relPath", pf.file().getRelPath().toString());
                }
                case ExternalPathFragment ef -> {
                    meta.put("absPath", ef.file().absPath().toString());
                }
                case ImageFileFragment iff -> {
                    meta.put("absPath", iff.file().absPath().toString());
                    if (iff.file() instanceof ProjectFile pf) {
                        meta.put("isProjectFile", "true");
                        meta.put("repoRoot", pf.getRoot().toString());
                        meta.put("relPath", pf.getRelPath().toString());
                    }
                }
                case SkeletonFragment sf -> {
                    meta.put("targetIdentifiers", String.join(";", sf.getTargetIdentifiers()));
                    meta.put("summaryType", sf.getSummaryType().name());
                }
                case UsageFragment uf -> {
                    meta.put("targetIdentifier", uf.targetIdentifier());
                }
                case CallGraphFragment cgf -> {
                    meta.put("methodName", cgf.getMethodName());
                    meta.put("depth", String.valueOf(cgf.getDepth()));
                    meta.put("isCalleeGraph", String.valueOf(cgf.isCalleeGraph()));
                }
                case GitFileFragment gff -> {
                    meta.put("repoRoot", gff.file().getRoot().toString());
                    meta.put("relPath", gff.file().getRelPath().toString());
                    meta.put("revision", gff.revision());
                }
                default -> {
                    // For fragment types that don't require specific metadata for unfreezing,
                    // the meta map remains empty. The FrozenFragment constructor captures
                    // originalClassName, which unfreeze() uses, and other standard fields.
                }
            }

            // Calculate content hash based on all identifying fields *except* the live fragment's ID.
            String contentHash = calculateContentHash(type, description, textContent, imageBytesContent,
                                                      isText, syntaxStyle, files,
                                                      originalClassName, meta);

            // Use liveFragment.id() for the new FrozenFragment if it's created.
            // The INTERN_POOL ensures that if another live fragment (possibly with a different ID)
            // produces the exact same contentHash, the first FrozenFragment instance (with its ID) is reused.
            final String finalDescription = description; // effectively final for lambda
            final String finalTextContent = textContent;
            final byte[] finalImageBytesContent = imageBytesContent;
            final Set<ProjectFile> finalFiles = files;
            final Map<String,String> finalMeta = meta;

            return INTERN_POOL.computeIfAbsent(contentHash, k -> new FrozenFragment(k,
                                                                                    liveFragment.id(),
                                                                                    contextManagerForFrozenFragment,
                                                                                    type,
                                                                                    finalDescription,
                                                                                    finalTextContent,
                                                                                    finalImageBytesContent,
                                                                                    isText,
                                                                                    syntaxStyle,
                                                                                    finalFiles,
                                                                                    originalClassName,
                                                                                    finalMeta));
        } catch (UncheckedIOException e) {
            throw new IOException(e);
        } catch (CancellationException e) {
            throw new InterruptedException(e.getMessage());
        }
    }

    private static String calculateContentHash(ContextFragment.FragmentType originalType,
                                               String description,
                                               String textContent, byte[] imageBytesContent,
                                               boolean isTextFragment, String syntaxStyle,
                                               Set<ProjectFile> files,
                                               String originalClassName, Map<String, String> meta) {
        MessageDigest md = SHA256_DIGEST_THREAD_LOCAL.get();
        md.reset(); // Reset the digest for reuse

            // Helper to update digest with a string
            BiConsumer<MessageDigest, String> updateWithString = (digest, str) -> {
                if (str != null) {
                    digest.update(str.getBytes(StandardCharsets.UTF_8));
                }
                digest.update((byte) 0); // Delimiter for null vs empty string
            };

            updateWithString.accept(md, originalType.name());
            updateWithString.accept(md, description);
            md.update(isTextFragment ? (byte) 1 : (byte) 0);
            updateWithString.accept(md, syntaxStyle);

            if (isTextFragment) {
                updateWithString.accept(md, textContent);
            } else {
                if (imageBytesContent != null) {
                    md.update(imageBytesContent);
                }
                md.update((byte) 0); // Delimiter
            }

            updateWithString.accept(md, originalClassName);

            // For Set<ProjectFile>: Sort by absolute path for stability
            String filesString = files.stream()
                                      .map(pf -> pf.absPath().toString())
                                      .sorted()
                                      .collect(Collectors.joining(","));
            updateWithString.accept(md, filesString);

            // For Map<String, String> meta: Sort by key, then "key=value"
            String metaString = meta.entrySet().stream()
                                    .sorted(Map.Entry.comparingByKey())
                                    .map(e -> e.getKey() + "=" + e.getValue())
                                    .collect(Collectors.joining(","));
            updateWithString.accept(md, metaString);

            byte[] digestBytes = md.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : digestBytes) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) {
                    hexString.append('0');
                }
                hexString.append(hex);
            }
        return hexString.toString();
    }

    /**
     * Recreates a live fragment from this frozen representation.
     * 
     * @param contextManagerForNewFragment The context manager for the new live fragment
     * @return A live fragment equivalent to the original
     * @throws IOException If reconstruction fails
     */
    public ContextFragment unfreeze(IContextManager contextManagerForNewFragment) throws IOException {
        return switch (originalClassName) {
            case "io.github.jbellis.brokk.context.ContextFragment$ProjectPathFragment" -> {
                var repoRoot = meta.get("repoRoot");
                var relPath = meta.get("relPath");
                if (repoRoot == null || relPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ProjectPathFragment");
                }
                var file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                yield ContextFragment.ProjectPathFragment.withId(file, this.id(), contextManagerForNewFragment);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$ExternalPathFragment" -> {
                var absPath = meta.get("absPath");
                if (absPath == null) {
                    throw new IllegalArgumentException("Missing metadata for ExternalPathFragment");
                }
                var file = new ExternalFile(Path.of(absPath));
                yield ContextFragment.ExternalPathFragment.withId(file, this.id(), contextManagerForNewFragment);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$ImageFileFragment" -> {
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
                yield ContextFragment.ImageFileFragment.withId(file, this.id(), contextManagerForNewFragment);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$SkeletonFragment" -> {
                var targetIdentifiersStr = meta.get("targetIdentifiers");
                var summaryTypeStr = meta.get("summaryType");
                if (targetIdentifiersStr == null || summaryTypeStr == null) {
                    throw new IllegalArgumentException("Missing metadata for SkeletonFragment");
                }
                var targetIdentifiers = Arrays.asList(targetIdentifiersStr.split(";"));
                var summaryType = ContextFragment.SummaryType.valueOf(summaryTypeStr);
                yield new ContextFragment.SkeletonFragment(this.id(), contextManagerForNewFragment, targetIdentifiers, summaryType);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$UsageFragment" -> {
                var targetIdentifier = meta.get("targetIdentifier");
                if (targetIdentifier == null) {
                    throw new IllegalArgumentException("Missing metadata for UsageFragment");
                }
                yield new ContextFragment.UsageFragment(this.id(), contextManagerForNewFragment, targetIdentifier);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$CallGraphFragment" -> {
                var methodName = meta.get("methodName");
                var depthStr = meta.get("depth");
                var isCalleeGraphStr = meta.get("isCalleeGraph");
                if (methodName == null || depthStr == null || isCalleeGraphStr == null) {
                    throw new IllegalArgumentException("Missing metadata for CallGraphFragment");
                }
                var depth = Integer.parseInt(depthStr);
                var isCalleeGraph = Boolean.parseBoolean(isCalleeGraphStr);
                yield new ContextFragment.CallGraphFragment(this.id(), contextManagerForNewFragment, methodName, depth, isCalleeGraph);
            }
            case "io.github.jbellis.brokk.context.ContextFragment$GitFileFragment" -> {
                var repoRoot = meta.get("repoRoot");
                var relPath = meta.get("relPath");
                var revision = meta.get("revision");
                // The 'content' is part of textContent if it was a text fragment,
                // or imageBytesContent if it was an image.
                // For GitFileFragment, it's always text.
                if (repoRoot == null || relPath == null || revision == null) {
                    throw new IllegalArgumentException("Missing metadata for GitFileFragment (repoRoot, relPath, or revision)");
                }
                if (!isTextFragment || textContent == null) {
                     throw new IllegalArgumentException("Missing text content for GitFileFragment in frozen state");
                }
                var file = new ProjectFile(Path.of(repoRoot), Path.of(relPath));
                // Use the stored textContent as the file content for unfreezing.
                yield ContextFragment.GitFileFragment.withId(file, revision, textContent, this.id());
            }
            default -> {
                 throw new IllegalArgumentException("Unhandled original class for unfreezing: " + originalClassName +
                                                   ". Implement unfreezing logic if this type needs to become live.");
            }
        };
    }

    /**
     * Clears the internal intern pool. For testing purposes only.
     */
    static void clearInternPoolForTesting() {
        INTERN_POOL.clear();
    }

    /**
     * Converts an Image to a byte array in PNG format.
     * 
     * @param image The image to convert
     * @return PNG bytes, or null if image is null
     * @throws IOException If conversion fails
     */
    private static byte[] imageToBytes(Image image) throws IOException {
        if (image == null) {
            return null;
        }
        
        BufferedImage bufferedImage;
        if (image instanceof BufferedImage bi) {
            bufferedImage = bi;
        } else {
            bufferedImage = new BufferedImage(
                image.getWidth(null),
                image.getHeight(null),
                BufferedImage.TYPE_INT_ARGB
            );
            var g = bufferedImage.createGraphics();
            g.drawImage(image, 0, 0, null);
            g.dispose();
        }
        
        try (var baos = new ByteArrayOutputStream()) {
            ImageIO.write(bufferedImage, "PNG", baos);
            return baos.toByteArray();
        }
    }
    
    /**
     * Converts a byte array to an Image.
     * 
     * @param bytes The byte array to convert
     * @return The converted image, or null if bytes is null
     * @throws IOException If conversion fails
     */
    private static Image bytesToImage(byte[] bytes) throws IOException {
        if (bytes == null) {
            return null;
        }
        
        try (var bais = new ByteArrayInputStream(bytes)) {
            return ImageIO.read(bais);
        }
    }
}

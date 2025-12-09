package ai.brokk.context;

import static java.util.Objects.requireNonNullElse;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.IContextManager;
import ai.brokk.Service;
import ai.brokk.TaskEntry;
import ai.brokk.TaskResult;
import ai.brokk.analyzer.BrokkFile;
import ai.brokk.analyzer.CodeUnit;
import ai.brokk.analyzer.CodeUnitType;
import ai.brokk.analyzer.ExternalFile;
import ai.brokk.analyzer.ProjectFile;
import ai.brokk.context.FragmentDtos.*;
import ai.brokk.util.HistoryIo.ContentReader;
import ai.brokk.util.HistoryIo.ContentWriter;
import ai.brokk.util.ImageUtil;
import ai.brokk.util.Messages;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Blocking;
import org.jetbrains.annotations.Nullable;

/**
 * Mapper to convert between Context domain objects and DTO representations.
 */
public class DtoMapper {
    private static final Logger logger = LogManager.getLogger(DtoMapper.class);

    private DtoMapper() {
        // Utility class - no instantiation
    }

    public static Context fromCompactDto(
            CompactContextDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCache,
            ContentReader contentReader) {
        var editableFragments = dto.editable().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .toList();

        var readonlyFragments = dto.readonly().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        var virtualFragments = dto.virtuals().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .toList();

        var taskHistory = dto.tasks().stream()
                .map(taskRefDto -> {
                    // Build TaskMeta if present and valid (ModelConfig requires non-null name)
                    TaskResult.TaskMeta meta = null;
                    boolean anyMetaPresent = taskRefDto.taskType() != null
                            || taskRefDto.primaryModelName() != null
                            || taskRefDto.primaryModelReasoning() != null;
                    if (anyMetaPresent && taskRefDto.primaryModelName() != null) {
                        var type =
                                TaskResult.Type.safeParse(taskRefDto.taskType()).orElse(TaskResult.Type.NONE);
                        var reasoning = Service.ReasoningLevel.fromString(
                                taskRefDto.primaryModelReasoning(), Service.ReasoningLevel.DEFAULT);
                        var pm = new Service.ModelConfig(
                                taskRefDto.primaryModelName(), reasoning, Service.ProcessingTier.DEFAULT);
                        meta = new TaskResult.TaskMeta(type, pm);
                        logger.debug(
                                "Reconstructed TaskMeta for sequence {}: type={}, model={}",
                                taskRefDto.sequence(),
                                meta.type(),
                                meta.primaryModel().name());
                    } else if (anyMetaPresent) {
                        // Incomplete meta present (e.g., older sessions missing model name) - ignore gracefully
                        logger.debug(
                                "Ignoring incomplete TaskMeta fields for sequence {} (taskType={}, modelName={}, reasoning={})",
                                taskRefDto.sequence(),
                                taskRefDto.taskType(),
                                taskRefDto.primaryModelName(),
                                taskRefDto.primaryModelReasoning());
                    }

                    // Load log and summary independently (both can coexist)
                    ContextFragment.TaskFragment logFragment = null;
                    if (taskRefDto.logId() != null) {
                        logFragment = (ContextFragment.TaskFragment) fragmentCache.get(taskRefDto.logId());
                    }

                    String summary = null;
                    if (taskRefDto.summaryContentId() != null) {
                        summary = contentReader.readContent(taskRefDto.summaryContentId());
                    }

                    // At least one must be present
                    if (logFragment != null || summary != null) {
                        return new TaskEntry(taskRefDto.sequence(), logFragment, summary, meta);
                    }
                    return null;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        var parsedOutputFragment = dto.parsedOutputId() != null
                ? (ContextFragment.TaskFragment) fragmentCache.get(dto.parsedOutputId())
                : null;

        var actionFuture = CompletableFuture.completedFuture(dto.action());
        var ctxId = dto.id() != null ? UUID.fromString(dto.id()) : Context.newContextId();

        var combined = Streams.concat(editableFragments.stream(), virtualFragments.stream())
                .toList();

        UUID groupUuid = null;
        if (dto.groupId() != null && !dto.groupId().isEmpty()) {
            groupUuid = UUID.fromString(dto.groupId());
        }

        return Context.createWithId(
                ctxId,
                mgr,
                combined,
                taskHistory,
                parsedOutputFragment,
                actionFuture,
                groupUuid,
                dto.groupLabel(),
                readonlyFragments);
    }

    public record GitStateDto(String commitHash, @Nullable String diffContentId) {}

    /**
     * Build a CompactContextDto for serialization, including marked read-only fragment IDs.
     */
    public static CompactContextDto toCompactDto(Context ctx, ContentWriter writer, String action) {
        var taskEntryRefs = ctx.getTaskHistory().stream()
                .map(te -> {
                    String type = te.meta() != null ? te.meta().type().name() : null;
                    String pmName = te.meta() != null ? te.meta().primaryModel().name() : null;
                    String pmReason = te.meta() != null
                            ? te.meta().primaryModel().reasoning().name()
                            : null;
                    return new TaskEntryRefDto(
                            te.sequence(),
                            te.hasLog() ? castNonNull(te.log()).id() : null,
                            te.isCompressed() ? writer.writeContent(castNonNull(te.summary()), null) : null,
                            type,
                            pmName,
                            pmReason);
                })
                .toList();

        var editableIds = ctx.fileFragments().map(ContextFragment::id).toList();
        var virtualIds = ctx.virtualFragments().map(ContextFragment::id).toList();
        var readonlyIds =
                ctx.getMarkedReadonlyFragments().map(ContextFragment::id).toList();

        return new CompactContextDto(
                ctx.id().toString(),
                editableIds,
                readonlyIds,
                virtualIds,
                taskEntryRefs,
                ctx.getParsedOutput() != null ? ctx.getParsedOutput().id() : null,
                action,
                ctx.getGroupId() != null ? ctx.getGroupId().toString() : null,
                ctx.getGroupLabel());
    }

    // Central method for resolving and building fragments, called by HistoryIo within computeIfAbsent
    public static @Nullable ContextFragment resolveAndBuildFragment(
            String idToResolve,
            Map<String, ReferencedFragmentDto> referencedDtos,
            Map<String, VirtualFragmentDto> virtualDtos,
            Map<String, TaskFragmentDto> taskDtos,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            ContentReader contentReader) {
        // Ensure ID continuity for numeric IDs
        ContextFragment.setMinimumId(parseNumericId(idToResolve));

        if (referencedDtos.containsKey(idToResolve)) {
            var dto = referencedDtos.get(idToResolve);
            if (dto instanceof FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildReferencedFragment(castNonNull(dto), mgr, contentReader);
        }
        if (virtualDtos.containsKey(idToResolve)) {
            var dto = virtualDtos.get(idToResolve);
            if (dto instanceof FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildVirtualFragment(
                    castNonNull(dto),
                    mgr,
                    imageBytesMap,
                    fragmentCacheForRecursion,
                    referencedDtos,
                    virtualDtos,
                    taskDtos,
                    contentReader);
        }
        if (taskDtos.containsKey(idToResolve)) {
            return _buildTaskFragment(castNonNull(taskDtos.get(idToResolve)), mgr, contentReader);
        }
        logger.error("Fragment DTO not found for ID: {} during resolveAndBuildFragment", idToResolve);
        throw new IllegalStateException("Fragment DTO not found for ID: " + idToResolve);
    }

    private static int parseNumericId(String id) {
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return 0; // Non-numeric IDs (hash-based) don't affect nextId
        }
    }

    private static @Nullable ContextFragment _buildReferencedFragment(
            ReferencedFragmentDto dto, IContextManager mgr, ContentReader reader) {
        return switch (dto) {
            case ProjectFileDto pfd -> {
                ContextFragment.setMinimumId(parseNumericId(pfd.id()));
                // Use current project root for cross-platform compatibility
                String snapshot = pfd.snapshotText() != null ? reader.readContent(pfd.snapshotText()) : null;
                yield ContextFragment.ProjectPathFragment.withId(mgr.toFile(pfd.relPath()), pfd.id(), mgr, snapshot);
            }
            case ExternalFileDto efd -> {
                ContextFragment.setMinimumId(parseNumericId(efd.id()));
                String snapshot = efd.snapshotText() != null ? reader.readContent(efd.snapshotText()) : null;
                yield ContextFragment.ExternalPathFragment.withId(
                        new ExternalFile(Path.of(efd.absPath())), efd.id(), mgr, snapshot);
            }
            case ImageFileDto ifd -> {
                ContextFragment.setMinimumId(parseNumericId(ifd.id()));
                BrokkFile file = fromImageFileDtoToBrokkFile(ifd, mgr);
                yield ContextFragment.ImageFileFragment.withId(file, ifd.id(), mgr);
            }
            case GitFileFragmentDto gfd ->
                // Use current project root for cross-platform compatibility
                ContextFragment.GitFileFragment.withId(
                        mgr.toFile(gfd.relPath()), gfd.revision(), reader.readContent(gfd.contentId()), gfd.id());
            case FrozenFragmentDto ffd ->
                // Reconstruct the original live fragment instead of returning a FrozenFragment
                fromFrozenDtoToLiveFragment(ffd, mgr, reader);
        };
    }

    private static @Nullable ContextFragment.TaskFragment _buildTaskFragment(
            @Nullable TaskFragmentDto dto, IContextManager mgr, ContentReader reader) {
        if (dto == null) return null;
        var messages = dto.messages().stream()
                .map(msgDto -> fromChatMessageDto(msgDto, reader))
                .toList();
        return new ContextFragment.TaskFragment(dto.id(), mgr, messages, dto.sessionName());
    }

    private static @Nullable ContextFragment _buildVirtualFragment(
            @Nullable VirtualFragmentDto dto,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            Map<String, ReferencedFragmentDto> allReferencedDtos,
            Map<String, VirtualFragmentDto> allVirtualDtos,
            Map<String, TaskFragmentDto> allTaskDtos,
            ContentReader reader) {
        if (dto == null) return null;
        // Ensure ID continuity for numeric IDs
        ContextFragment.setMinimumId(parseNumericId(dto.id()));
        return switch (dto) {
            case FrozenFragmentDto ffd -> {
                if (isDeprecatedBuildFragment(ffd)) {
                    logger.info("Skipping deprecated BuildFragment during deserialization: {}", ffd.id());
                    yield null;
                }
                yield fromFrozenDtoToLiveFragment(ffd, mgr, reader);
            }
            case SearchFragmentDto searchDto -> {
                logger.warn("Search fragments are no longer supported, dropping fragment");
                yield null;
            }
            case TaskFragmentDto taskDto -> _buildTaskFragment(taskDto, mgr, reader);
            case StringFragmentDto stringDto ->
                new ContextFragment.StringFragment(
                        stringDto.id(),
                        mgr,
                        reader.readContent(stringDto.contentId()),
                        stringDto.description(),
                        stringDto.syntaxStyle());
            case SkeletonFragmentDto skeletonDto -> {
                logger.warn("Skeleton fragments are no longer supported, dropping fragment");
                yield null;
            }
            case SummaryFragmentDto summaryDto ->
                new ContextFragment.SummaryFragment(
                        summaryDto.id(),
                        mgr,
                        summaryDto.targetIdentifier(),
                        ContextFragment.SummaryType.valueOf(summaryDto.summaryType()));
            case UsageFragmentDto usageDto -> {
                String snapshot = usageDto.snapshotText() != null ? reader.readContent(usageDto.snapshotText()) : null;
                yield new ContextFragment.UsageFragment(
                        usageDto.id(), mgr, usageDto.targetIdentifier(), usageDto.includeTestFiles(), snapshot);
            }
            case PasteTextFragmentDto pasteTextDto ->
                new ContextFragment.PasteTextFragment(
                        pasteTextDto.id(),
                        mgr,
                        reader.readContent(pasteTextDto.contentId()),
                        CompletableFuture.completedFuture(pasteTextDto.description()),
                        CompletableFuture.completedFuture(
                                requireNonNullElse(pasteTextDto.syntaxStyle(), SyntaxConstants.SYNTAX_STYLE_MARKDOWN)));
            case PasteImageFragmentDto pasteImageDto -> {
                try {
                    byte[] imageBytes = imageBytesMap != null ? imageBytesMap.get(pasteImageDto.id()) : null;
                    if (imageBytes == null) {
                        logger.error("Image bytes not found for fragment: {}", pasteImageDto.id());
                        yield null;
                    }
                    var image = ImageUtil.bytesToImage(imageBytes);
                    yield new ContextFragment.AnonymousImageFragment(
                            pasteImageDto.id(),
                            mgr,
                            image,
                            CompletableFuture.completedFuture(pasteImageDto.description()));
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            case StacktraceFragmentDto stDto -> {
                var sources = stDto.sources().stream()
                        .map(cuDto -> fromCodeUnitDto(cuDto, mgr))
                        .collect(Collectors.toSet());
                yield new ContextFragment.StacktraceFragment(
                        stDto.id(),
                        mgr,
                        sources,
                        reader.readContent(stDto.originalContentId()),
                        stDto.exception(),
                        reader.readContent(stDto.codeContentId()));
            }
            case CallGraphFragmentDto callGraphDto ->
                new ContextFragment.CallGraphFragment(
                        callGraphDto.id(),
                        mgr,
                        callGraphDto.methodName(),
                        callGraphDto.depth(),
                        callGraphDto.isCalleeGraph());
            case CodeFragmentDto codeDto -> {
                String snapshot = codeDto.snapshotText() != null ? reader.readContent(codeDto.snapshotText()) : null;
                yield new ContextFragment.CodeFragment(codeDto.id(), mgr, codeDto.fullyQualifiedName(), snapshot);
            }
            case BuildFragmentDto bfDto -> {
                // Backward compatibility: convert legacy BuildFragment to StringFragment with BUILD_RESULTS
                var text = reader.readContent(bfDto.contentId());
                yield new ContextFragment.StringFragment(
                        bfDto.id(),
                        mgr,
                        text,
                        SpecialTextType.BUILD_RESULTS.description(),
                        SpecialTextType.BUILD_RESULTS.syntaxStyle());
            }
            case HistoryFragmentDto historyDto -> {
                var historyEntries = historyDto.history().stream()
                        .map(te -> _fromTaskEntryDto(
                                te,
                                mgr,
                                fragmentCacheForRecursion,
                                allReferencedDtos,
                                allVirtualDtos,
                                allTaskDtos,
                                imageBytesMap,
                                reader))
                        .toList();
                yield new ContextFragment.HistoryFragment(historyDto.id(), mgr, historyEntries);
            }
        };
    }

    public static ReferencedFragmentDto toReferencedFragmentDto(ContextFragment fragment, ContentWriter writer) {
        return switch (fragment) {
            case ContextFragment.ProjectPathFragment pf -> {
                ProjectFile file = pf.file();
                String snapshotId = null;
                String snapshot = pf.text().tryGet().orElse(null);
                if (snapshot != null && !snapshot.isBlank()) {
                    String fileKey = file.getRoot() + ":" + file.getRelPath();
                    snapshotId = writer.writeContent(snapshot, fileKey);
                }
                yield new ProjectFileDto(
                        pf.id(), file.getRoot().toString(), file.getRelPath().toString(), snapshotId);
            }
            case ContextFragment.GitFileFragment gf -> {
                var file = gf.file();
                var fileKey = file.getRoot() + ":" + file.getRelPath();
                String contentId = writer.writeContent(gf.content(), fileKey);
                ProjectFile pf = gf.file();
                yield new GitFileFragmentDto(
                        gf.id(), pf.getRoot().toString(), pf.getRelPath().toString(), gf.revision(), contentId);
            }
            case ContextFragment.ExternalPathFragment ef -> {
                ExternalFile extFile = ef.file();
                String snapshotId = null;
                String snapshot = ef.text().tryGet().orElse(null);
                if (snapshot != null && !snapshot.isBlank()) {
                    String fileKey = extFile.absPath().toString();
                    snapshotId = writer.writeContent(snapshot, fileKey);
                }
                yield new ExternalFileDto(ef.id(), extFile.absPath().toString(), snapshotId);
            }
            case ContextFragment.ImageFileFragment imf -> {
                BrokkFile file = imf.file();
                String fileName = file.getFileName().toLowerCase(Locale.ROOT);
                String mediaType = null;
                if (fileName.endsWith(".png")) mediaType = "image/png";
                else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) mediaType = "image/jpeg";
                else if (fileName.endsWith(".gif")) mediaType = "image/gif";

                String fileId = file.absPath().toString();
                yield new ImageFileDto(imf.id(), fileId, mediaType);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported fragment type for referenced DTO conversion: " + fragment.getClass());
        };
    }

    private static BrokkFile fromImageFileDtoToBrokkFile(ImageFileDto ifd, IContextManager mgr) {
        Path path = Path.of(ifd.absPath());
        Path projectRoot = mgr.getProject().getRoot();
        if (path.startsWith(projectRoot)) {
            try {
                Path relPath = projectRoot.relativize(path);
                return mgr.toFile(relPath.toString());
            } catch (IllegalArgumentException e) {
                return new ExternalFile(path);
            }
        }
        return new ExternalFile(path);
    }

    @Blocking
    public static @Nullable VirtualFragmentDto toVirtualFragmentDto(ContextFragment fragment, ContentWriter writer) {
        return switch (fragment) {
            case ContextFragment.TaskFragment tf -> toTaskFragmentDto(tf, writer);
            case ContextFragment.StringFragment sf ->
                // String fragment is fine to block on
                new StringFragmentDto(
                        sf.id(),
                        writer.writeContent(sf.text().join(), null),
                        sf.description().join(),
                        sf.syntaxStyle().join());
            case ContextFragment.SummaryFragment sumf ->
                new SummaryFragmentDto(
                        sumf.id(),
                        sumf.getTargetIdentifier(),
                        sumf.getSummaryType().name());
            case ContextFragment.UsageFragment uf -> {
                String snapshotId = null;
                String snapshot = uf.text().tryGet().orElse(null);
                if (snapshot != null) {
                    snapshotId = writer.writeContent(snapshot, null);
                }
                yield new UsageFragmentDto(uf.id(), uf.targetIdentifier(), uf.includeTestFiles(), snapshotId);
            }
            case ContextFragment.PasteTextFragment ptf -> {
                // Fine to block on
                String description = getFutureDescription(ptf.getDescriptionFuture(), "Paste of ");
                String contentId = writer.writeContent(ptf.text().join(), null);
                String syntaxStyle = getFutureSyntaxStyle(ptf.getSyntaxStyleFuture());
                yield new PasteTextFragmentDto(ptf.id(), contentId, description, syntaxStyle);
            }
            case ContextFragment.AnonymousImageFragment aif -> {
                String description = getFutureDescription(aif.descriptionFuture, "Paste of ");
                yield new PasteImageFragmentDto(aif.id(), description);
            }
            case ContextFragment.StacktraceFragment stf -> {
                // Fine to block on
                var sourcesDto = stf.sources().join().stream()
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                String originalContentId = writer.writeContent(stf.getOriginal(), null);
                String codeContentId = writer.writeContent(stf.getCode(), null);
                yield new StacktraceFragmentDto(
                        stf.id(), sourcesDto, originalContentId, stf.getException(), codeContentId);
            }
            case ContextFragment.CallGraphFragment cgf ->
                new CallGraphFragmentDto(cgf.id(), cgf.getMethodName(), cgf.getDepth(), cgf.isCalleeGraph());
            case ContextFragment.CodeFragment cf -> {
                String snapshotId = null;
                String snapshot = cf.text().tryGet().orElse(null);
                if (snapshot != null) {
                    snapshotId = writer.writeContent(snapshot, null);
                }
                yield new CodeFragmentDto(cf.id(), cf.getFullyQualifiedName(), snapshotId);
            }
            case ContextFragment.HistoryFragment hf -> {
                var historyDto = hf.entries().stream()
                        .map(te -> toTaskEntryDto(te, writer))
                        .toList();
                yield new HistoryFragmentDto(hf.id(), historyDto);
            }
            default -> {
                logger.warn(
                        "Unsupported VirtualFragment type for DTO conversion '{}', dropping",
                        fragment.getClass().getName());
                yield null;
            }
        };
    }

    private static String getFutureDescription(Future<String> future, String prefix) {
        String description;
        try {
            String fullDescription = future.get(10, TimeUnit.SECONDS);
            description =
                    fullDescription.startsWith(prefix) ? fullDescription.substring(prefix.length()) : fullDescription;
        } catch (Exception e) {
            description = "(Error getting paste description: " + e.getMessage() + ")";
        }
        return description;
    }

    private static String getFutureSyntaxStyle(Future<String> future) {
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            return SyntaxConstants.SYNTAX_STYLE_MARKDOWN; // Fallback
        }
    }

    private static TaskEntryDto toTaskEntryDto(TaskEntry entry, ContentWriter writer) {
        TaskFragmentDto logDto = null;
        if (entry.hasLog()) {
            logDto = toTaskFragmentDto(castNonNull(entry.log()), writer);
        }
        String summaryContentId = null;
        if (entry.isCompressed()) {
            summaryContentId = writer.writeContent(castNonNull(entry.summary()), null);
        }
        return new TaskEntryDto(entry.sequence(), logDto, summaryContentId);
    }

    @Blocking
    public static TaskFragmentDto toTaskFragmentDto(ContextFragment.TaskFragment fragment, ContentWriter writer) {
        var messagesDto = fragment.messages().stream()
                .map(m -> toChatMessageDto(m, writer))
                .toList();
        // Cheap to block on
        return new TaskFragmentDto(
                fragment.id(), messagesDto, fragment.description().join());
    }

    static ChatMessageDto toChatMessageDto(ChatMessage message, ContentWriter writer) {
        // Package-private for tests in ai.brokk.context and internal mapping use.
        String reasoningContentId = null;
        String contentId;

        if (message instanceof AiMessage aiMessage) {
            // For AiMessage, store text and reasoning separately
            String text = aiMessage.text();
            String reasoning = aiMessage.reasoningContent();

            contentId = writer.writeContent(text != null ? text : "", null);

            if (reasoning != null && !reasoning.isBlank()) {
                reasoningContentId = writer.writeContent(reasoning, null);
            }
        } else {
            // For other message types, use the display representation
            contentId = writer.writeContent(Messages.getRepr(message), null);
        }

        return new ChatMessageDto(message.type().name().toLowerCase(Locale.ROOT), contentId, reasoningContentId);
    }

    private static ProjectFile fromProjectFileDto(ProjectFileDto dto, IContextManager mgr) {
        // Use the current project root instead of the serialized one to handle cross-platform compatibility
        // (e.g., when a history ZIP was created on Unix but deserialized on Windows)
        return mgr.toFile(dto.relPath());
    }

    static ChatMessage fromChatMessageDto(ChatMessageDto dto, ContentReader reader) {
        // Package-private for tests in ai.brokk.context and internal mapping use.
        String content = reader.readContent(dto.contentId());
        return switch (dto.role().toLowerCase(Locale.ROOT)) {
            case "user" -> UserMessage.from(content);
            case "ai" -> {
                // Prefer structured reasoningContentId if available
                if (dto.reasoningContentId() != null) {
                    String reasoning = reader.readContent(dto.reasoningContentId());
                    yield new AiMessage(content, reasoning);
                }
                // Graceful degrade: treat entire content as text when reasoningContentId is absent
                yield new AiMessage(content);
            }
            case "system", "custom" -> SystemMessage.from(content);
            default -> throw new IllegalArgumentException("Unsupported message role: " + dto.role());
        };
    }

    private static CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) {
        ProjectFile pf = codeUnit.source();
        ProjectFileDto pfd =
                new ProjectFileDto("0", pf.getRoot().toString(), pf.getRelPath().toString(), null);
        return new CodeUnitDto(
                pfd, codeUnit.kind().name(), codeUnit.packageName(), codeUnit.shortName(), codeUnit.signature());
    }

    private static TaskEntry _fromTaskEntryDto(
            TaskEntryDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            Map<String, ReferencedFragmentDto> allReferencedDtos,
            Map<String, VirtualFragmentDto> allVirtualDtos,
            Map<String, TaskFragmentDto> allTaskDtos,
            @Nullable Map<String, byte[]> imageBytesMap,
            ContentReader reader) {
        // Load the log if present
        ContextFragment.TaskFragment taskFragment = null;
        if (dto.log() != null) {
            taskFragment = (ContextFragment.TaskFragment) fragmentCacheForRecursion.computeIfAbsent(
                    dto.log().id(),
                    id -> resolveAndBuildFragment(
                            id,
                            allReferencedDtos,
                            allVirtualDtos,
                            allTaskDtos,
                            mgr,
                            imageBytesMap,
                            fragmentCacheForRecursion,
                            reader));
        }

        // Load the summary if present
        String summary = null;
        if (dto.summaryContentId() != null) {
            summary = reader.readContent(dto.summaryContentId());
        }

        // Both can coexist; at least one must be present
        return new TaskEntry(dto.sequence(), taskFragment, summary);
    }

    private static CodeUnit fromCodeUnitDto(CodeUnitDto dto, IContextManager mgr) {
        ProjectFileDto pfd = dto.sourceFile();
        // Use current project root for cross-platform compatibility
        ProjectFile source = mgr.toFile(pfd.relPath());
        var kind = CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName(), dto.signature());
    }

    private static boolean isDeprecatedBuildFragment(FrozenFragmentDto ffd) {
        return "io.github.jbellis.brokk.context.ContextFragment$BuildFragment".equals(ffd.originalClassName())
                || "BUILD_LOG".equals(ffd.originalType());
    }

    private static @Nullable ContextFragment fromFrozenDtoToLiveFragment(
            FrozenFragmentDto ffd, IContextManager mgr, ContentReader reader) {
        var original = ffd.originalClassName();
        var meta = ffd.meta();
        try {
            switch (original) {
                case "io.github.jbellis.brokk.context.ContextFragment$ProjectPathFragment",
                        "ai.brokk.context.ContextFragment$ProjectPathFragment" -> {
                    var relPath = meta.get("relPath");
                    if (relPath == null)
                        throw new IllegalArgumentException("Missing metadata 'relPath' for ProjectPathFragment");
                    var file = mgr.toFile(relPath);
                    String snapshot = ffd.contentId() != null && ffd.isTextFragment()
                            ? reader.readContent(ffd.contentId())
                            : null;
                    return new ContextFragment.ProjectPathFragment(file, mgr, snapshot);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$ExternalPathFragment",
                        "ai.brokk.context.ContextFragment$ExternalPathFragment" -> {
                    var absPath = meta.get("absPath");
                    if (absPath == null)
                        throw new IllegalArgumentException("Missing metadata 'absPath' for ExternalPathFragment");
                    var file = new ExternalFile(Path.of(absPath).toAbsolutePath());
                    String snapshot = ffd.contentId() != null && ffd.isTextFragment()
                            ? reader.readContent(ffd.contentId())
                            : null;
                    return new ContextFragment.ExternalPathFragment(file, mgr, snapshot);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$ImageFileFragment",
                        "ai.brokk.context.ContextFragment$ImageFileFragment" -> {
                    var absPath = meta.get("absPath");
                    if (absPath == null)
                        throw new IllegalArgumentException("Missing metadata 'absPath' for ImageFileFragment");
                    BrokkFile file;
                    if ("true".equals(meta.get("isProjectFile"))) {
                        var relPath = meta.get("relPath");
                        if (relPath == null) {
                            throw new IllegalArgumentException("Missing 'relPath' for project ImageFileFragment");
                        }
                        file = mgr.toFile(relPath);
                    } else {
                        file = new ExternalFile(Path.of(absPath).toAbsolutePath());
                    }
                    return new ContextFragment.ImageFileFragment(file, mgr);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$GitFileFragment",
                        "ai.brokk.context.ContextFragment$GitFileFragment" -> {
                    var relPath = meta.get("relPath");
                    var revision = meta.get("revision");
                    if (relPath == null || revision == null) {
                        throw new IllegalArgumentException("Missing 'relPath' or 'revision' for GitFileFragment");
                    }
                    var file = mgr.toFile(relPath); // use current project root for portability
                    var contentId = ffd.contentId();
                    if (contentId == null) {
                        throw new IllegalArgumentException("Frozen GitFileFragment missing contentId");
                    }
                    var content = reader.readContent(contentId);
                    return new ContextFragment.GitFileFragment(file, revision, content);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$SkeletonFragment",
                        "ai.brokk.context.ContextFragment$SkeletonFragment" -> {
                    logger.warn("Skeleton fragments are no longer supported, dropping fragment");
                    return null;
                }
                case "io.github.jbellis.brokk.context.ContextFragment$SummaryFragment",
                        "ai.brokk.context.ContextFragment$SummaryFragment" -> {
                    var targetIdentifier = meta.get("targetIdentifier");
                    var summaryTypeStr = meta.get("summaryType");
                    if (targetIdentifier == null || summaryTypeStr == null) {
                        throw new IllegalArgumentException(
                                "Missing 'targetIdentifier' or 'summaryType' for SummaryFragment");
                    }
                    var summaryType = ContextFragment.SummaryType.valueOf(summaryTypeStr);
                    return new ContextFragment.SummaryFragment(mgr, targetIdentifier, summaryType);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$UsageFragment",
                        "ai.brokk.context.ContextFragment$UsageFragment" -> {
                    var targetIdentifier = meta.get("targetIdentifier");
                    if (targetIdentifier == null) {
                        throw new IllegalArgumentException("Missing 'targetIdentifier' for UsageFragment");
                    }
                    String snapshot = ffd.contentId() != null && ffd.isTextFragment()
                            ? reader.readContent(ffd.contentId())
                            : null;
                    return new ContextFragment.UsageFragment(mgr, targetIdentifier, true, snapshot);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$CallGraphFragment",
                        "ai.brokk.context.ContextFragment$CallGraphFragment" -> {
                    var methodName = meta.get("methodName");
                    var depthStr = meta.get("depth");
                    var isCalleeGraphStr = meta.get("isCalleeGraph");
                    if (methodName == null || depthStr == null || isCalleeGraphStr == null) {
                        throw new IllegalArgumentException(
                                "Missing 'methodName', 'depth' or 'isCalleeGraph' for CallGraphFragment");
                    }
                    int depth = Integer.parseInt(depthStr);
                    boolean isCalleeGraph = Boolean.parseBoolean(isCalleeGraphStr);
                    return new ContextFragment.CallGraphFragment(mgr, methodName, depth, isCalleeGraph);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$CodeFragment",
                        "ai.brokk.context.ContextFragment$CodeFragment" -> {
                    var fqName = meta.get("fqName");
                    if (fqName == null) {
                        throw new IllegalArgumentException("Missing 'fqName' for CodeFragment");
                    }
                    String snapshot = ffd.contentId() != null && ffd.isTextFragment()
                            ? reader.readContent(ffd.contentId())
                            : null;
                    return new ContextFragment.CodeFragment(mgr, fqName, snapshot);
                }
                default -> throw new RuntimeException("Unsupported FrozenFragment originalClassName=" + original);
            }
        } catch (RuntimeException ex) {
            logger.error(
                    "Failed to reconstruct live fragment from FrozenFragmentDto id={} originalClassName={}: {}",
                    ffd.id(),
                    original,
                    ex.toString());
            throw ex;
        }
    }

    /* ───────────── entryInfos mapping ───────────── */

    public static Map<String, EntryInfoDto> toEntryInfosDto(
            Map<UUID, ContextHistory.ContextHistoryEntryInfo> entryInfos) {
        return entryInfos.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> new EntryInfoDto(e.getValue().deletedFiles().stream()
                                .map(DtoMapper::toDeletedFileDto)
                                .toList())));
    }

    public static Map<String, ContextHistory.ContextHistoryEntryInfo> fromEntryInfosDto(
            Map<String, EntryInfoDto> dtoMap, IContextManager mgr) {
        return dtoMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ContextHistory.ContextHistoryEntryInfo(e.getValue().deletedFiles().stream()
                                .map(dto -> fromDeletedFileDto(dto, mgr))
                                .toList())));
    }

    private static DeletedFileDto toDeletedFileDto(ContextHistory.DeletedFile df) {
        return new DeletedFileDto(
                new ProjectFileDto(
                        "0",
                        df.file().getRoot().toString(),
                        df.file().getRelPath().toString(),
                        null),
                df.content(),
                df.wasTracked());
    }

    private static ContextHistory.DeletedFile fromDeletedFileDto(DeletedFileDto dto, IContextManager mgr) {
        return new ContextHistory.DeletedFile(fromProjectFileDto(dto.file(), mgr), dto.content(), dto.wasTracked());
    }
}

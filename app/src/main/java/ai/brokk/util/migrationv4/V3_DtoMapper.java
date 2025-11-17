package ai.brokk.util.migrationv4;

import static java.util.Objects.requireNonNullElse;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.*;
import ai.brokk.context.*;
import ai.brokk.util.ImageUtil;
import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.jetbrains.annotations.Nullable;

public class V3_DtoMapper {
    private static final Logger logger = LogManager.getLogger(V3_DtoMapper.class);

    private V3_DtoMapper() {
        // Utility class - no instantiation
    }

    public static Context fromCompactDto(
            V3_FragmentDtos.CompactContextDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCache,
            V3_HistoryIo.ContentReader contentReader) {
        var editableFragments = dto.editable().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .toList();

        var readonlyFragments = dto.readonly().stream()
                .map(fragmentCache::get)
                .filter(Objects::nonNull)
                .toList();

        var virtualFragments = dto.virtuals().stream()
                .map(id -> (ContextFragment.VirtualFragment) fragmentCache.get(id))
                .filter(Objects::nonNull)
                .toList();

        var taskHistory = dto.tasks().stream()
                .map(taskRefDto -> {
                    if (taskRefDto.logId() != null) {
                        var logFragment = (ContextFragment.TaskFragment) fragmentCache.get(taskRefDto.logId());
                        if (logFragment != null) {
                            return new TaskEntry(taskRefDto.sequence(), logFragment, null);
                        }
                    } else if (taskRefDto.summaryContentId() != null) {
                        String summary = contentReader.readContent(taskRefDto.summaryContentId());
                        return TaskEntry.fromCompressed(taskRefDto.sequence(), summary);
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

        var combined = Streams.concat(
                        Streams.concat(editableFragments.stream(), readonlyFragments.stream()),
                        virtualFragments.stream().map(v -> (ContextFragment) v))
                .toList();

        return Context.createWithId(ctxId, mgr, combined, taskHistory, parsedOutputFragment, actionFuture);
    }

    public record GitStateDto(String commitHash, @Nullable String diffContentId) {}

    // Central method for resolving and building fragments, called by HistoryIo within computeIfAbsent
    public static @Nullable ContextFragment resolveAndBuildFragment(
            String idToResolve,
            Map<String, V3_FragmentDtos.ReferencedFragmentDto> referencedDtos,
            Map<String, V3_FragmentDtos.VirtualFragmentDto> virtualDtos,
            Map<String, V3_FragmentDtos.TaskFragmentDto> taskDtos,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            V3_HistoryIo.ContentReader contentReader) {
        if (referencedDtos.containsKey(idToResolve)) {
            var dto = referencedDtos.get(idToResolve);
            if (dto instanceof V3_FragmentDtos.FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildReferencedFragment(castNonNull(dto), mgr, contentReader);
        }
        if (virtualDtos.containsKey(idToResolve)) {
            var dto = virtualDtos.get(idToResolve);
            if (dto instanceof V3_FragmentDtos.FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildVirtualFragment(
                    castNonNull(dto),
                    mgr,
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

    private static ContextFragment _buildReferencedFragment(
            V3_FragmentDtos.ReferencedFragmentDto dto, IContextManager mgr, V3_HistoryIo.ContentReader reader) {
        return switch (dto) {
            case V3_FragmentDtos.ProjectFileDto pfd ->
                // Use current project root for cross-platform compatibility
                ContextFragment.ProjectPathFragment.withId(mgr.toFile(pfd.relPath()), pfd.id(), mgr);
            case V3_FragmentDtos.ExternalFileDto efd ->
                ContextFragment.ExternalPathFragment.withId(
                        new ExternalFile(Path.of(efd.absPath()).toAbsolutePath()), efd.id(), mgr);
            case V3_FragmentDtos.ImageFileDto ifd -> {
                BrokkFile file = fromImageFileDtoToBrokkFile(ifd, mgr);
                yield ContextFragment.ImageFileFragment.withId(file, ifd.id(), mgr);
            }
            case V3_FragmentDtos.GitFileFragmentDto gfd ->
                // Use current project root for cross-platform compatibility
                ContextFragment.GitFileFragment.withId(
                        mgr.toFile(gfd.relPath()), gfd.revision(), reader.readContent(gfd.contentId()), gfd.id());
            case V3_FragmentDtos.FrozenFragmentDto ffd -> {
                // Unfreeze all FrozenFragmentDto objects during V3 migration.
                // No fallback to frozen objects; fail fast if metadata is insufficient.
                try {
                    yield fromFrozenDtoToLiveFragment(ffd, mgr, reader);
                } catch (Exception e) {
                    logger.error(
                            "MIGRATION FAILURE: Unable to unfreeze FrozenFragment {} (originalType={}, originalClass={}). "
                                    + "The fragment metadata may be incomplete or corrupted. "
                                    + "Error: {}",
                            ffd.id(),
                            ffd.originalType(),
                            ffd.originalClassName(),
                            e.getMessage(),
                            e);
                    throw new RuntimeException(
                            "V3 migration: Failed to unfreeze FrozenFragment " + ffd.id()
                                    + "; aborting to prevent FrozenFragment objects in V4 context. "
                                    + "Cause: " + e.getMessage(),
                            e);
                }
            }
        };
    }

    private static @Nullable ContextFragment.TaskFragment _buildTaskFragment(
            @Nullable V3_FragmentDtos.TaskFragmentDto dto, IContextManager mgr, V3_HistoryIo.ContentReader reader) {
        if (dto == null) return null;
        var messages = dto.messages().stream()
                .map(msgDto -> fromChatMessageDto(msgDto, reader))
                .toList();
        return new ContextFragment.TaskFragment(dto.id(), mgr, messages, dto.sessionName());
    }

    private static ContextFragment fromFrozenDtoToLiveFragment(
            V3_FragmentDtos.FrozenFragmentDto ffd, IContextManager mgr, V3_HistoryIo.ContentReader reader) {
        var original = ffd.originalClassName();
        var meta = ffd.meta();
        try {
            switch (original) {
                case "ai.brokk.util.migrationv4.V3_FragmentDtos$ProjectFileDto",
                        "io.github.jbellis.brokk.context.ContextFragment$ProjectPathFragment",
                        "ai.brokk.context.ContextFragment$ProjectPathFragment" -> {
                    var relPath = meta.get("relPath");
                    if (relPath == null)
                        throw new IllegalArgumentException("Missing metadata 'relPath' for ProjectPathFragment");
                    var file = mgr.toFile(relPath);
                    return new ContextFragment.ProjectPathFragment(file, mgr);
                }
                case "io.github.jbellis.brokk.context.ContextFragment$ExternalPathFragment",
                        "ai.brokk.context.ContextFragment$ExternalPathFragment" -> {
                    var absPath = meta.get("absPath");
                    if (absPath == null)
                        throw new IllegalArgumentException("Missing metadata 'absPath' for ExternalPathFragment");
                    var file = new ExternalFile(Path.of(absPath).toAbsolutePath());
                    return new ContextFragment.ExternalPathFragment(file, mgr);
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
                    var targetIdentifiersStr = meta.get("targetIdentifiers");
                    var summaryTypeStr = meta.get("summaryType");
                    if (targetIdentifiersStr == null || summaryTypeStr == null) {
                        throw new IllegalArgumentException(
                                "Missing 'targetIdentifiers' or 'summaryType' for SkeletonFragment");
                    }
                    var targets = targetIdentifiersStr.isEmpty()
                            ? List.<String>of()
                            : List.of(targetIdentifiersStr.split(";"));
                    var summaryType = ContextFragment.SummaryType.valueOf(summaryTypeStr);
                    return new ContextFragment.SkeletonFragment(mgr, targets, summaryType);
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
                    return new ContextFragment.UsageFragment(mgr, targetIdentifier);
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
                    return new ContextFragment.CodeFragment(mgr, fqName);
                }
                default -> {
                    throw new RuntimeException("Unsupported FrozenFragment originalClassName=" + original);
                }
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

    private static @Nullable ContextFragment.VirtualFragment _buildVirtualFragment(
            @Nullable V3_FragmentDtos.VirtualFragmentDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            Map<String, V3_FragmentDtos.ReferencedFragmentDto> allReferencedDtos,
            Map<String, V3_FragmentDtos.VirtualFragmentDto> allVirtualDtos,
            Map<String, V3_FragmentDtos.TaskFragmentDto> allTaskDtos,
            V3_HistoryIo.ContentReader reader) {
        if (dto == null) return null;
        return switch (dto) {
            case V3_FragmentDtos.FrozenFragmentDto ffd -> {
                if (isDeprecatedBuildFragment(ffd)) {
                    logger.info("Skipping deprecated BuildFragment during deserialization: {}", ffd.id());
                    yield null;
                }
                // FrozenFragmentDto is treated as a ReferencedFragmentDto and unfrozen during deserialization.
                // No fallback to frozen objects; _buildReferencedFragment() will fail fast if unfreezing fails.
                yield (ContextFragment.VirtualFragment) _buildReferencedFragment(ffd, mgr, reader);
            }
            case V3_FragmentDtos.SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(cuDto -> fromCodeUnitDto(cuDto, mgr))
                        .collect(Collectors.toSet());
                var messages = searchDto.messages().stream()
                        .map(msgDto -> fromChatMessageDto(msgDto, reader))
                        .toList();
                yield new ContextFragment.SearchFragment(searchDto.id(), mgr, searchDto.query(), messages, sources);
            }
            case V3_FragmentDtos.TaskFragmentDto taskDto -> _buildTaskFragment(taskDto, mgr, reader);
            case V3_FragmentDtos.StringFragmentDto stringDto ->
                new ContextFragment.StringFragment(
                        stringDto.id(),
                        mgr,
                        reader.readContent(stringDto.contentId()),
                        stringDto.description(),
                        stringDto.syntaxStyle());
            case V3_FragmentDtos.SkeletonFragmentDto skeletonDto ->
                new ContextFragment.SkeletonFragment(
                        skeletonDto.id(),
                        mgr,
                        skeletonDto.targetIdentifiers(),
                        mapSummaryType(skeletonDto.summaryType()));
            case V3_FragmentDtos.SummaryFragmentDto summaryDto ->
                new ContextFragment.SummaryFragment(
                        summaryDto.id(), mgr, summaryDto.targetIdentifier(), mapSummaryType(summaryDto.summaryType()));
            case V3_FragmentDtos.UsageFragmentDto usageDto ->
                new ContextFragment.UsageFragment(
                        usageDto.id(), mgr, usageDto.targetIdentifier(), usageDto.includeTestFiles());
            case V3_FragmentDtos.PasteTextFragmentDto pasteTextDto ->
                new ContextFragment.PasteTextFragment(
                        pasteTextDto.id(),
                        mgr,
                        reader.readContent(pasteTextDto.contentId()),
                        CompletableFuture.completedFuture(pasteTextDto.description()),
                        CompletableFuture.completedFuture(
                                requireNonNullElse(pasteTextDto.syntaxStyle(), SyntaxConstants.SYNTAX_STYLE_MARKDOWN)));
            case V3_FragmentDtos.PasteImageFragmentDto pasteImageDto -> {
                try {
                    byte[] imageBytes = reader.readImageBytes(pasteImageDto.id());
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
            case V3_FragmentDtos.StacktraceFragmentDto stDto -> {
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
            case V3_FragmentDtos.CallGraphFragmentDto callGraphDto ->
                new ContextFragment.CallGraphFragment(
                        callGraphDto.id(),
                        mgr,
                        callGraphDto.methodName(),
                        callGraphDto.depth(),
                        callGraphDto.isCalleeGraph());
            case V3_FragmentDtos.CodeFragmentDto codeDto -> {
                // Extract fully qualified name from the V3 CodeUnitDto and preserve the ID
                String fqName = buildFullyQualifiedName(codeDto.unit());
                yield new ContextFragment.CodeFragment(codeDto.id(), mgr, fqName);
            }
            case V3_FragmentDtos.BuildFragmentDto bfDto -> {
                // Backward compatibility: convert legacy BuildFragment to StringFragment with BUILD_RESULTS
                var text = reader.readContent(bfDto.contentId());
                yield new ContextFragment.StringFragment(
                        bfDto.id(),
                        mgr,
                        text,
                        ContextFragment.BUILD_RESULTS.description(),
                        ContextFragment.BUILD_RESULTS.syntaxStyle());
            }
            case V3_FragmentDtos.HistoryFragmentDto historyDto -> {
                var historyEntries = historyDto.history().stream()
                        .map(taskEntryDto -> _fromTaskEntryDto(
                                taskEntryDto,
                                mgr,
                                fragmentCacheForRecursion,
                                allReferencedDtos,
                                allVirtualDtos,
                                allTaskDtos,
                                reader))
                        .toList();
                yield new ContextFragment.HistoryFragment(historyDto.id(), mgr, historyEntries);
            }
        };
    }

    private static BrokkFile fromImageFileDtoToBrokkFile(V3_FragmentDtos.ImageFileDto ifd, IContextManager mgr) {
        Path path = Path.of(ifd.absPath()).toAbsolutePath();
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

    private static ProjectFile fromProjectFileDto(V3_FragmentDtos.ProjectFileDto dto, IContextManager mgr) {
        // Use the current project root instead of the serialized one to handle cross-platform compatibility
        // (e.g., when a V3 ZIP was created on Unix but deserialized on Windows)
        return mgr.toFile(dto.relPath());
    }

    private static ChatMessage fromChatMessageDto(
            V3_FragmentDtos.ChatMessageDto dto, V3_HistoryIo.ContentReader reader) {
        String content = reader.readContent(dto.contentId());
        return switch (dto.role().toLowerCase(Locale.ROOT)) {
            case "user" -> UserMessage.from(content);
            case "ai" -> AiMessage.from(content);
            case "system", "custom" -> SystemMessage.from(content);
            default -> throw new IllegalArgumentException("Unsupported message role: " + dto.role());
        };
    }

    private static TaskEntry _fromTaskEntryDto(
            V3_FragmentDtos.TaskEntryDto dto,
            IContextManager mgr,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            Map<String, V3_FragmentDtos.ReferencedFragmentDto> allReferencedDtos,
            Map<String, V3_FragmentDtos.VirtualFragmentDto> allVirtualDtos,
            Map<String, V3_FragmentDtos.TaskFragmentDto> allTaskDtos,
            V3_HistoryIo.ContentReader reader) {
        if (dto.log() != null) {
            var taskFragment = (ContextFragment.TaskFragment) fragmentCacheForRecursion.computeIfAbsent(
                    dto.log().id(),
                    id -> resolveAndBuildFragment(
                            id,
                            allReferencedDtos,
                            allVirtualDtos,
                            allTaskDtos,
                            mgr,
                            fragmentCacheForRecursion,
                            reader));
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summaryContentId() != null) {
            String summary = reader.readContent(dto.summaryContentId());
            return TaskEntry.fromCompressed(dto.sequence(), summary);
        }
        throw new IllegalArgumentException("TaskEntryDto has neither log nor summary");
    }

    private static CodeUnit fromCodeUnitDto(V3_FragmentDtos.CodeUnitDto dto, IContextManager mgr) {
        V3_FragmentDtos.ProjectFileDto pfd = dto.sourceFile();
        // Use current project root for cross-platform compatibility
        ProjectFile source = mgr.toFile(pfd.relPath());
        var kind = CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName());
    }

    private static boolean isDeprecatedBuildFragment(V3_FragmentDtos.FrozenFragmentDto ffd) {
        return "io.github.jbellis.brokk.context.ContextFragment$BuildFragment".equals(ffd.originalClassName())
                || "BUILD_LOG".equals(ffd.originalType());
    }

    private static String buildFullyQualifiedName(V3_FragmentDtos.CodeUnitDto cuDto) {
        String packageName = cuDto.packageName();
        String shortName = cuDto.shortName();
        if (packageName.isEmpty()) {
            return shortName;
        }
        return packageName + "." + shortName;
    }

    // Map legacy enum values to current ones to avoid brittle JSON string replacements
    private static ContextFragment.SummaryType mapSummaryType(String raw) {
        String normalized = "CLASS_SKELETON".equals(raw) ? "CODEUNIT_SKELETON" : raw;
        return ContextFragment.SummaryType.valueOf(normalized);
    }

    /* ───────────── entryInfos mapping ───────────── */

    public static Map<String, ContextHistory.ContextHistoryEntryInfo> fromEntryInfosDto(
            Map<String, V3_FragmentDtos.EntryInfoDto> dtoMap, IContextManager mgr) {
        return dtoMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ContextHistory.ContextHistoryEntryInfo(e.getValue().deletedFiles().stream()
                                .map(dto -> fromDeletedFileDto(dto, mgr))
                                .toList())));
    }

    private static ContextHistory.DeletedFile fromDeletedFileDto(
            V3_FragmentDtos.DeletedFileDto dto, IContextManager mgr) {
        return new ContextHistory.DeletedFile(fromProjectFileDto(dto.file(), mgr), dto.content(), dto.wasTracked());
    }
}

package ai.brokk.util.migrationv4;

import static java.util.Objects.requireNonNullElse;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import ai.brokk.IContextManager;
import ai.brokk.TaskEntry;
import ai.brokk.analyzer.*;
import ai.brokk.context.Context;
import ai.brokk.context.ContextFragment;
import ai.brokk.context.ContextHistory;
import ai.brokk.context.FrozenFragment;
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
            @Nullable Map<String, byte[]> imageBytesMap,
            Map<String, ContextFragment> fragmentCacheForRecursion,
            V3_HistoryIo.ContentReader contentReader) {
        if (referencedDtos.containsKey(idToResolve)) {
            var dto = referencedDtos.get(idToResolve);
            if (dto instanceof V3_FragmentDtos.FrozenFragmentDto ffd && isDeprecatedBuildFragment(ffd)) {
                logger.info("Skipping deprecated BuildFragment during deserialization: {}", idToResolve);
                return null;
            }
            return _buildReferencedFragment(castNonNull(dto), mgr, imageBytesMap, contentReader);
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

    private static ContextFragment _buildReferencedFragment(
            V3_FragmentDtos.ReferencedFragmentDto dto,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
            V3_HistoryIo.ContentReader reader) {
        return switch (dto) {
            case V3_FragmentDtos.ProjectFileDto pfd ->
                ContextFragment.ProjectPathFragment.withId(
                        new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath())), pfd.id(), mgr);
            case V3_FragmentDtos.ExternalFileDto efd ->
                ContextFragment.ExternalPathFragment.withId(new ExternalFile(Path.of(efd.absPath())), efd.id(), mgr);
            case V3_FragmentDtos.ImageFileDto ifd -> {
                BrokkFile file = fromImageFileDtoToBrokkFile(ifd, mgr);
                yield ContextFragment.ImageFileFragment.withId(file, ifd.id(), mgr);
            }
            case V3_FragmentDtos.GitFileFragmentDto gfd ->
                ContextFragment.GitFileFragment.withId(
                        new ProjectFile(Path.of(gfd.repoRoot()), Path.of(gfd.relPath())),
                        gfd.revision(),
                        reader.readContent(gfd.contentId()),
                        gfd.id());
            case V3_FragmentDtos.FrozenFragmentDto ffd -> {
                // TODO: [Migration4] Frozen fragments are to be replaced and mapped to "Fragments"
                yield FrozenFragment.fromDto(
                        ffd.id(),
                        mgr,
                        ContextFragment.FragmentType.valueOf(ffd.originalType()),
                        ffd.description(),
                        ffd.shortDescription(),
                        ffd.isTextFragment() ? reader.readContent(Objects.requireNonNull(ffd.contentId())) : null,
                        imageBytesMap != null ? imageBytesMap.get(ffd.id()) : null,
                        ffd.isTextFragment(),
                        ffd.syntaxStyle(),
                        ffd.files().stream()
                                .map(V3_DtoMapper::fromProjectFileDto)
                                .collect(Collectors.toSet()),
                        ffd.originalClassName(),
                        ffd.meta(),
                        ffd.repr());
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

    private static @Nullable ContextFragment.VirtualFragment _buildVirtualFragment(
            @Nullable V3_FragmentDtos.VirtualFragmentDto dto,
            IContextManager mgr,
            @Nullable Map<String, byte[]> imageBytesMap,
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
                yield (ContextFragment.VirtualFragment) _buildReferencedFragment(ffd, mgr, imageBytesMap, reader);
            }
            case V3_FragmentDtos.SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(V3_DtoMapper::fromCodeUnitDto)
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
                    if (imageBytesMap == null) {
                        logger.error("imageBytesMap is null, cannot load image for {}", pasteImageDto.id());
                        yield null;
                    }
                    byte[] imageBytes = imageBytesMap.get(pasteImageDto.id());
                    if (imageBytes == null) {
                        logger.error("Image bytes not found for fragment: {}", pasteImageDto.id());
                        yield null;
                    }
                    var image = FrozenFragment.bytesToImage(imageBytes);
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
                        .map(V3_DtoMapper::fromCodeUnitDto)
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
            case V3_FragmentDtos.CodeFragmentDto codeDto ->
                new ContextFragment.CodeFragment(codeDto.id(), mgr, fromCodeUnitDto(codeDto.unit()));
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
        Path path = Path.of(ifd.absPath());
        Path projectRoot = mgr.getProject().getRoot();
        if (path.startsWith(projectRoot)) {
            try {
                Path relPath = projectRoot.relativize(path);
                return new ProjectFile(projectRoot, relPath);
            } catch (IllegalArgumentException e) {
                return new ExternalFile(path);
            }
        }
        return new ExternalFile(path);
    }

    private static ProjectFile fromProjectFileDto(V3_FragmentDtos.ProjectFileDto dto) {
        return new ProjectFile(Path.of(dto.repoRoot()), Path.of(dto.relPath()));
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
                            null,
                            fragmentCacheForRecursion,
                            reader));
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summaryContentId() != null) {
            String summary = reader.readContent(dto.summaryContentId());
            return TaskEntry.fromCompressed(dto.sequence(), summary);
        }
        throw new IllegalArgumentException("TaskEntryDto has neither log nor summary");
    }

    private static CodeUnit fromCodeUnitDto(V3_FragmentDtos.CodeUnitDto dto) {
        V3_FragmentDtos.ProjectFileDto pfd = dto.sourceFile();
        ProjectFile source = new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath()));
        var kind = CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName());
    }

    private static boolean isDeprecatedBuildFragment(V3_FragmentDtos.FrozenFragmentDto ffd) {
        return "io.github.jbellis.brokk.context.ContextFragment$BuildFragment".equals(ffd.originalClassName())
                || "BUILD_LOG".equals(ffd.originalType());
    }

    // Map legacy enum values to current ones to avoid brittle JSON string replacements
    private static ContextFragment.SummaryType mapSummaryType(String raw) {
        String normalized = "CLASS_SKELETON".equals(raw) ? "CODEUNIT_SKELETON" : raw;
        return ContextFragment.SummaryType.valueOf(normalized);
    }

    /* ───────────── entryInfos mapping ───────────── */

    public static Map<String, ContextHistory.ContextHistoryEntryInfo> fromEntryInfosDto(
            Map<String, V3_FragmentDtos.EntryInfoDto> dtoMap) {
        return dtoMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ContextHistory.ContextHistoryEntryInfo(e.getValue().deletedFiles().stream()
                                .map(V3_DtoMapper::fromDeletedFileDto)
                                .toList())));
    }

    private static ContextHistory.DeletedFile fromDeletedFileDto(V3_FragmentDtos.DeletedFileDto dto) {
        return new ContextHistory.DeletedFile(fromProjectFileDto(dto.file()), dto.content(), dto.wasTracked());
    }
}

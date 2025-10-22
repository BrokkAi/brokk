package io.github.jbellis.brokk.util.migrationv4;

import static java.util.Objects.requireNonNullElse;
import static org.checkerframework.checker.nullness.util.NullnessUtil.castNonNull;

import com.google.common.collect.Streams;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.*;
import io.github.jbellis.brokk.context.Context;
import io.github.jbellis.brokk.context.ContextFragment;
import io.github.jbellis.brokk.context.ContextHistory;
import io.github.jbellis.brokk.util.Messages;
import io.github.jbellis.brokk.util.migrationv4.V3_FragmentDtos.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
                V3_FrozenFragment frozenFragment;
                // TODO: [Migration4] Frozen fragments are to be replaced and mapped to "Fragments"
                frozenFragment = V3_FrozenFragment.fromDto(
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
                yield frozenFragment;
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
                yield (V3_FrozenFragment) _buildReferencedFragment(ffd, mgr, imageBytesMap, reader);
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
                        ContextFragment.SummaryType.valueOf(skeletonDto.summaryType()));
            case V3_FragmentDtos.SummaryFragmentDto summaryDto ->
                new ContextFragment.SummaryFragment(
                        summaryDto.id(),
                        mgr,
                        summaryDto.targetIdentifier(),
                        ContextFragment.SummaryType.valueOf(summaryDto.summaryType()));
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
                    var image = V3_FrozenFragment.bytesToImage(imageBytes);
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

    private static V3_FragmentDtos.FrozenFragmentDto toFrozenFragmentDto(
            V3_FrozenFragment ff, V3_HistoryIo.ContentWriter writer) {
        try {
            String contentId = null;
            if (ff.isText()) {
                String singleFile = ff.files().size() == 1
                        ? ff.files().stream()
                                .findFirst()
                                .map(pf -> pf.getRelPath().toString())
                                .orElse(null)
                        : null;
                contentId = writer.writeContent(ff.text(), singleFile);
            }
            var filesDto =
                    ff.files().stream().map(V3_DtoMapper::toProjectFileDto).collect(Collectors.toSet());
            return new V3_FragmentDtos.FrozenFragmentDto(
                    ff.id(),
                    ff.getType().name(),
                    ff.description(),
                    ff.shortDescription(),
                    contentId,
                    ff.isText(),
                    ff.syntaxStyle(),
                    filesDto,
                    ff.originalClassName(),
                    ff.meta(),
                    ff.repr());
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize FrozenFragment to DTO: " + ff.id(), e);
        }
    }

    public static V3_FragmentDtos.ReferencedFragmentDto toReferencedFragmentDto(
            ContextFragment fragment, V3_HistoryIo.ContentWriter writer) {
        if (fragment instanceof V3_FrozenFragment ff) {
            return toFrozenFragmentDto(ff, writer);
        }
        return switch (fragment) {
            case ContextFragment.ProjectPathFragment pf -> toProjectFileDto(pf);
            case ContextFragment.GitFileFragment gf -> {
                var file = gf.file();
                var fileKey =
                        file.getRoot().toString() + ":" + file.getRelPath().toString();
                String contentId = writer.writeContent(gf.content(), fileKey);
                yield new V3_FragmentDtos.GitFileFragmentDto(
                        gf.id(), file.getRoot().toString(), file.getRelPath().toString(), gf.revision(), contentId);
            }
            case ContextFragment.ExternalPathFragment ef ->
                new V3_FragmentDtos.ExternalFileDto(ef.id(), ef.file().getPath().toString());
            case ContextFragment.ImageFileFragment imf -> {
                var file = imf.file();
                String absPath = file.absPath().toString();
                String fileName = file.getFileName().toLowerCase(Locale.ROOT);
                String mediaType = null;
                if (fileName.endsWith(".png")) mediaType = "image/png";
                else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) mediaType = "image/jpeg";
                else if (fileName.endsWith(".gif")) mediaType = "image/gif";
                yield new V3_FragmentDtos.ImageFileDto(imf.id(), absPath, mediaType);
            }
            default ->
                throw new IllegalArgumentException(
                        "Unsupported fragment type for referenced DTO conversion: " + fragment.getClass());
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

    private static V3_FragmentDtos.ProjectFileDto toProjectFileDto(ContextFragment.ProjectPathFragment fragment) {
        var file = fragment.file();
        return new V3_FragmentDtos.ProjectFileDto(
                fragment.id(), file.getRoot().toString(), file.getRelPath().toString());
    }

    private static V3_FragmentDtos.ProjectFileDto toProjectFileDto(ProjectFile pf) {
        return new V3_FragmentDtos.ProjectFileDto(
                "0", pf.getRoot().toString(), pf.getRelPath().toString());
    }

    public static V3_FragmentDtos.VirtualFragmentDto toVirtualFragmentDto(
            ContextFragment.VirtualFragment fragment, V3_HistoryIo.ContentWriter writer) {
        if (fragment instanceof V3_FrozenFragment ff) {
            return toFrozenFragmentDto(ff, writer);
        }

        return switch (fragment) {
            case ContextFragment.SearchFragment searchFragment -> {
                var sourcesDto = searchFragment.sources().stream()
                        .map(V3_DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                var messagesDto = searchFragment.messages().stream()
                        .map(m -> toChatMessageDto(m, writer))
                        .toList();
                yield new V3_FragmentDtos.SearchFragmentDto(
                        searchFragment.id(), searchFragment.description(), "", sourcesDto, messagesDto);
            }
            case ContextFragment.TaskFragment tf -> toTaskFragmentDto(tf, writer);
            case ContextFragment.StringFragment sf ->
                new V3_FragmentDtos.StringFragmentDto(
                        sf.id(), writer.writeContent(sf.text(), null), sf.description(), sf.syntaxStyle());
            case ContextFragment.SkeletonFragment skf ->
                new V3_FragmentDtos.SkeletonFragmentDto(
                        skf.id(),
                        skf.getTargetIdentifiers(),
                        skf.getSummaryType().name());
            case ContextFragment.SummaryFragment sumf ->
                new V3_FragmentDtos.SummaryFragmentDto(
                        sumf.id(),
                        sumf.getTargetIdentifier(),
                        sumf.getSummaryType().name());
            case ContextFragment.UsageFragment uf ->
                new V3_FragmentDtos.UsageFragmentDto(uf.id(), uf.targetIdentifier(), uf.includeTestFiles());
            case ContextFragment.PasteTextFragment ptf -> {
                String description = getFutureDescription(ptf.getDescriptionFuture(), "Paste of ");
                String contentId = writer.writeContent(ptf.text(), null);
                String syntaxStyle = getFutureSyntaxStyle(ptf.getSyntaxStyleFuture());
                yield new V3_FragmentDtos.PasteTextFragmentDto(ptf.id(), contentId, description, syntaxStyle);
            }
            case ContextFragment.AnonymousImageFragment aif -> {
                String description = getFutureDescription(aif.getDescriptionFuture(), "Paste of ");
                yield new V3_FragmentDtos.PasteImageFragmentDto(aif.id(), description);
            }
            case ContextFragment.StacktraceFragment stf -> {
                var sourcesDto =
                        stf.sources().stream().map(V3_DtoMapper::toCodeUnitDto).collect(Collectors.toSet());
                String originalContentId = writer.writeContent(stf.getOriginal(), null);
                String codeContentId = writer.writeContent(stf.getCode(), null);
                yield new V3_FragmentDtos.StacktraceFragmentDto(
                        stf.id(), sourcesDto, originalContentId, stf.getException(), codeContentId);
            }
            case ContextFragment.CallGraphFragment cgf ->
                new V3_FragmentDtos.CallGraphFragmentDto(
                        cgf.id(), cgf.getMethodName(), cgf.getDepth(), cgf.isCalleeGraph());
            case ContextFragment.CodeFragment cf ->
                new V3_FragmentDtos.CodeFragmentDto(cf.id(), toCodeUnitDto(cf.getCodeUnit()));
            case ContextFragment.HistoryFragment hf -> {
                var historyDto = hf.entries().stream()
                        .map(te -> toTaskEntryDto(te, writer))
                        .toList();
                yield new V3_FragmentDtos.HistoryFragmentDto(hf.id(), historyDto);
            }
            default ->
                throw new IllegalArgumentException("Unsupported VirtualFragment type for DTO conversion: "
                        + fragment.getClass().getName());
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

    private static V3_FragmentDtos.TaskEntryDto toTaskEntryDto(TaskEntry entry, V3_HistoryIo.ContentWriter writer) {
        V3_FragmentDtos.TaskFragmentDto logDto = null;
        if (entry.log() != null) {
            logDto = toTaskFragmentDto(entry.log(), writer);
        }
        String summaryContentId = null;
        if (entry.summary() != null) {
            summaryContentId = writer.writeContent(entry.summary(), null);
        }
        return new V3_FragmentDtos.TaskEntryDto(entry.sequence(), logDto, summaryContentId);
    }

    public static V3_FragmentDtos.TaskFragmentDto toTaskFragmentDto(
            ContextFragment.TaskFragment fragment, V3_HistoryIo.ContentWriter writer) {
        var messagesDto = fragment.messages().stream()
                .map(m -> toChatMessageDto(m, writer))
                .toList();
        return new V3_FragmentDtos.TaskFragmentDto(fragment.id(), messagesDto, fragment.description());
    }

    private static V3_FragmentDtos.ChatMessageDto toChatMessageDto(
            ChatMessage message, V3_HistoryIo.ContentWriter writer) {
        String contentId = writer.writeContent(Messages.getRepr(message), null);
        return new V3_FragmentDtos.ChatMessageDto(message.type().name().toLowerCase(Locale.ROOT), contentId);
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

    private static V3_FragmentDtos.CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) {
        ProjectFile pf = codeUnit.source();
        V3_FragmentDtos.ProjectFileDto pfd = new V3_FragmentDtos.ProjectFileDto(
                "0", pf.getRoot().toString(), pf.getRelPath().toString());
        return new V3_FragmentDtos.CodeUnitDto(
                pfd, codeUnit.kind().name(), codeUnit.packageName(), codeUnit.shortName());
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

    /* ───────────── entryInfos mapping ───────────── */

    public static Map<String, V3_FragmentDtos.EntryInfoDto> toEntryInfosDto(
            Map<UUID, ContextHistory.ContextHistoryEntryInfo> entryInfos) {
        return entryInfos.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> e.getKey().toString(),
                        e -> new V3_FragmentDtos.EntryInfoDto(e.getValue().deletedFiles().stream()
                                .map(V3_DtoMapper::toDeletedFileDto)
                                .toList())));
    }

    public static Map<String, ContextHistory.ContextHistoryEntryInfo> fromEntryInfosDto(
            Map<String, V3_FragmentDtos.EntryInfoDto> dtoMap) {
        return dtoMap.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> new ContextHistory.ContextHistoryEntryInfo(e.getValue().deletedFiles().stream()
                                .map(V3_DtoMapper::fromDeletedFileDto)
                                .toList())));
    }

    private static V3_FragmentDtos.DeletedFileDto toDeletedFileDto(ContextHistory.DeletedFile df) {
        return new V3_FragmentDtos.DeletedFileDto(toProjectFileDto(df.file()), df.content(), df.wasTracked());
    }

    private static ContextHistory.DeletedFile fromDeletedFileDto(V3_FragmentDtos.DeletedFileDto dto) {
        return new ContextHistory.DeletedFile(fromProjectFileDto(dto.file()), dto.content(), dto.wasTracked());
    }
}

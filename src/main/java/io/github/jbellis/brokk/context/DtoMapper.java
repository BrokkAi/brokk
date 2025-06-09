package io.github.jbellis.brokk.context;

import dev.langchain4j.data.message.ChatMessage;
import io.github.jbellis.brokk.IContextManager;
import io.github.jbellis.brokk.TaskEntry;
import io.github.jbellis.brokk.analyzer.BrokkFile;
import io.github.jbellis.brokk.analyzer.CodeUnit;
import io.github.jbellis.brokk.analyzer.ExternalFile;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import io.github.jbellis.brokk.context.FragmentDtos.*;
import io.github.jbellis.brokk.util.Messages;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.stream.Collectors;

/**
 * Mapper to convert between Context domain objects and DTO representations.
 */
public class DtoMapper {
    private static final Logger logger = LogManager.getLogger(DtoMapper.class);

    private DtoMapper() {
        // Utility class - no instantiation
    }

    /**
     * Converts a CompactContextDto back to a Context domain object using a pre-populated fragment cache.
     */
    public static Context fromCompactDto(CompactContextDto dto, IContextManager mgr, Map<String, ContextFragment> fragmentCache, Map<String, byte[]> imageBytesMap) {
        var editableFragments = dto.editable().stream()
                .map(id -> fragmentCache.get(id)) // Cast needed as map stores ContextFragment
                .filter(java.util.Objects::nonNull) // Filter out if ID not found, though ideally all should be present
                .collect(Collectors.toList());

        var readonlyFragments = dto.readonly().stream()
                .map(id -> fragmentCache.get(id)) // Cast needed
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        var virtualFragments = dto.virtuals().stream()
                .map(id -> (ContextFragment.VirtualFragment) fragmentCache.get(id)) // Specific cast
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toList());

        var taskHistory = dto.tasks().stream()
                .map(taskRefDto -> {
                    if (taskRefDto.logId() != null) {
                        ContextFragment.TaskFragment logFragment = (ContextFragment.TaskFragment) fragmentCache.get(taskRefDto.logId());
                        if (logFragment != null) {
                            return new TaskEntry(taskRefDto.sequence(), logFragment, null);
                        } else {
                            // This case should ideally not happen if all fragments are resolved correctly.
                            // If it does, it means a TaskFragment referenced by logId was not found in the cache.
                            logger.warn("TaskFragment with ID {} not found in cache for TaskEntryRefDto {}. Using summary if available.",
                                        taskRefDto.logId(), taskRefDto);
                            if (taskRefDto.summary() != null) { // Fallback to summary if log fragment is missing despite having an ID
                                return TaskEntry.fromCompressed(taskRefDto.sequence(), taskRefDto.summary());
                            }
                        }
                    } else if (taskRefDto.summary() != null) { // This is the normal path for compressed entries
                        return TaskEntry.fromCompressed(taskRefDto.sequence(), taskRefDto.summary());
                    }
                    // If neither logId (that resolves) nor summary is present.
                    logger.warn("TaskEntryRefDto {} could not be resolved to a TaskEntry (logId missing/unresolved and no summary).", taskRefDto);
                    return null;
                })
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        ContextFragment.TaskFragment parsedOutputFragment = null;
        if (dto.parsedOutputId() != null) {
            parsedOutputFragment = (ContextFragment.TaskFragment) fragmentCache.get(dto.parsedOutputId());
        }

        var actionFuture = CompletableFuture.completedFuture(dto.action());

        return new Context(Context.newId(), // Generate new transient ID for the live context
                           mgr,
                           editableFragments,
                           readonlyFragments,
                           virtualFragments,
                           taskHistory,
                           parsedOutputFragment,
                           actionFuture);
    }

    // Central method for resolving and building fragments, called by HistoryIo within computeIfAbsent
    public static ContextFragment resolveAndBuildFragment(String idToResolve,
                                                          Map<String, ReferencedFragmentDto> referencedDtos,
                                                          Map<String, VirtualFragmentDto> virtualDtos,
                                                          Map<String, TaskFragmentDto> taskDtos,
                                                          IContextManager mgr,
                                                          Map<String, byte[]> imageBytesMap,
                                                          Map<String, ContextFragment> fragmentCacheForRecursion) {
        if (referencedDtos.containsKey(idToResolve)) {
            return _buildReferencedFragment(referencedDtos.get(idToResolve), mgr, imageBytesMap, fragmentCacheForRecursion, referencedDtos, virtualDtos, taskDtos);
        }
        if (virtualDtos.containsKey(idToResolve)) {
            return _buildVirtualFragment(virtualDtos.get(idToResolve), mgr, imageBytesMap, fragmentCacheForRecursion, referencedDtos, virtualDtos, taskDtos);
        }
        if (taskDtos.containsKey(idToResolve)) {
            return _buildTaskFragment(taskDtos.get(idToResolve), mgr, fragmentCacheForRecursion, referencedDtos, virtualDtos, taskDtos);
        }
        logger.error("Fragment DTO not found for ID: {} during resolveAndBuildFragment", idToResolve);
        throw new IllegalStateException("Fragment DTO not found for ID: " + idToResolve + " during resolveAndBuildFragment");
    }

    private static ContextFragment _buildReferencedFragment(ReferencedFragmentDto dto, IContextManager mgr, Map<String, byte[]> imageBytesMap,
                                                           Map<String, ContextFragment> fragmentCacheForRecursion,
                                                           Map<String, ReferencedFragmentDto> allReferencedDtos,
                                                           Map<String, VirtualFragmentDto> allVirtualDtos,
                                                           Map<String, TaskFragmentDto> allTaskDtos) {
        if (dto == null) return null;

        return switch (dto) {
            case ProjectFileDto pfd -> ContextFragment.ProjectPathFragment.withId(
                    new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath())), pfd.id(), mgr);
            case ExternalFileDto efd -> ContextFragment.ExternalPathFragment.withId(
                    new ExternalFile(Path.of(efd.absPath())), efd.id(), mgr);
            case ImageFileDto ifd -> {
                BrokkFile file = fromImageFileDtoToBrokkFile(ifd, mgr);
                yield ContextFragment.ImageFileFragment.withId(file, ifd.id(), mgr);
            }
            case GitFileFragmentDto gfd -> ContextFragment.GitFileFragment.withId(
                    new ProjectFile(Path.of(gfd.repoRoot()), Path.of(gfd.relPath())), gfd.revision(), gfd.content(), gfd.id());
            case FrozenFragmentDto ffd ->
                FrozenFragment.fromDto(
                    ffd.id(),
                    mgr,
                    ContextFragment.FragmentType.valueOf(ffd.originalType()),
                    ffd.description(),
                    ffd.shortDescription(),
                    ffd.textContent(),
                    imageBytesMap.get(ffd.id()),
                    ffd.isTextFragment(),
                    ffd.syntaxStyle(),
                    ffd.files().stream().map(DtoMapper::fromProjectFileDto).collect(Collectors.toSet()),
                    ffd.originalClassName(),
                    ffd.meta()
                );
        };
    }

    private static ContextFragment.TaskFragment _buildTaskFragment(TaskFragmentDto dto, IContextManager mgr,
                                                                  Map<String, ContextFragment> fragmentCacheForRecursion,
                                                                  Map<String, ReferencedFragmentDto> allReferencedDtos,
                                                                  Map<String, VirtualFragmentDto> allVirtualDtos,
                                                                  Map<String, TaskFragmentDto> allTaskDtos) {
        if (dto == null) return null;

        var messages = dto.messages().stream()
                .map(DtoMapper::fromChatMessageDto)
                .toList();
        return new ContextFragment.TaskFragment(dto.id(), mgr, messages, dto.sessionName());
    }

    private static ContextFragment.VirtualFragment _buildVirtualFragment(VirtualFragmentDto dto, IContextManager mgr, Map<String, byte[]> imageBytesMap,
                                                                        Map<String, ContextFragment> fragmentCacheForRecursion,
                                                                        Map<String, ReferencedFragmentDto> allReferencedDtos,
                                                                        Map<String, VirtualFragmentDto> allVirtualDtos,
                                                                        Map<String, TaskFragmentDto> allTaskDtos) {
        if (dto == null) return null;

        return switch (dto) {
            case FrozenFragmentDto frozenDto ->
                FrozenFragment.fromDto(
                    frozenDto.id(),
                    mgr,
                    ContextFragment.FragmentType.valueOf(frozenDto.originalType()),
                    frozenDto.description(),
                    frozenDto.shortDescription(),
                    frozenDto.textContent(),
                    imageBytesMap.get(frozenDto.id()),
                    frozenDto.isTextFragment(),
                    frozenDto.syntaxStyle(),
                    frozenDto.files().stream().map(DtoMapper::fromProjectFileDto).collect(Collectors.toSet()),
                    frozenDto.originalClassName(),
                    frozenDto.meta()
                );
            case SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                var messages = searchDto.messages().stream()
                        .map(DtoMapper::fromChatMessageDto)
                        .toList();
                yield new ContextFragment.SearchFragment(searchDto.id(), mgr, searchDto.query(), messages, sources);
            }
            case TaskFragmentDto taskDto -> // This case implies a TaskFragmentDto was listed under "virtual"
                _buildTaskFragment(taskDto, mgr, fragmentCacheForRecursion, allReferencedDtos, allVirtualDtos, allTaskDtos);
            case StringFragmentDto stringDto ->
                new ContextFragment.StringFragment(stringDto.id(), mgr, stringDto.text(), stringDto.description(), stringDto.syntaxStyle());
            case SkeletonFragmentDto skeletonDto ->
                new ContextFragment.SkeletonFragment(skeletonDto.id(), mgr, skeletonDto.targetIdentifiers(), ContextFragment.SummaryType.valueOf(skeletonDto.summaryType()));
            case UsageFragmentDto usageDto ->
                new ContextFragment.UsageFragment(usageDto.id(), mgr, usageDto.targetIdentifier());
            case PasteTextFragmentDto pasteTextDto ->
                new ContextFragment.PasteTextFragment(pasteTextDto.id(), mgr, pasteTextDto.text(), CompletableFuture.completedFuture(pasteTextDto.description()));
            case PasteImageFragmentDto pasteImageDto -> {
                Image image = base64ToImage(pasteImageDto.base64ImageData());
                yield new ContextFragment.AnonymousImageFragment(pasteImageDto.id(), mgr, image, CompletableFuture.completedFuture(pasteImageDto.description()));
            }
            case StacktraceFragmentDto stacktraceDto -> {
                var sources = stacktraceDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new ContextFragment.StacktraceFragment(stacktraceDto.id(), mgr, sources, stacktraceDto.original(), stacktraceDto.exception(), stacktraceDto.code());
            }
            case CallGraphFragmentDto callGraphDto ->
                new ContextFragment.CallGraphFragment(callGraphDto.id(), mgr, callGraphDto.methodName(), callGraphDto.depth(), callGraphDto.isCalleeGraph());
            case HistoryFragmentDto historyDto -> {
                var historyEntries = historyDto.history().stream()
                        .map(taskEntryDto -> _fromTaskEntryDto(taskEntryDto, mgr, fragmentCacheForRecursion, allReferencedDtos, allVirtualDtos, allTaskDtos))
                        .filter(java.util.Objects::nonNull)
                        .toList();
                yield new ContextFragment.HistoryFragment(historyDto.id(), mgr, historyEntries);
            }
            // If any VirtualFragmentDto subtype was missed, it would fall through.
            // Ensure all subtypes of VirtualFragmentDto are handled in the switch.
            // default -> throw new IllegalArgumentException("Unsupported VirtualFragmentDto subtype: " + dto.getClass().getName());
        };
    }

    public static ReferencedFragmentDto toReferencedFragmentDto(ContextFragment fragment) {
        // If the fragment is already frozen, serialize as FrozenFragmentDto
        if (fragment instanceof FrozenFragment ff) {
            try {
                var filesDto = ff.files().stream().map(DtoMapper::toProjectFileDto).collect(Collectors.toSet());
                return new FrozenFragmentDto(
                    ff.id(), 
                    ff.getType().name(), 
                    ff.description(),
                    ff.shortDescription(),
                    ff.isText() ? ff.text() : null,
                    ff.isText(),
                    ff.syntaxStyle(),
                    filesDto,
                    ff.originalClassName(),
                    ff.meta()
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize FrozenFragment to DTO: " + ff.id(), e);
            }
        }
        // For live fragments, serialize to their specific DTOs
        return switch (fragment) {
            case ContextFragment.ProjectPathFragment pf -> toProjectFileDto(pf);
            case ContextFragment.GitFileFragment gf -> {
                var file = gf.file();
                yield new GitFileFragmentDto(gf.id(), file.getRoot().toString(), file.getRelPath().toString(), gf.revision(), gf.content());
            }
            case ContextFragment.ExternalPathFragment ef -> new ExternalFileDto(ef.id(), ef.file().getPath().toString());
            case ContextFragment.ImageFileFragment imf -> {
                var file = imf.file();
                String absPath = file.absPath().toString();
                String fileName = file.getFileName().toLowerCase();
                String mediaType = null;
                if (fileName.endsWith(".png")) mediaType = "image/png";
                else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) mediaType = "image/jpeg";
                else if (fileName.endsWith(".gif")) mediaType = "image/gif";
                yield new ImageFileDto(imf.id(), absPath, mediaType);
            }
            default -> throw new IllegalArgumentException("Unsupported fragment type for referenced DTO conversion: " + fragment.getClass());
        };
    }

private static BrokkFile fromImageFileDtoToBrokkFile(ImageFileDto ifd, IContextManager mgr) {
        Path path = Path.of(ifd.absPath());
        // Assuming IProject has a getRoot() method similar to ProjectFile that returns Path
        if (mgr != null && mgr.getProject() != null && mgr.getProject().getRoot() != null) { 
            Path projectRoot = mgr.getProject().getRoot(); 
            if (path.startsWith(projectRoot)) {
                try {
                    Path relPath = projectRoot.relativize(path);
                    return new ProjectFile(projectRoot, relPath);
                } catch (IllegalArgumentException e) {
                    // Path cannot be relativized, treat as external
                    return new ExternalFile(path);
                }
            }
        }
        return new ExternalFile(path);
    }
    
    private static ProjectFileDto toProjectFileDto(ContextFragment.ProjectPathFragment fragment) {
        var file = fragment.file();
        return new ProjectFileDto(fragment.id(), file.getRoot().toString(), file.getRelPath().toString());
    }
    
    private static ProjectFileDto toProjectFileDto(ProjectFile pf) {
        // This DTO is for ProjectFile instances not directly part of editable/readonly PathFragments,
        // e.g., those embedded in FrozenFragment.sources/files.
        // If this ProjectFile was never a standalone fragment, it doesn't have its own ID.
        // Using a placeholder like "0_pf" or a hash of its path could be options.
        // For now, using "0" as a String, consistent with it being a non-fragment identifier.
        return new ProjectFileDto("0", pf.getRoot().toString(), pf.getRelPath().toString());
    }

    public static VirtualFragmentDto toVirtualFragmentDto(ContextFragment.VirtualFragment fragment) {
        // If the fragment is already frozen, serialize as FrozenFragmentDto
        if (fragment instanceof FrozenFragment ff) {
            try {
                var filesDto = ff.files().stream()
                        .map(DtoMapper::toProjectFileDto)
                        .collect(Collectors.toSet());
                return new FrozenFragmentDto(
                    ff.id(),
                    ff.getType().name(),
                    ff.description(),
                    ff.shortDescription(),
                    ff.isText() ? ff.text() : null,
                    ff.isText(),
                    ff.syntaxStyle(),
                    filesDto,
                    ff.originalClassName(),
                    ff.meta()
                );
            } catch (Exception e) {
                logger.error("Failed to serialize FrozenFragment to DTO: {}", ff.id(), e);
                throw new RuntimeException("Failed to serialize FrozenFragment to DTO: " + ff.id(), e);
            }
        }

        // For live VirtualFragments, serialize to their specific DTOs
        return switch (fragment) {
            case ContextFragment.SearchFragment searchFragment -> {
                var sourcesDto = searchFragment.sources().stream()
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                var messagesDto = searchFragment.messages().stream()
                        .map(DtoMapper::toChatMessageDto)
                        .toList();
                // SearchFragment is a TaskFragment, its ID is content-hashed via TaskFragment's logic
                yield new SearchFragmentDto(
                        searchFragment.id(), // This ID comes from TaskFragment constructor
                        searchFragment.description(), // sessionName for TaskFragment
                        "", // explanation - no longer a separate field for SearchFragmentDto itself
                        sourcesDto,
                        messagesDto
                );
            }
            case ContextFragment.TaskFragment taskFragment -> toTaskFragmentDto(taskFragment); // Handles general TaskFragments
            case ContextFragment.StringFragment stringFragment -> new StringFragmentDto(
                    stringFragment.id(), stringFragment.text(), stringFragment.description(), stringFragment.syntaxStyle());
            case ContextFragment.SkeletonFragment skeletonFragment -> new SkeletonFragmentDto(
                    skeletonFragment.id(), skeletonFragment.getTargetIdentifiers(), skeletonFragment.getSummaryType().name());
            case ContextFragment.UsageFragment usageFragment -> new UsageFragmentDto(
                    usageFragment.id(), usageFragment.targetIdentifier());
            case ContextFragment.PasteTextFragment pasteTextFragment -> {
                String description = getFutureDescription(pasteTextFragment.descriptionFuture, "Paste of ");
                yield new PasteTextFragmentDto(pasteTextFragment.id(), pasteTextFragment.text(), description);
            }
            case ContextFragment.AnonymousImageFragment pasteImageFragment -> {
                String description = getFutureDescription(pasteImageFragment.descriptionFuture, "Paste of ");
                String base64ImageData = imageToBase64(pasteImageFragment.image());
                yield new PasteImageFragmentDto(pasteImageFragment.id(), base64ImageData, description);
            }
            case ContextFragment.StacktraceFragment stacktraceFragment -> {
                var sourcesDto = stacktraceFragment.sources().stream()
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new StacktraceFragmentDto(
                        stacktraceFragment.id(),
                        sourcesDto,
                        stacktraceFragment.getOriginal(),
                        stacktraceFragment.getException(),
                        stacktraceFragment.getCode()
                );
            }
            case ContextFragment.CallGraphFragment callGraphFragment -> new CallGraphFragmentDto(
                    callGraphFragment.id(), callGraphFragment.getMethodName(), callGraphFragment.getDepth(), callGraphFragment.isCalleeGraph());
            case ContextFragment.HistoryFragment historyFragment -> {
                var historyDto = historyFragment.entries().stream()
                        .map(DtoMapper::toTaskEntryDto)
                        .toList();
                yield new HistoryFragmentDto(historyFragment.id(), historyDto);
            }
            default -> throw new IllegalArgumentException("Unsupported VirtualFragment type for DTO conversion: " + fragment.getClass().getName());
        };
    }

    private static String getFutureDescription(Future<String> future, String prefix) {
        String description;
        try {
            String fullDescription = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
            description = fullDescription.startsWith(prefix)
                ? fullDescription.substring(prefix.length())
                : fullDescription;
        } catch (java.util.concurrent.TimeoutException e) {
            description = "(Paste description timed out)";
        } catch (Exception e) {
            description = "(Error getting paste description: " + e.getMessage() + ")";
        }
        return description;
    }
    
    private static TaskEntryDto toTaskEntryDto(TaskEntry entry) {
        TaskFragmentDto logDto = null;
        if (entry.log() != null) {
            var messagesDto = entry.log().messages().stream()
                    .map(DtoMapper::toChatMessageDto)
                    .toList();
            logDto = new TaskFragmentDto(entry.log().id(), messagesDto, entry.log().description()); // entry.log().id() is String
        }

        return new TaskEntryDto(entry.sequence(), logDto, entry.summary());
    }

    // TaskFragmentDto constructor expects String id
    public static TaskFragmentDto toTaskFragmentDto(ContextFragment.TaskFragment fragment) {
        var messagesDto = fragment.messages().stream()
                .map(DtoMapper::toChatMessageDto)
                .toList();
        return new TaskFragmentDto(fragment.id(), messagesDto, fragment.description()); // fragment.id() is String
    }

    private static ChatMessageDto toChatMessageDto(ChatMessage message) {
        return new ChatMessageDto(message.type().name().toLowerCase(), Messages.getRepr(message));
    }

    private static ProjectFile fromProjectFileDto(ProjectFileDto dto) {
        return new ProjectFile(Path.of(dto.repoRoot()), Path.of(dto.relPath()));
    }

    private static ExternalFile fromExternalFileDto(ExternalFileDto dto) {
        return new ExternalFile(Path.of(dto.absPath()));
    }

    private static BrokkFile fromPathFragmentDto(PathFragmentDto dto) { // IContextManager not needed for this helper
        return switch (dto) {
            case ProjectFileDto pfd -> fromProjectFileDto(pfd);
            case ExternalFileDto efd -> fromExternalFileDto(efd);
            case ImageFileDto ifd -> {
                // For ImageFileDto, we need to determine if it's a ProjectFile or ExternalFile based on the path
                Path path = Path.of(ifd.absPath());
                if (path.isAbsolute()) {
                    yield new ExternalFile(path);
                } else {
                    // This is problematic as we don't have the root - for now assume it's external
                    yield new ExternalFile(path.toAbsolutePath());
                }
            }
            // Assuming ProjectFileDto now takes String ID.
            // For GitFileFragmentDto, the ID is its hash, but when converting to BrokkFile for CodeUnitDto context,
            // it's just a file path. "0" string is fine as placeholder if BrokkFile doesn't need an ID here.
            case GitFileFragmentDto gfd -> fromProjectFileDto(new ProjectFileDto("0", gfd.repoRoot(), gfd.relPath()));
        };
    }

    private static ChatMessage fromChatMessageDto(ChatMessageDto dto) {
        // Convert role string back to ChatMessage
        return switch (dto.role().toLowerCase()) {
            case "user" -> dev.langchain4j.data.message.UserMessage.from(dto.content());
            case "ai" -> dev.langchain4j.data.message.AiMessage.from(dto.content());
            case "system" -> dev.langchain4j.data.message.SystemMessage.from(dto.content());
            case "custom" -> dev.langchain4j.data.message.SystemMessage.from(dto.content());
            default -> throw new IllegalArgumentException("Unsupported message role: " + dto.role());
        };
    }

    private static CodeUnitDto toCodeUnitDto(CodeUnit codeUnit) { // IContextManager not needed for serialization
        ProjectFile pf = codeUnit.source();
        // Assuming ProjectFileDto constructor now takes String ID
        ProjectFileDto pfd = new ProjectFileDto("0", pf.getRoot().toString(), pf.getRelPath().toString());
        return new CodeUnitDto(
                pfd,
                codeUnit.kind().name(),
                codeUnit.packageName(),
                codeUnit.shortName()
        );
    }

    // Note: fragmentCache parameter is for recursive resolution of dependencies.
    private static TaskEntry _fromTaskEntryDto(TaskEntryDto dto, IContextManager mgr,
                                              Map<String, ContextFragment> fragmentCacheForRecursion,
                                              Map<String, ReferencedFragmentDto> allReferencedDtos,
                                              Map<String, VirtualFragmentDto> allVirtualDtos,
                                              Map<String, TaskFragmentDto> allTaskDtos) {
        if (dto == null) return null;

        if (dto.log() != null) {
            ContextFragment.TaskFragment taskFragment = (ContextFragment.TaskFragment) fragmentCacheForRecursion.computeIfAbsent(
                dto.log().id(), // ID of the TaskFragment to resolve
                idToResolve -> resolveAndBuildFragment(idToResolve, allReferencedDtos, allVirtualDtos, allTaskDtos,
                                                       mgr, null, fragmentCacheForRecursion) // imageBytesMap not needed for TaskFragment directly
            );
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summary() != null) {
            return TaskEntry.fromCompressed(dto.sequence(), dto.summary());
        } else {
            logger.warn("TaskEntryDto {} had neither log nor summary during deserialization.", dto);
            return null; // Invalid TaskEntry
        }
    }

    private static CodeUnit fromCodeUnitDto(CodeUnitDto dto) { // IContextManager not needed as ProjectFileDto has full path info
        ProjectFileDto pfd = dto.sourceFile();
        ProjectFile source = new ProjectFile(Path.of(pfd.repoRoot()), Path.of(pfd.relPath()));
        var kind = io.github.jbellis.brokk.analyzer.CodeUnitType.valueOf(dto.kind());
        return new CodeUnit(source, kind, dto.packageName(), dto.shortName());
    }
    
    /**
     * Converts an Image to base64-encoded PNG data.
     */
    private static String imageToBase64(Image image) {
        try (var baos = new ByteArrayOutputStream()) {
            // Convert Image to BufferedImage if needed
            java.awt.image.BufferedImage bufferedImage;
            if (image instanceof java.awt.image.BufferedImage bi) {
                bufferedImage = bi;
            } else {
                bufferedImage = new java.awt.image.BufferedImage(
                    image.getWidth(null), 
                    image.getHeight(null), 
                    java.awt.image.BufferedImage.TYPE_INT_ARGB
                );
                var g = bufferedImage.createGraphics();
                g.drawImage(image, 0, 0, null);
                g.dispose();
            }
            
            ImageIO.write(bufferedImage, "PNG", baos);
            return Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert image to base64", e);
        }
    }
    
    /**
     * Converts base64-encoded image data back to an Image.
     */
    private static Image base64ToImage(String base64Data) {
        try {
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException e) {
            throw new RuntimeException("Failed to convert base64 to image", e);
        }
    }
}

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

import javax.imageio.ImageIO;
import java.awt.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Mapper to convert between Context domain objects and DTO representations.
 */
public class DtoMapper {
    
    private DtoMapper() {
        // Utility class - no instantiation
    }
    
    /**
     * Converts a Context domain object to its DTO representation.
     */
    public static ContextDto toDto(Context ctx) {
        var editableFilesDto = ctx.editableFiles()
                .map(fragment -> (ReferencedFragmentDto) DtoMapper.toReferencedFragmentDto(fragment))
                .toList();
        
        var readonlyFilesDto = ctx.readonlyFiles()
                .map(fragment -> (ReferencedFragmentDto) DtoMapper.toReferencedFragmentDto(fragment))
                .toList();
        
        var virtualFragmentsDto = ctx.virtualFragments()
                .map(DtoMapper::toVirtualFragmentDto)
                .filter(dto -> dto != null) // Skip unsupported fragments for now
                .toList();
        
        var taskHistoryDto = ctx.getTaskHistory().stream()
                .map(DtoMapper::toTaskEntryDto)
                .toList();
        
        var parsedOutputDto = ctx.getParsedOutput() != null 
                ? toTaskFragmentDto(ctx.getParsedOutput())
                : null;
        
        // Wait up to 5 seconds for action to resolve, fallback to "(Summary Unavailable)"
        String actionSummary;
        try {
            actionSummary = ctx.action.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            actionSummary = "(Summary Unavailable)";
        } catch (Exception e) {
            actionSummary = "(Error retrieving summary: " + e.getMessage() + ")";
        }
        
        return new ContextDto(editableFilesDto,
                readonlyFilesDto,
                virtualFragmentsDto,
                taskHistoryDto,
                parsedOutputDto,
                actionSummary);
    }
    
    /**
     * Converts a ContextHistory to its DTO representation.
     */
    public static HistoryDto toHistoryDto(ContextHistory ch) {
        var contexts = ch.getHistory().stream()
                .map(DtoMapper::toDto)
                .toList();
        return new HistoryDto(contexts);
    }
    
    /**
     * Converts a HistoryDto back to a ContextHistory domain object.
     */
    public static ContextHistory fromHistoryDto(HistoryDto dto, IContextManager mgr, Map<Integer, byte[]> imageBytesMap) {
        var ch = new ContextHistory();
        Map<Integer, ContextFragment> fragmentCache = new HashMap<>();
        
        var contexts = dto.contexts().stream()
                .map(contextDto -> DtoMapper.fromDto(contextDto, mgr, imageBytesMap, fragmentCache))
                .toList();
        
        if (!contexts.isEmpty()) {
            ch.setInitialContext(contexts.get(0));
            for (int i = 1; i < contexts.size(); i++) {
                final Context contextToAdd = contexts.get(i);
                ch.addFrozenContextAndClearRedo(contextToAdd);
            }
        }
        
        return ch;
    }
    
    /**
     * Converts a ContextDto back to a Context domain object.
     */
    public static Context fromDto(ContextDto dto, IContextManager mgr, Map<Integer, byte[]> imageBytesMap, Map<Integer, ContextFragment> fragmentCache) {
        var editableFragments = dto.editableFiles().stream()
                .map(dtoObj -> fromReferencedFragmentDto(dtoObj, mgr, imageBytesMap, fragmentCache))
                .collect(Collectors.toList());

        var readonlyFragments = dto.readonlyFiles().stream()
                .map(dtoObj -> fromReferencedFragmentDto(dtoObj, mgr, imageBytesMap, fragmentCache))
                .collect(Collectors.toList());

        var virtualFragments = dto.virtualFragments().stream()
                .map(virtualDto -> fromVirtualFragmentDto(virtualDto, mgr, imageBytesMap, fragmentCache))
                .collect(Collectors.toList());

        var taskHistory = dto.taskHistory().stream()
                .map(taskDto -> fromTaskEntryDto(taskDto, mgr, fragmentCache))
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toCollection(ArrayList::new));

        var parsedOutputFragment = dto.parsedOutput() != null
                ? fromTaskFragmentDto(dto.parsedOutput(), mgr, fragmentCache)
                : null;

        var actionFuture = (dto.actionSummary() != null)
                ? CompletableFuture.completedFuture(dto.actionSummary())
                : CompletableFuture.completedFuture("Restored Session");

        // Use the main constructor that accepts List<ContextFragment>
        return new Context(Context.newId(), // Or restore ID if ContextDto had one
                           mgr,
                           editableFragments,
                           readonlyFragments,
                           virtualFragments,
                           taskHistory,
                           parsedOutputFragment,
                           actionFuture);
    }

    private static ContextFragment.VirtualFragment fromVirtualFragmentDto(VirtualFragmentDto dto, IContextManager mgr, Map<Integer, byte[]> imageBytesMap, Map<Integer, ContextFragment> fragmentCache) {
        if (dto == null) {
            return null;
        }
        var cachedFragment = fragmentCache.get(dto.id());
        if (cachedFragment != null) {
            if (cachedFragment instanceof ContextFragment.VirtualFragment vf) {
                return vf;
            } else {
                // This should ideally not happen if IDs are unique across fragment types or cache is properly managed
                throw new IllegalStateException("Cached fragment for ID " + dto.id() + " is not a VirtualFragment: " + cachedFragment.getClass());
            }
        }

        ContextFragment.VirtualFragment newFragment = switch (dto) {
            case FrozenFragmentDto frozenDto -> {
                byte[] imageBytes = imageBytesMap.get(frozenDto.id());
                var files = frozenDto.files().stream()
                        .map(DtoMapper::fromProjectFileDto)
                        .collect(Collectors.toSet());
                var originalType = ContextFragment.FragmentType.valueOf(frozenDto.originalType());
                yield FrozenFragment.fromDto(
                    frozenDto.id(),
                    mgr,
                    originalType,
                    frozenDto.description(),
                    frozenDto.textContent(),
                    imageBytes,
                    frozenDto.isTextFragment(),
                    frozenDto.syntaxStyle(),
                    files,
                    frozenDto.originalClassName(),
                    frozenDto.meta()
                );
            }
            case SearchFragmentDto searchDto -> {
                var sources = searchDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                var messages = searchDto.messages().stream()
                        .map(DtoMapper::fromChatMessageDto)
                        .toList();
                yield new ContextFragment.SearchFragment(mgr, searchDto.query(), messages, sources);
            }
            case TaskFragmentDto taskDto ->
                // Ensure TaskFragments from the virtual list also use the caching mechanism
                fromTaskFragmentDto(taskDto, mgr, fragmentCache);
            case StringFragmentDto stringDto -> new ContextFragment.StringFragment(stringDto.id(), mgr, stringDto.text(), stringDto.description(), stringDto.syntaxStyle());
            case SkeletonFragmentDto skeletonDto -> new ContextFragment.SkeletonFragment(skeletonDto.id(), mgr, skeletonDto.targetIdentifiers(), ContextFragment.SummaryType.valueOf(skeletonDto.summaryType()));
            case UsageFragmentDto usageDto -> new ContextFragment.UsageFragment(usageDto.id(), mgr, usageDto.targetIdentifier());
            case PasteTextFragmentDto pasteTextDto -> new ContextFragment.PasteTextFragment(pasteTextDto.id(), mgr, pasteTextDto.text(), CompletableFuture.completedFuture(pasteTextDto.description()));
            case PasteImageFragmentDto pasteImageDto -> {
                Image image = base64ToImage(pasteImageDto.base64ImageData());
                yield new ContextFragment.PasteImageFragment(pasteImageDto.id(), mgr, image, CompletableFuture.completedFuture(pasteImageDto.description()));
            }
            case StacktraceFragmentDto stacktraceDto -> {
                var sources = stacktraceDto.sources().stream()
                        .map(DtoMapper::fromCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new ContextFragment.StacktraceFragment(stacktraceDto.id(), mgr, sources, stacktraceDto.original(), stacktraceDto.exception(), stacktraceDto.code());
            }
            case CallGraphFragmentDto callGraphDto -> new ContextFragment.CallGraphFragment(callGraphDto.id(), mgr, callGraphDto.methodName(), callGraphDto.depth(), callGraphDto.isCalleeGraph());
            case HistoryFragmentDto historyDto -> {
                var historyEntries = historyDto.history().stream()
                        .map(taskEntryDto -> fromTaskEntryDto(taskEntryDto, mgr, fragmentCache)) // Pass cache
                        .filter(java.util.Objects::nonNull)
                        .toList();
                yield new ContextFragment.HistoryFragment(historyDto.id(), mgr, historyEntries);
            }
        };
        fragmentCache.put(newFragment.id(), newFragment);
        return newFragment;
    }

    private static Object toReferencedFragmentDto(ContextFragment fragment) {
        if (fragment instanceof FrozenFragment ff) {
            try {
                var filesDto = ff.files().stream().map(DtoMapper::toProjectFileDto).collect(Collectors.toSet()); // Uses existing toProjectFileDto(ProjectFile)
                return new FrozenFragmentDto(
                    ff.id(),
                    ff.getType().name(), // Assuming FrozenFragment.getType() returns the original fragment's type
                    ff.description(),
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
        if (fragment instanceof ContextFragment.ProjectPathFragment pf) {
            return toProjectFileDto(pf);
        }
        if (fragment instanceof ContextFragment.GitFileFragment gf) {
            var file = gf.file();
            return new GitFileFragmentDto(gf.id(), file.getRoot().toString(), file.getRelPath().toString(), gf.revision(), gf.content());
        }
        if (fragment instanceof ContextFragment.ExternalPathFragment ef) {
            return new ExternalFileDto(ef.id(), ef.file().getPath().toString());
        }
        if (fragment instanceof ContextFragment.ImageFileFragment imf) {
            var file = imf.file();
            String absPath = file.absPath().toString();
            String fileName = file.getFileName().toLowerCase();
            String mediaType = null;
            if (fileName.endsWith(".png")) mediaType = "image/png";
            else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) mediaType = "image/jpeg";
            else if (fileName.endsWith(".gif")) mediaType = "image/gif";
            return new ImageFileDto(imf.id(), absPath, mediaType);
        }
        throw new IllegalArgumentException("Unsupported fragment type for referenced DTO conversion: " + fragment.getClass());
    }

    private static ContextFragment fromReferencedFragmentDto(ReferencedFragmentDto dto, IContextManager mgr, Map<Integer, byte[]> imageBytesMap, Map<Integer, ContextFragment> fragmentCache) {
        if (dto == null) {
            return null;
        }
        var cachedFragment = fragmentCache.get(dto.id());
        if (cachedFragment != null) {
            // No specific type check needed here as ReferencedFragmentDto is a broad category
            // and the cache stores ContextFragment.
            return cachedFragment;
        }

        ContextFragment newFragment = switch (dto) {
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
            case FrozenFragmentDto ffd -> {
                byte[] imageBytes = imageBytesMap.get(ffd.id());
                var files = ffd.files().stream().map(DtoMapper::fromProjectFileDto).collect(Collectors.toSet());
                var originalType = ContextFragment.FragmentType.valueOf(ffd.originalType());
                yield FrozenFragment.fromDto(
                    ffd.id(), mgr, originalType, ffd.description(), ffd.textContent(), imageBytes,
                    ffd.isTextFragment(), ffd.syntaxStyle(), files, ffd.originalClassName(), ffd.meta()
                );
            }
        };
        fragmentCache.put(newFragment.id(), newFragment); // newFragment.id() should match dto.id()
        return newFragment;
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
        // e.g., those embedded in FrozenFragment.sources/files. The ID might not be relevant here or
        // should come from a different source if these ProjectFiles were originally live fragments.
        // For now, using 0, assuming the primary fragment's ID is what's tracked.
        return new ProjectFileDto(0, pf.getRoot().toString(), pf.getRelPath().toString());
    }
    
    // toPathFragmentDto is no longer directly called by toDto, but might be used by other DTOs if they contain PathFragments directly.
    // For now, keeping it. If it becomes unused, it can be removed.
    private static PathFragmentDto toPathFragmentDto(ContextFragment.PathFragment fragment) { 
        return switch (fragment) {
            case ContextFragment.ProjectPathFragment projectFragment -> {
                var pf = projectFragment.file();
                yield new ProjectFileDto(projectFragment.id(), pf.getRoot().toString(), pf.getRelPath().toString());
            }
            case ContextFragment.ExternalPathFragment externalFragment -> {
                var ef = externalFragment.file();
                yield new ExternalFileDto(externalFragment.id(), ef.getPath().toString());
            }
            case ContextFragment.GitFileFragment gitFileFragment -> {
                var pf = gitFileFragment.file();
                yield new GitFileFragmentDto(
                    gitFileFragment.id(),
                    pf.getRoot().toString(),
                    pf.getRelPath().toString(),
                    gitFileFragment.revision(),
                    gitFileFragment.content()
                );
            }
            case ContextFragment.ImageFileFragment imageFileFragment -> {
                var file = imageFileFragment.file();
                String absPath = file.absPath().toString();
                String fileName = file.getFileName().toLowerCase();
                String mediaType = null;
                if (fileName.endsWith(".png")) {
                    mediaType = "image/png";
                } else if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
                    mediaType = "image/jpeg";
                } else if (fileName.endsWith(".gif")) {
                    mediaType = "image/gif";
                }
                yield new ImageFileDto(imageFileFragment.id(), absPath, mediaType);
            }
        };
    }
    
    private static VirtualFragmentDto toVirtualFragmentDto(ContextFragment.VirtualFragment fragment) {
        // Handle FrozenFragment first
        if (fragment instanceof FrozenFragment ff) {
            try {
                var filesDto = ff.files().stream()
                        .map(DtoMapper::toProjectFileDto)
                        .collect(Collectors.toSet());
                
                return new FrozenFragmentDto(
                    ff.id(),
                    ff.getType().name(),
                    ff.description(),
                    ff.isText() ? ff.text() : null,
                    ff.isText(),
                    ff.syntaxStyle(),
                    filesDto,
                    ff.originalClassName(),
                    ff.meta()
                );
            } catch (Exception e) {
                throw new RuntimeException("Failed to serialize FrozenFragment", e);
            }
        }
        
        return switch (fragment) {
            case ContextFragment.SearchFragment searchFragment -> {
                var sourcesDto = searchFragment.sources().stream() // No analyzer needed for pre-computed
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                var messagesDto = searchFragment.messages().stream()
                        .map(DtoMapper::toChatMessageDto)
                        .toList();
                yield new SearchFragmentDto(
                        searchFragment.id(),
                        searchFragment.description(), // sessionName
                        "", // explanation - no longer available as separate field
                        sourcesDto,
                        messagesDto
                );
            }
            case ContextFragment.TaskFragment taskFragment -> {
                var messagesDto = taskFragment.messages().stream()
                        .map(DtoMapper::toChatMessageDto)
                        .toList();
                yield new TaskFragmentDto(taskFragment.id(), messagesDto, taskFragment.description());
            }
            case ContextFragment.StringFragment stringFragment -> {
                yield new StringFragmentDto(
                        stringFragment.id(),
                        stringFragment.text(), // StringFragment is non-dynamic
                        stringFragment.description(),
                        stringFragment.syntaxStyle()
                );
            }
            case ContextFragment.SkeletonFragment skeletonFragment -> {
                yield new SkeletonFragmentDto(
                        skeletonFragment.id(),
                        skeletonFragment.getTargetIdentifiers(),
                        skeletonFragment.getSummaryType().name()
                );
            }
            case ContextFragment.UsageFragment usageFragment -> {
                yield new UsageFragmentDto(
                        usageFragment.id(),
                        usageFragment.targetIdentifier()
                );
            }
            case ContextFragment.PasteTextFragment pasteTextFragment -> {
                // Block for up to 10 seconds to get the completed description
                String description;
                try {
                    var future = pasteTextFragment.descriptionFuture;
                    String fullDescription = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    // Remove "Paste of " prefix to avoid duplication during deserialization
                    description = fullDescription.startsWith("Paste of ")
                        ? fullDescription.substring("Paste of ".length())
                        : fullDescription;
                } catch (java.util.concurrent.TimeoutException e) {
                    description = "(Paste description timed out)";
                } catch (Exception e) {
                    description = "(Error getting paste description)";
                }
                yield new PasteTextFragmentDto(pasteTextFragment.id(), pasteTextFragment.text(), description); // PasteTextFragment is non-dynamic
            }
            case ContextFragment.PasteImageFragment pasteImageFragment -> {
                // Block for up to 10 seconds to get the completed description
                String description;
                try {
                    var future = pasteImageFragment.descriptionFuture;
                    String fullDescription = future.get(10, java.util.concurrent.TimeUnit.SECONDS);
                    // Remove "Paste of " prefix to avoid duplication during deserialization
                    description = fullDescription.startsWith("Paste of ")
                        ? fullDescription.substring("Paste of ".length())
                        : fullDescription;
                } catch (java.util.concurrent.TimeoutException e) {
                    description = "(Paste description timed out)";
                } catch (Exception e) {
                    description = "(Error getting paste description)";
                }
                // Convert Image to base64
                String base64ImageData = imageToBase64(pasteImageFragment.image());
                yield new PasteImageFragmentDto(pasteImageFragment.id(), base64ImageData, description);
            }
            case ContextFragment.StacktraceFragment stacktraceFragment -> {
                var sourcesDto = stacktraceFragment.sources().stream() // No analyzer needed for pre-computed
                        .map(DtoMapper::toCodeUnitDto)
                        .collect(Collectors.toSet());
                yield new StacktraceFragmentDto(
                        stacktraceFragment.id(),
                        sourcesDto,
                        stacktraceFragment.text().split("\n\nStacktrace methods in this project:\n\n")[0], // original // StacktraceFragment is non-dynamic
                        stacktraceFragment.description().substring("stacktrace of ".length()), // exception
                        stacktraceFragment.text().contains("\n\nStacktrace methods in this project:\n\n")
                            ? stacktraceFragment.text().split("\n\nStacktrace methods in this project:\n\n")[1]
                            : "" // code
                );
            }
            case ContextFragment.CallGraphFragment callGraphFragment -> {
                yield new CallGraphFragmentDto(
                        callGraphFragment.id(),
                        callGraphFragment.getMethodName(),
                        callGraphFragment.getDepth(),
                        callGraphFragment.isCalleeGraph()
                );
            }
            case ContextFragment.HistoryFragment historyFragment -> {
                var historyDto = historyFragment.entries().stream()
                        .map(DtoMapper::toTaskEntryDto)
                        .toList();
                yield new HistoryFragmentDto(historyFragment.id(), historyDto);
            }
            default -> null; // Skip unsupported fragments
        };
    }
    
    private static TaskEntryDto toTaskEntryDto(TaskEntry entry) { // IContextManager not needed for serialization
        TaskFragmentDto logDto = null;
        if (entry.log() != null) {
            var messagesDto = entry.log().messages().stream()
                    .map(DtoMapper::toChatMessageDto)
                    .toList();
            logDto = new TaskFragmentDto(entry.log().id(), messagesDto, entry.log().description());
        }

        return new TaskEntryDto(entry.sequence(), logDto, entry.summary());
    }
    
    private static TaskFragmentDto toTaskFragmentDto(ContextFragment.TaskFragment fragment) {
        var messagesDto = fragment.messages().stream()
                .map(DtoMapper::toChatMessageDto)
                .toList();
        return new TaskFragmentDto(fragment.id(), messagesDto, fragment.description());
    }
    
    private static ContextFragment.TaskFragment fromTaskFragmentDto(TaskFragmentDto dto, IContextManager mgr, Map<Integer, ContextFragment> fragmentCache) {
        if (dto == null) {
            return null;
        }
        var cachedFragment = fragmentCache.get(dto.id());
        if (cachedFragment != null) {
            if (cachedFragment instanceof ContextFragment.TaskFragment tf) {
                return tf;
            } else {
                throw new IllegalStateException("Cached fragment for ID " + dto.id() + " is not a TaskFragment: " + cachedFragment.getClass());
            }
        }

        var messages = dto.messages().stream()
                .map(DtoMapper::fromChatMessageDto)
                .toList();
        var newFragment = new ContextFragment.TaskFragment(dto.id(), mgr, messages, dto.sessionName());
        fragmentCache.put(newFragment.id(), newFragment);
        return newFragment;
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
            case GitFileFragmentDto gfd -> fromProjectFileDto(new ProjectFileDto(0, gfd.repoRoot(), gfd.relPath()));
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
        ProjectFileDto pfd = new ProjectFileDto(0, pf.getRoot().toString(), pf.getRelPath().toString());
        return new CodeUnitDto(
                pfd,
                codeUnit.kind().name(),
                codeUnit.packageName(),
                codeUnit.shortName()
        );
    }

    private static TaskEntry fromTaskEntryDto(TaskEntryDto dto, IContextManager mgr, Map<Integer, ContextFragment> fragmentCache) {
        if (dto == null) {
            return null;
        }
        if (dto.log() != null) {
            // Use fromTaskFragmentDto to ensure caching for the TaskFragment within TaskEntry
            var taskFragment = fromTaskFragmentDto(dto.log(), mgr, fragmentCache);
            return new TaskEntry(dto.sequence(), taskFragment, null);
        } else if (dto.summary() != null) {
            return TaskEntry.fromCompressed(dto.sequence(), dto.summary());
        } else {
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

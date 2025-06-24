package io.github.jbellis.brokk.gui.mop.stream.flex;

import com.vladsch.flexmark.parser.InlineParser;
import com.vladsch.flexmark.parser.block.AbstractBlockParser;
import com.vladsch.flexmark.parser.block.BlockContinue;
import com.vladsch.flexmark.parser.block.BlockStart;
import com.vladsch.flexmark.parser.block.CustomBlockParserFactory;
import com.vladsch.flexmark.parser.block.MatchedBlockParser;
import com.vladsch.flexmark.parser.block.ParserState;
import com.vladsch.flexmark.parser.block.BlockParserFactory;
import com.vladsch.flexmark.util.ast.Block;
import com.vladsch.flexmark.util.data.DataHolder;
import com.vladsch.flexmark.util.sequence.BasedSequence;
import io.github.jbellis.brokk.analyzer.ProjectFile;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;

import static io.github.jbellis.brokk.gui.mop.stream.flex.EditBlockUtils.*;

/**
 * Parser for Brokk-specific EDIT BLOCK syntax.
 * Supports two formats:
 * 1. Fenced:    ```\n<filename>\n<<<<<<< SEARCH\n...\n=======\n...\n>>>>>>> REPLACE\n```
 * 2. Unfenced:  <<<<<<< SEARCH <filename>\n...\n======= <filename>\n...\n>>>>>>> REPLACE
 */
public class EditBlockParser extends AbstractBlockParser {
    private static final Logger logger = LogManager.getLogger(EditBlockParser.class);

    // Parser states
    private enum Phase { FENCE, FILENAME, SEARCH, DIVIDER, REPLACE, DONE }

    private final EditBlockNode block = new EditBlockNode();
    private final BasedSequence openingMarker;
    private final BasedSequence searchKeyword;

    private BasedSequence divider = BasedSequence.NULL;
    private BasedSequence replaceKeyword = BasedSequence.NULL;
    private BasedSequence closingMarker = BasedSequence.NULL;

    // Parser state
    private Phase phase = Phase.SEARCH;
    private StringBuilder searchContent = new StringBuilder();
    private StringBuilder replaceContent = new StringBuilder();
    private boolean parsingFenced = false;
    private boolean sawHeadLine = false;
    private @Nullable String currentFilename = null;

    EditBlockParser(BasedSequence openingMarker, BasedSequence searchKeyword, BasedSequence initialLine, boolean isFenced, @Nullable String filename) {
        this.openingMarker = openingMarker;
        this.searchKeyword = searchKeyword;
        this.parsingFenced = isFenced;
        this.currentFilename = filename;

        // Set the block's character sequence to ensure it has a real position in the document
        block.setChars(openingMarker);

        if (isFenced) {
            // If we have a filename from the fence line, go directly to SEARCH phase
            this.phase = (filename != null && !filename.isBlank()) ? Phase.SEARCH : Phase.FILENAME;
        } else if (!initialLine.isBlank()) {
            searchContent.append(initialLine);
        }

        // Set filename in the node immediately if available
        if (currentFilename != null && !currentFilename.isBlank()) {
            block.setFilename(currentFilename);
            logger.trace("Setting filename in constructor: {}", currentFilename);
        }
    }

    @Override
    public Block getBlock() {
        return block;
    }

    @Override
    public BlockContinue tryContinue(ParserState state) {
        var line = state.getLine();
        var lineStr = line.toString();

        var blockContinueResult = switch (phase) {
            case FILENAME -> {
                // First line after opening fence should be the filename
                var possibleFilename = stripFilename(lineStr);

                // If this is already a SEARCH line, don't treat it as filename
                if (HEAD.matcher(lineStr).matches()) {
                    sawHeadLine = true;
                    phase = Phase.SEARCH;

                    // Try to extract filename from the SEARCH line
                    var headMatcher = HEAD.matcher(lineStr);
                    if (headMatcher.matches() && headMatcher.group(1) != null) {
                        currentFilename = headMatcher.group(1).trim();
                        block.setFilename(currentFilename);
                        logger.trace("Set filename from HEAD line: {}", currentFilename);
                    }
                } else if (possibleFilename != null && !possibleFilename.isBlank()) {
                    // Got a valid filename
                    currentFilename = possibleFilename;
                    block.setFilename(currentFilename);
                    phase = Phase.SEARCH;
                    logger.trace("Set filename from line after fence: {}", currentFilename);
                }
                yield BlockContinue.atIndex(state.getIndex());
            }
            case SEARCH -> {
                // Check for HEAD marker (<<<<<<< SEARCH)
                if (!sawHeadLine && HEAD.matcher(lineStr).matches()) {
                    sawHeadLine = true;
                    // Extract filename from head line if none was provided before
                    if ((currentFilename == null || currentFilename.isBlank()) && lineStr.trim().length() > 14) {
                        var filenameMatcher = HEAD.matcher(lineStr);
                        if (filenameMatcher.matches() && filenameMatcher.group(1) != null) {
                            currentFilename = filenameMatcher.group(1).trim();
                            block.setFilename(currentFilename);
                        }
                    }
                    yield BlockContinue.atIndex(state.getIndex());
                }

                // Check for divider (=======)
                if (DIVIDER.matcher(lineStr).matches()) {
                    phase = Phase.REPLACE;
                    this.divider = line;
                    yield BlockContinue.atIndex(state.getIndex());
                }

                // Still in search part - only add content after we've seen the HEAD marker
                if (sawHeadLine || !parsingFenced) {
                    searchContent.append(searchContent.length() > 0 ? "\n" : "").append(line);
                }
                yield BlockContinue.atIndex(state.getIndex());
            }
            case REPLACE -> {
                // Check for end marker (>>>>>>> REPLACE)
                if (UPDATED.matcher(lineStr).matches()) {
                    phase = Phase.DONE;
                    this.closingMarker = line;
                    replaceKeyword = BasedSequence.of("REPLACE");

                    var beforeText = stripQuotedWrapping(searchContent.toString(), Objects.toString(currentFilename, ""));
                    var afterText = stripQuotedWrapping(replaceContent.toString(), Objects.toString(currentFilename, ""));

                    block.setSegments(openingMarker, searchKeyword,
                                      BasedSequence.of(beforeText),
                                      divider, replaceKeyword,
                                      BasedSequence.of(afterText),
                                      closingMarker);

                    if (!parsingFenced) {
                        yield BlockContinue.finished();
                    }
                    yield BlockContinue.atIndex(state.getIndex());
                }

                // Still in replace part
                replaceContent.append(replaceContent.length() > 0 ? "\n" : "").append(line);
                yield BlockContinue.atIndex(state.getIndex());
            }
            case DONE -> {
                // We're done parsing this block
                if (parsingFenced && lineStr.trim().equals(DEFAULT_FENCE.get(0))) {
                    // Consume the closing fence line and finish
                    yield BlockContinue.finished();
                }
                yield BlockContinue.atIndex(state.getIndex());
            }
            default -> BlockContinue.none();
        };
        return blockContinueResult;
    }

    @Override
    public void parseInlines(InlineParser inlineParser) {
        // No inline parsing needed for EDIT blocks
    }

    @Override
    public void closeBlock(ParserState state) {
        // If we haven't already set segments (e.g. because closing marker was not found yet), do it now
        if (block.getOpeningMarker() == BasedSequence.NULL && openingMarker != BasedSequence.NULL) {
            var beforeText = stripQuotedWrapping(searchContent.toString(), Objects.toString(currentFilename, ""));
            var afterText = stripQuotedWrapping(replaceContent.toString(), Objects.toString(currentFilename, ""));

            block.setSegments(openingMarker, searchKeyword,
                              BasedSequence.of(beforeText),
                              divider, // already defaults to BasedSequence.NULL
                              replaceKeyword, // already defaults to BasedSequence.NULL
                              BasedSequence.of(afterText),
                              closingMarker); // already defaults to BasedSequence.NULL
        }

        // Ensure filename is set
        if (currentFilename != null && !currentFilename.isBlank() && block.getFilename() == BasedSequence.NULL) {
            block.setFilename(currentFilename);
        }
    }

    /**
     * Factory for creating EditBlockParser.
     */
    public static class Factory implements CustomBlockParserFactory {
        @Override
        public BlockParserFactory apply(DataHolder options) {
            return new BlockParserFactory() {
                @Override
                public BlockStart tryStart(ParserState state, MatchedBlockParser matchedBlockParser) {
                    var line = state.getLine();
                    var lineStr = line.toString();
                    Set<ProjectFile> projectFiles = null; // Would need to be passed from elsewhere
                    String previousFilename = null; // Would need tracking

                    // We can't use sophisticated paragraph interruption in this version
                    // of flexmark, so we'll proceed with standard block handling

                    // Check if this line is a fence opening
                    var fenceMatcher = OPENING_FENCE.matcher(lineStr);
                    if (fenceMatcher.matches()) {
                        // logger.trace("Found potential fence opening: {}", line);

                        // Look-ahead: is there a <<<<<<< SEARCH before the closing fence?
                        var doc = line.getBaseSequence();          // whole document
                        int pos = line.getEndOffset();             // character after the opening ```
                        int linesChecked = 0;
                        boolean headFound = false;
                        boolean fenceClosed = false;

                        while (pos < doc.length() && !headFound && !fenceClosed && linesChecked < 25) {
                            int eol = doc.indexOf('\n', pos);
                            if (eol == -1) eol = doc.length();
                            var nextLine = doc.subSequence(pos, eol).toString();

                            if (OPENING_FENCE.matcher(nextLine).matches()) fenceClosed = true;
                            if (HEAD.matcher(nextLine).matches())          headFound = true;

                            pos = eol + 1;
                            linesChecked++;
                        }

                        if (!headFound) {
                            // No SEARCH marker before the closing fence -> not an edit block
                            return BlockStart.none();               // let Flexmark parse it as code-fence
                        }

                        // Extract token from fence line (may be language or filename)
                        var token = fenceMatcher.group(1);
                        String filenameFromFence = null;

                        // If token contains . or / treat it as a filename
                        if (token != null && looksLikePath(token)) {
                            filenameFromFence = token;
                            logger.trace("Found filename in fence line: {}", filenameFromFence);
                        }

                        // SEARCH found -> treat as fenced edit-block
                        // If filenameFromFence is non-null, we'll start in SEARCH phase
                        return BlockStart.of(new EditBlockParser(
                                        line, BasedSequence.NULL, BasedSequence.NULL, true, filenameFromFence))
                                .atIndex(state.getIndex());
                    }

                    // Check if this line matches the edit block start pattern (<<<<<<< SEARCH)
                    var headMatcher = HEAD.matcher(lineStr);
                    if (headMatcher.matches()) {
                        // Find proper boundaries for each component
                        int startOfSearch = lineStr.indexOf("SEARCH");
                        int endOfSearch = startOfSearch + "SEARCH".length();

                        var openingMarker = line.subSequence(0, lineStr.indexOf("SEARCH") - 1);
                        var searchKeyword = line.subSequence(startOfSearch, endOfSearch);
                        var searchText = BasedSequence.NULL; // Default to empty

                        // Only include content after "SEARCH" if it's not empty
                        if (endOfSearch < lineStr.length()) {
                            var afterSearch = lineStr.substring(endOfSearch).trim();
                            if (!afterSearch.isEmpty()) {
                                searchText = line.subSequence(endOfSearch + (lineStr.charAt(endOfSearch) == ' ' ? 1 : 0));
                            }
                        }

                        // Extract filename from the SEARCH line or guess
                        String filename = null;
                        var filenameText = headMatcher.group(1);

                        if (filenameText != null && !filenameText.isBlank()) {
                            filename = filenameText.trim();
                        } else {
                            // Try to find filename by checking context in the document

                            // Get the full document through the base sequence of the current line
                            var docChars = state.getLine().getBaseSequence();
                            var docContent = docChars.toString();
                            var allLines = docContent.split("\n", -1);

                            // Calculate proper document line index by counting newlines
                            int headStartChar = state.getLine().getStartOffset();
                            int lineIdx = 0;
                            for (int pos = 0; pos < headStartChar; pos++) {
                                if (docChars.charAt(pos) == '\n') lineIdx++;
                            }

                            filename = findFileNameNearby(
                                    allLines,
                                    lineIdx,
                                    projectFiles != null ? projectFiles : Set.of(),
                                    previousFilename);

                            // If we found a filename in the line directly before current line,
                            // we need to handle it differently in unfenced mode:
                            // It should NOT be included in the SEARCH content
                            if (filename != null && lineIdx > 0 &&
                                    filename.equals(stripFilename(allLines[lineIdx-1]))) {
                                // Skip this line by advancing the parser index
                                return BlockStart.of(new EditBlockParser(openingMarker, searchKeyword, searchText, false, filename))
                                        .atIndex(state.getIndex() + 1);
                            }
                        }

                        logger.trace("Found edit block start: {}, filename: {}", line, filename);

                        return BlockStart.of(new EditBlockParser(openingMarker, searchKeyword, searchText, false, filename))
                                .atIndex(state.getIndex());
                    }

                    return BlockStart.none();
                }
            };
        }

        @Override
        public boolean affectsGlobalScope() {
            return false;
        }

        @Override
        public Set<Class<?>> getAfterDependents() {
            return Collections.emptySet();
        }

        @Override
        public Set<Class<?>> getBeforeDependents() {
            return Collections.emptySet();
        }
    }
}

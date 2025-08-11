package io.github.jbellis.brokk.difftool.doc;

import javax.swing.event.DocumentEvent;
import org.jetbrains.annotations.Nullable;

public class JMDocumentEvent {
  private final AbstractBufferDocument document;
  @Nullable private DocumentEvent de;
  private int startLine;
  private int numberOfLines;

  public JMDocumentEvent(AbstractBufferDocument document) {
    this.document = document;
    // this.de remains null
  }

  public JMDocumentEvent(AbstractBufferDocument document, DocumentEvent de) {
    this(document);

    this.de = de;
  }

  public AbstractBufferDocument getDocument() {
    return document;
  }

  @Nullable
  public DocumentEvent getDocumentEvent() {
    return de;
  }

  public void setStartLine(int startLine) {
    this.startLine = startLine;
  }

  public int getStartLine() {
    return startLine;
  }

  public void setNumberOfLines(int numberOfLines) {
    this.numberOfLines = numberOfLines;
  }

  public int getNumberOfLines() {
    return numberOfLines;
  }
}

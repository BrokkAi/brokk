package io.github.jbellis.brokk.difftool.node;

import io.github.jbellis.brokk.difftool.doc.FileDocument;
import org.jetbrains.annotations.Nullable;
import java.io.File;
import java.lang.ref.SoftReference;

import static java.util.Objects.requireNonNull;

public class FileNode implements Comparable<FileNode>, BufferNode {
    private final String name;
    private final File file;
    private volatile SoftReference<@Nullable FileDocument> documentRef; // documentRef itself is @NonNull
    private final Object lock = new Object(); // For synchronizing document creation

    public FileNode(String name, File file) {
        this.name = name;
        this.file = file;
        this.documentRef = new SoftReference<>(null);
    }

    @Override
    public FileDocument getDocument() {
        FileDocument doc = documentRef.get();
        if (doc == null) {
            synchronized (lock) {
                doc = documentRef.get(); // Double-check locking
                if (doc == null) {
                    doc = new FileDocument(file, name);
                    documentRef = new SoftReference<>(doc);
                }
            }
        }
        // doc is guaranteed non-null here by the double-checked locking pattern.
        return requireNonNull(doc, "FileDocument should be initialized by double-checked locking pattern.");
    }

    public void unload() {
        synchronized (lock) {
            documentRef = new SoftReference<>(null);
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public long getSize() {
        return file.length();
    }

    @Override
    public int compareTo(FileNode other) {
        return name.compareTo(other.getName());
    }

    @Override
    public boolean equals(Object o) {
        return (o instanceof FileNode fileNode) && name.equals(fileNode.getName());
    }

    @Override
    public int hashCode() {
        return name.hashCode();
    }

    @Override
    public String toString() {
        return name;
    }
}

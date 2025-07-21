// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
/*
 * Modifications copyright 2025 Brokk, Inc. and made available under the GPLv3.
 *
 * The original file can be found at https://github.com/JetBrains/intellij-community/blob/8716ac75ffffbf446285cc33c325c5a98ddeb6c5/platform/util/src/com/intellij/openapi/util/LowMemoryWatcherManager.java
 */
package io.github.jbellis.brokk.util.containers;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.*;

/**
 * Implementation of the {@link Collection} interface which:
 * <ul>
 *   <li>Stores elements using weak semantics (see {@link java.lang.ref.WeakReference})</li>
 *   <li>Automatically reclaims storage for garbage collected elements</li>
 *   <li>Is thread safe</li>
 *   <li>Is NOT RandomAccess, because garbage collector can remove element at any time</li>
 *   <li>Does NOT support null elements</li>
 * </ul>
 * Please note that since weak references can be collected at any time, index-based methods (like get(index))
 * or size-based methods (like size()) are dangerous, misleading, error-inducing and are not supported.
 * Instead, please use {@link #add(T)} and {@link #iterator()}.
 */
public final class WeakList<T> extends AbstractCollection<T> {

    final List<WeakList.MyReference<T>> myList;
    private final ReferenceQueue<T> myQueue = new ReferenceQueue<>();
    private int myAlive;
    private int modCount;

    private static final class MyReference<T> extends WeakReference<T> {
        private final int index;

        private MyReference(int index, T referent, ReferenceQueue<? super T> queue) {
            super(referent, queue);
            this.index = index;
        }

        @Override
        public boolean equals(Object obj) {
            return this == obj || (obj != null && Objects.equals(get(), ((Reference<?>) obj).get()));
        }

        @Override
        public int hashCode() {
            return Objects.hash(get());
        }
    }

    public WeakList() {
        myList = new ArrayList<>();
    }

    public WeakList(int initialCapacity) {
        myList = new ArrayList<>(initialCapacity);
    }

    @SuppressWarnings("unchecked")
    public boolean processQueue() {
        boolean processed = false;
        MyReference<T> reference;
        //noinspection unchecked
        while ((reference = (MyReference<T>) myQueue.poll()) != null) {
            int index = reference.index;
            // list may have changed while the reference was dangling in queue
            if (index < myList.size() && reference.equals(myList.get(index))) {
                nullizeAt(index);
            }
            processed = true;
        }
        if (myAlive < myList.size() / 2) {
            reduceCapacity();
        }
        return processed;
    }

    private void nullizeAt(int index) {
        myList.set(index, null);
        myAlive--;
        // do not increment modCount here because every iterator().remove() usages will throw
    }

    private void reduceCapacity() {
        int toSaveAlive = 0;
        for (int i = 0; i < myList.size(); i++) {
            MyReference<T> reference = myList.get(i);
            if (reference == null) continue;
            T t = reference.get();
            if (t == null) {
                myAlive--;
                continue;
            }
            if (toSaveAlive != i) {
                myList.set(toSaveAlive, new MyReference<>(toSaveAlive, t, myQueue));
            }
            toSaveAlive++;
        }
        if (toSaveAlive != myList.size()) {
            myList.subList(toSaveAlive, myList.size()).clear();
            modCount++;
        }
        myAlive = toSaveAlive;
    }

    private void append(@NotNull T element) {
        myList.add(new MyReference<>(myList.size(), element, myQueue));
        myAlive++;
        modCount++;
    }

    @Override
    public boolean add(@NotNull T element) {
        synchronized (myList) {
            processQueue();
            append(element);
            return true;
        }
    }

    @Override
    public boolean addAll(@NotNull Collection<? extends T> c) {
        synchronized (myList) {
            processQueue();
            return super.addAll(c);
        }
    }

    public boolean addIfAbsent(@NotNull T element) {
        synchronized (myList) {
            processQueue();
            if (contains(element)) return false;
            append(element);
            return true;
        }
    }

    @Override
    public void clear() {
        synchronized (myList) {
            processQueue();
            myList.clear();
            myAlive = 0;
            modCount++;
        }
    }

    @Override
    public boolean contains(@NotNull Object o) {
        synchronized (myList) {
            return !isEmpty() && super.contains(o);
        }
    }

    @Override
    public boolean remove(@NotNull Object o) {
        synchronized (myList) {
            processQueue();
            for (int i = 0; i < myList.size(); i++) {
                Reference<T> ref = myList.get(i);
                T t = ref == null ? null : ref.get();
                if (t != null && t.equals(o)) {
                    nullizeAt(i);
                    modCount++;
                    return true;
                }
            }
            return false;
        }
    }

    @Override
    public boolean removeAll(@NotNull Collection<?> c) {
        synchronized (myList) {
            processQueue();
            return super.removeAll(c);
        }
    }

    @Override
    public boolean isEmpty() {
        synchronized (myList) {
            if (myList.isEmpty()) {
                return true;
            }

            for (MyReference<T> value : myList) {
                if (value != null && value.get() != null) {
                    return false;
                }
            }
            return true;
        }
    }


    private final class MyIterator implements Iterator<T> {
        private final int startModCount;
        private int curIndex;
        private @Nullable T curElement;

        private int nextIndex = -1;
        private @Nullable T nextElement;
        private boolean modified; // set this flag on modification and update modCount in the very end of iteration to avoid CME on each remove()

        private MyIterator() {
            startModCount = modCount;
            findNext();
        }

        private void findNext() {
            if (modCount != startModCount) throw new ConcurrentModificationException();
            curIndex = nextIndex;
            curElement = nextElement;
            nextElement = null;
            nextIndex = -1;
            for (int i = curIndex + 1; i < myList.size(); i++) {
                Reference<T> ref = myList.get(i);
                T t = ref == null ? null : ref.get();
                if (t != null) {
                    nextElement = t;
                    nextIndex = i;
                    break;
                }
            }
            if (nextIndex == -1 && modified) {
                modCount++;
            }
        }

        @Override
        public boolean hasNext() {
            return nextElement != null;
        }

        @Override
        @Nullable
        public T next() {
            if (!hasNext()) throw new NoSuchElementException();
            findNext();
            return curElement;
        }

        @Override
        public void remove() {
            if (curElement == null) throw new NoSuchElementException();
            int index = curIndex;
            nullizeAt(index);
            modified = true;
        }
    }

    @Override
    public @NotNull Iterator<@NotNull T> iterator() {
        final Iterator<T> iterator;
        synchronized (myList) {
            iterator = new MyIterator();
        }
        return new Iterator<>() {
            @Override
            public boolean hasNext() {
                synchronized (myList) {
                    return iterator.hasNext();
                }
            }

            @Override
            public T next() {
                synchronized (myList) {
                    return iterator.next();
                }
            }

            @Override
            public void remove() {
                synchronized (myList) {
                    iterator.remove();
                }
            }
        };
    }

    public @NotNull @Unmodifiable List<@NotNull T> toStrongList() {
        synchronized (myList) {
            if (myList.isEmpty()) {
                return new ArrayList<>();
            }

            List<T> result = new ArrayList<>(myList.size());
            for (MyReference<T> t : myList) {
                T o = t == null ? null : t.get();
                if (o != null) {
                    result.add(o);
                }
            }
            return result;
        }
    }

    /**
     * @deprecated Since weak references can be collected at any time,
     * this method considered dangerous, misleading, error-inducing and is not supported.
     * Instead, please use {@link #add(T)} and {@link #iterator()}.
     */
    @Override
    @Deprecated
    public int size() {
        throw new UnsupportedOperationException("index/size-based operations in UnsafeWeakList are not supported because they don't make sense in the presence of weak references. Use .iterator() (which retains its elements to avoid sudden GC) instead.");
    }

    public @NotNull @Unmodifiable List<@NotNull T> copyAndClear() {
        synchronized (myList) {
            List<T> result = toStrongList();
            clear();
            return result;
        }
    }
}

package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * A simple persistent (functional) singly-linked list.
 */
public final class FList<E> extends AbstractList<E> {
    /**
     * Shared empty-list sentinel
     */
    private static final FList<?> EMPTY_LIST = new FList<>(null, null, 0);

    /**
     * First element (may itself be {@code null})
     */
    private final @Nullable E myHead;

    /**
     * Remaining elements; {@code null} only for the empty list
     */
    private final @Nullable FList<E> myTail;

    /**
     * Cached size for O(1) access
     */
    private final int mySize;

    /**
     * Internal constructor
     */
    private FList(@Nullable E head, @Nullable FList<E> tail, int size) {
        myHead = head;
        myTail = tail;
        mySize = size;
    }

    @Override
    public @Nullable E get(int index) {
        if (index < 0 || index >= mySize) {
            throw new IndexOutOfBoundsException("index = " + index + ", size = " + mySize);
        }

        FList<E> current = this;
        while (index > 0) {
            current = requireNonNull(current.myTail);
            index--;
        }
        return current.myHead;
    }

    /**
     * Returns the first element (which may be {@code null}).
     */
    @Nullable
    public E getHead() {
        return myHead;
    }

    /**
     * Returns a new list with {@code elem} added in front.
     */
    public FList<E> prepend(@Nullable E elem) {
        return new FList<>(elem, this, mySize + 1);
    }

    /**
     * Returns a copy of this list with the first occurrence of {@code elem} removed.
     * Comparison respects {@code null} elements.
     */
    public FList<E> without(@Nullable E elem) {
        FList<E> front = emptyList();
        FList<E> current = this;

        while (!current.isEmpty()) {
            if (elem == null ? current.myHead == null : elem.equals(current.myHead)) {
                FList<E> result = requireNonNull(current.myTail);
                while (!front.isEmpty()) {
                    result = result.prepend(front.myHead);
                    front = requireNonNull(front.myTail);
                }
                return result;
            }

            front = front.prepend(current.myHead);
            current = requireNonNull(current.myTail);
        }
        return this;
    }

    @Override
    public Iterator<E> iterator() {
        return new Iterator<E>() {
            private FList<E> list = FList.this;

            @Override
            public boolean hasNext() {
                return list.size() > 0;
            }

            @Override
            public @Nullable E next() {
                if (list.size() == 0) {
                    throw new NoSuchElementException();
                }
                E res = list.myHead;
                list = requireNonNull(list.getTail());
                return res;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    /**
     * Returns the tail (which is {@code null} only for the empty list).
     */
    public @Nullable FList<E> getTail() {
        return myTail;
    }

    @Override
    public int size() {
        return mySize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof FList) {
            FList<?> list1 = this;
            FList<?> list2 = (FList<?>) o;
            if (mySize != list2.mySize) return false;

            while (list1 != null && !list1.isEmpty()
                    && list2 != null && !list2.isEmpty()) {
                if (!Objects.equals(list1.myHead, list2.myHead)) return false;
                list1 = list1.myTail;
                list2 = list2.myTail;
                // If both tails become null simultaneously, we've reached the end and all elements matched
                if (list1 == null && list2 == null) return true;
                // If sizes matched initially, tails should become null at the same time if elements are equal
                // If one becomes null before the other, something is wrong (shouldn't happen if sizes match)
                if (list1 == null || list2 == null) return false; // Should not happen if size check passed
            }
            return true;
        }
        return super.equals(o);
    }

    @Override
    public int hashCode() {
        int result = 1;
        FList<?> each = this;
        while (each != null) {
            result = result * 31 + (each.myHead != null ? each.myHead.hashCode() : 0);
            each = each.getTail();
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public static <E> FList<E> emptyList() {
        return (FList<E>) EMPTY_LIST;
    }

    /**
     * Convenience factory for a single-element list.
     */
    public static <E> FList<E> singleton(E elem) {
        return FList.<E>emptyList().prepend(elem);
    }
}

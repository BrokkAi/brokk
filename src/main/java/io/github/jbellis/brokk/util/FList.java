// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package io.github.jbellis.brokk.util;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.Iterator;
import java.util.NoSuchElementException;

public final class FList<E> extends AbstractList<E> {
    private static final FList<?> EMPTY_LIST = new FList<>(null, null, 0);
    @Nullable
    private final E myHead;
    private final FList<E> myTail;
    private final int mySize;

    private FList(@Nullable E head, FList<E> tail, int size) {
        myHead = head;
        myTail = tail;
        mySize = size;
    }

    @Override
    public E get(int index) {
        if (index < 0 || index >= mySize) {
            throw new IndexOutOfBoundsException("index = " + index + ", size = " + mySize);
        }

        FList<E> current = this;
        while (index > 0) {
            current = current.myTail;
            index--;
        }
        return current.myHead;
    }

    @Nullable
    public E getHead() {
        return myHead;
    }

    public FList<E> prepend(E elem) {
        return new FList<>(elem, this, mySize + 1);
    }

    public FList<E> without(@Nullable E elem) {
        FList<E> front = emptyList();

        FList<E> current = this;
        while (!current.isEmpty()) {
            if (elem == null ? current.myHead == null : current.myHead.equals(elem)) {
                FList<E> result = current.myTail;
                while (!front.isEmpty()) {
                    result = result.prepend(front.myHead);
                    front = front.myTail;
                }
                return result;
            }

            front = front.prepend(current.myHead);
            current = current.myTail;
        }
        return this;
    }

    @Override
    public @NotNull Iterator<E> iterator() {
        return new Iterator<E>() {
            private FList<E> list = FList.this;

            @Override
            public boolean hasNext() {
                return list.size() > 0;
            }

            @Override
            public E next() {
                if (list.size() == 0) throw new NoSuchElementException();

                E res = list.myHead;
                list = list.getTail();
                assert list != null;

                return res;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException();
            }
        };
    }

    public FList<E> getTail() {
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
                FList<?> list2 = (FList<?>)o;
                if (mySize != list2.mySize) return false;
                while (list1 != null && !list1.isEmpty() && list2 != null && !list2.isEmpty()) {
                    if (!java.util.Objects.equals(list1.myHead, list2.myHead)) return false;
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
        FList each = this;
        while (each != null) {
            result = result * 31 + (each.myHead != null ? each.myHead.hashCode() : 0);
            each = each.getTail();
        }
        return result;
    }

    public static <E> FList<E> emptyList() {
        //noinspection unchecked
        return (FList<E>)EMPTY_LIST;
    }

    public static <E> FList<E> singleton(@NotNull E elem) {
        return FList.<E>emptyList().prepend(elem);
    }
}

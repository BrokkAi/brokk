
package io.github.jbellis.brokk.diffTool.rangedifferencer;

import java.util.ArrayList;
import java.util.List;

/**
 * A custom iterator to iterate over a List of <code>RangeDifferences</code>.
 * It is used internally by the <code>RangeDifferencer</code>.
 */
class DifferencesIterator {

    List fRange;
    int fIndex;
    RangeDifference[] fArray;
    RangeDifference fDifference;

    /*
     * Creates a differences iterator on an array of <code>RangeDifference</code>s.
     */
    DifferencesIterator(RangeDifference[] differenceRanges) {

        fArray = differenceRanges;
        fIndex = 0;
        fRange = new ArrayList();
        if (fIndex < fArray.length)
            fDifference = fArray[fIndex++];
        else
            fDifference = null;
    }

    /*
     * Returns the number of RangeDifferences
     */
    int getCount() {
        return fRange.size();
    }

    /*
     * Appends the edit to its list and moves to the next <code>RangeDifference</code>.
     */
    void next() {
        fRange.add(fDifference);
        if (fDifference != null) {
            if (fIndex < fArray.length)
                fDifference = fArray[fIndex++];
            else
                fDifference = null;
        }
    }

    /*
     * Difference iterators are used in pairs.
     * This method returns the other iterator.
     */
    DifferencesIterator other(DifferencesIterator right, DifferencesIterator left) {
        if (this == right)
            return left;
        return right;
    }

    /*
     * Removes all <code>RangeDifference</code>s
     */
    void removeAll() {
        fRange.clear();
    }
}

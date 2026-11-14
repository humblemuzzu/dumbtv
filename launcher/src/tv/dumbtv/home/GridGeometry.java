package tv.dumbtv.home;

/** Distributes fixed-size grid columns within the section's existing padding. */
final class GridGeometry {
    private GridGeometry() { }

    /**
     * Keep the outer edges aligned with the title. A row with fewer tiles than columns keeps the
     * columns of a full row, so a short section lines up with the one above it.
     */
    static int columnLeft(int availableWidth, int childWidth, int gap, int columns, int column) {
        if (columns <= 1) return 0;
        int usedWidth = columns * childWidth + (columns - 1) * gap;
        int extra = Math.max(0, availableWidth - usedWidth);
        int spaces = columns - 1;
        return column * (childWidth + gap) + (extra * column + spaces / 2) / spaces;
    }
}

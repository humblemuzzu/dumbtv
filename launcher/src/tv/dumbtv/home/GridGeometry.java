package tv.dumbtv.home;

/** Distributes fixed-size grid columns within the section's existing padding. */
final class GridGeometry {
    private GridGeometry() { }

    /** Keep the outer edges aligned with the title; incomplete rows retain their columns. */
    static int columnLeft(int availableWidth, int childWidth, int gap, int columns, int count, int column) {
        int usedColumns = Math.min(columns, count);
        if (usedColumns <= 1) return 0;
        int usedWidth = usedColumns * childWidth + (usedColumns - 1) * gap;
        int extra = Math.max(0, availableWidth - usedWidth);
        int spaces = usedColumns - 1;
        return column * (childWidth + gap) + (extra * column + spaces / 2) / spaces;
    }
}

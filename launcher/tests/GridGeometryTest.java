package tv.dumbtv.home;

/** Run with javac/java; no Android runtime is needed. */
public final class GridGeometryTest {
    private static void equal(int expected, int actual) {
        if (expected != actual) throw new AssertionError("expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        // 1920 px display, 112 px title padding on each side, four 360 px tiles.
        equal(0, GridGeometry.columnLeft(1696, 360, 36, 4, 0));
        equal(445, GridGeometry.columnLeft(1696, 360, 36, 4, 1));
        equal(891, GridGeometry.columnLeft(1696, 360, 36, 4, 2));
        equal(1696, GridGeometry.columnLeft(1696, 360, 36, 4, 3) + 360);
        // A single column starts at the left edge.
        equal(0, GridGeometry.columnLeft(280, 360, 36, 1, 0));
        // When the columns fit exactly, keep the existing minimum spacing.
        equal(396, GridGeometry.columnLeft(1548, 360, 36, 4, 1));
        equal(1548, GridGeometry.columnLeft(1548, 360, 36, 4, 3) + 360);
        // Pixel remainders belong between columns and never add an outer gutter.
        equal(1697, GridGeometry.columnLeft(1697, 360, 36, 4, 3) + 360);
        // Other tile sizes use the same section edges.
        equal(1696, GridGeometry.columnLeft(1696, 288, 36, 5, 4) + 288);
        equal(1696, GridGeometry.columnLeft(1696, 448, 36, 3, 2) + 448);
        System.out.println("GridGeometryTest passed");
    }
}

package dev.mcmcp.util;

/**
 * Coordinate math utilities per PRD §7.3 and IMPLEMENTATION.md §11.3.
 * Uses floor division/modulus for negative coordinates.
 */
public final class CoordMath {

    public static int floorToBlock(double coord) {
        return (int) Math.floor(coord);
    }

    public static int floorDiv(int a, int b) {
        return Math.floorDiv(a, b);
    }

    public static int floorMod(int a, int b) {
        return Math.floorMod(a, b);
    }

    public static int blockToChunk(int blockCoord) {
        return floorDiv(blockCoord, 16);
    }

    public static int blockToInChunk(int blockCoord) {
        return floorMod(blockCoord, 16);
    }

    public static int chunkToRegion(int chunkCoord) {
        return floorDiv(chunkCoord, 32);
    }

    public static int chunkOrigin(int chunkCoord) {
        return chunkCoord * 16;
    }

    private CoordMath() {}
}

package dev.mcmcp.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CoordMathTest {

    @Test
    void floorToBlock() {
        assertEquals(10, CoordMath.floorToBlock(10.5));
        assertEquals(-1, CoordMath.floorToBlock(-0.5));
        assertEquals(-4, CoordMath.floorToBlock(-3.25));
    }

    @Test
    void blockToChunkPositive() {
        assertEquals(0, CoordMath.blockToChunk(10));
        assertEquals(1, CoordMath.blockToChunk(16));
        assertEquals(1, CoordMath.blockToChunk(31));
    }

    @Test
    void blockToChunkNegative() {
        assertEquals(-1, CoordMath.blockToChunk(-1));
        assertEquals(-1, CoordMath.blockToChunk(-16));
        assertEquals(-2, CoordMath.blockToChunk(-17));
    }

    @Test
    void blockToInChunk() {
        assertEquals(10, CoordMath.blockToInChunk(10));
        assertEquals(0, CoordMath.blockToInChunk(16));
        assertEquals(15, CoordMath.blockToInChunk(-1));
        assertEquals(0, CoordMath.blockToInChunk(-16));
        assertEquals(15, CoordMath.blockToInChunk(-17));
    }

    @Test
    void chunkOrigin() {
        assertEquals(0, CoordMath.chunkOrigin(0));
        assertEquals(16, CoordMath.chunkOrigin(1));
        assertEquals(-16, CoordMath.chunkOrigin(-1));
    }

    @Test
    void chunkToRegion() {
        assertEquals(0, CoordMath.chunkToRegion(0));
        assertEquals(0, CoordMath.chunkToRegion(31));
        assertEquals(1, CoordMath.chunkToRegion(32));
        assertEquals(-1, CoordMath.chunkToRegion(-1));
        assertEquals(-1, CoordMath.chunkToRegion(-32));
        assertEquals(-2, CoordMath.chunkToRegion(-33));
    }
}

package dev.anchormc.core;

/** 월드 이름 + 블록 좌표. */
public record Pos(String world, int x, int y, int z) {
    /** 면으로 맞닿은 여섯 방향. */
    public static final int[][] FACES = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    public Pos offset(int dx, int dy, int dz) {
        return new Pos(world, x + dx, y + dy, z + dz);
    }

    /** 블록 중심까지의 유클리드 거리. */
    public double distanceTo(double px, double py, double pz) {
        double dx = px - (x + 0.5), dy = py - (y + 0.5), dz = pz - (z + 0.5);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public int chunkX() {
        return Math.floorDiv(x, 16);
    }

    public int chunkZ() {
        return Math.floorDiv(z, 16);
    }
}

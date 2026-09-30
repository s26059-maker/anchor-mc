package dev.anchormc.core;

import java.util.Arrays;
import java.util.random.RandomGenerator;

/** 테스트용 작은 월드. 범위 밖은 "로드 안 됨"(고체 아님)이다. */
final class GridWorld implements BlockView {
    static final byte AIR = 0, STONE = 1, DEEPSLATE = 2, GLASS = 3, SAND = 4;

    final int size;
    final byte[] cells;

    GridWorld(int size) {
        this.size = size;
        this.cells = new byte[size * size * size];
    }

    static GridWorld solid(int size) {
        GridWorld w = new GridWorld(size);
        Arrays.fill(w.cells, STONE);
        return w;
    }

    /** 돌 바탕에 공기·유리·모래를 무작위로 뿌린다. */
    static GridWorld random(int size, RandomGenerator rng, double airFraction) {
        GridWorld w = solid(size);
        for (int i = 0; i < w.cells.length; i++) {
            double r = rng.nextDouble();
            if (r < airFraction) {
                w.cells[i] = AIR;
            } else if (r < airFraction * 1.2) {
                w.cells[i] = GLASS;
            } else if (r < airFraction * 1.4) {
                w.cells[i] = SAND;
            } else if (r < 0.5) {
                w.cells[i] = DEEPSLATE;
            }
        }
        return w;
    }

    boolean in(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < size && y < size && z < size;
    }

    int idx(int x, int y, int z) {
        return (x * size + y) * size + z;
    }

    byte get(int x, int y, int z) {
        return in(x, y, z) ? cells[idx(x, y, z)] : -1;
    }

    void set(int x, int y, int z, byte v) {
        cells[idx(x, y, z)] = v;
    }

    @Override
    public boolean isStableOpaque(int x, int y, int z) {
        byte c = get(x, y, z);
        return c == STONE || c == DEEPSLATE;
    }

    @Override
    public Host hostAt(int x, int y, int z) {
        byte c = get(x, y, z);
        return c == STONE ? Host.STONE : c == DEEPSLATE ? Host.DEEPSLATE : null;
    }

    int checksum() {
        return Arrays.hashCode(cells);
    }
}

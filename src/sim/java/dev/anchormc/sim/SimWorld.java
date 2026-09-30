package dev.anchormc.sim;

import dev.anchormc.core.BlockView;
import dev.anchormc.core.Host;
import dev.anchormc.core.Pos;

import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/** 서버 없이 도는 복셀 월드: 돌·심층암 바탕에 벌레 모양 동굴과 실제 다이아 광석. y는 -64..15. */
final class SimWorld implements BlockView {
    static final String NAME = "sim";
    static final int SX = 128, SY = 80, SZ = 128, Y0 = -64;
    static final byte AIR = 0, STONE = 1, ORE = 2;

    final byte[] cells;
    /** 처음 생성된 실제 광석 좌표(배열 인덱스 기준 x,y,z). */
    final List<int[]> ores;

    private SimWorld(byte[] cells, List<int[]> ores) {
        this.cells = cells;
        this.ores = ores;
    }

    static SimWorld generate(RandomGenerator rng) {
        byte[] c = new byte[SX * SY * SZ];
        java.util.Arrays.fill(c, STONE);
        // 벌레 동굴
        for (int w = 0; w < 28; w++) {
            double x = 10 + rng.nextInt(SX - 20), y = 6 + rng.nextInt(SY - 12), z = 10 + rng.nextInt(SZ - 20);
            double yaw = rng.nextDouble() * Math.PI * 2, pitch = 0;
            double radius = 1.2 + rng.nextDouble() * 1.3;
            for (int s = 0; s < 400; s++) {
                yaw += rng.nextGaussian() * 0.35;
                pitch = Math.max(-0.5, Math.min(0.5, pitch + rng.nextGaussian() * 0.15));
                x += Math.cos(yaw) * Math.cos(pitch);
                y += Math.sin(pitch);
                z += Math.sin(yaw) * Math.cos(pitch);
                if (x < 3 || z < 3 || y < 3 || x > SX - 4 || z > SZ - 4 || y > SY - 4) {
                    break;
                }
                int r = (int) Math.ceil(radius);
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        for (int dz = -r; dz <= r; dz++) {
                            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                                c[idx((int) x + dx, (int) y + dy, (int) z + dz)] = AIR;
                            }
                        }
                    }
                }
            }
        }
        // 실제 광석: 돌 속에 드문드문(작은 광맥)
        List<int[]> ores = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            int x = 2 + rng.nextInt(SX - 4), y = 2 + rng.nextInt(SY - 4), z = 2 + rng.nextInt(SZ - 4);
            int size = 1 + rng.nextInt(3);
            for (int k = 0; k < size; k++) {
                int ox = x + rng.nextInt(2), oy = y + rng.nextInt(2), oz = z + rng.nextInt(2);
                if (ox < SX && oy < SY && oz < SZ && c[idx(ox, oy, oz)] == STONE) {
                    c[idx(ox, oy, oz)] = ORE;
                    ores.add(new int[] {ox, oy, oz});
                }
            }
        }
        return new SimWorld(c, ores);
    }

    SimWorld copy() {
        return new SimWorld(cells.clone(), ores);
    }

    static int idx(int x, int y, int z) {
        return (x * SY + y) * SZ + z;
    }

    boolean in(int x, int y, int z) {
        return x >= 0 && y >= 0 && z >= 0 && x < SX && y < SY && z < SZ;
    }

    byte get(int x, int y, int z) {
        return in(x, y, z) ? cells[idx(x, y, z)] : STONE;
    }

    boolean solid(int x, int y, int z) {
        return get(x, y, z) != AIR;
    }

    void set(int x, int y, int z, byte v) {
        cells[idx(x, y, z)] = v;
    }

    /** 엔진이 쓰는 월드 좌표(y = 배열 y - 64)로 바꾼다. */
    static Pos pos(int x, int y, int z) {
        return new Pos(NAME, x, y + Y0, z);
    }

    // ---- BlockView: 엔진 좌표계 ----

    @Override
    public boolean isStableOpaque(int x, int y, int z) {
        int ay = y - Y0;
        return in(x, ay, z) && cells[idx(x, ay, z)] != AIR;
    }

    @Override
    public Host hostAt(int x, int y, int z) {
        int ay = y - Y0;
        if (!in(x, ay, z) || cells[idx(x, ay, z)] != STONE) {
            return null;
        }
        return y < 0 ? Host.DEEPSLATE : Host.STONE;
    }
}

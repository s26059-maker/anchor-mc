package dev.anchormc.sim;

import dev.anchormc.core.BlockView;
import dev.anchormc.core.Host;
import dev.anchormc.core.Pos;
import dev.anchormc.core.VeinShapes;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * 서버 없이 도는 복셀 월드: 돌·심층암 바탕에 벌레 모양 동굴과 바닐라식 다이아 광맥. y는 -64..15.
 * 256x256(16x16 청크). 광맥은 청크마다 바닐라 규칙(작은 광맥 7회 size 4/공기 노출 시 50% 제거, 묻힌 광맥 4회 size 8/노출 시 전부 제거,
 * 큰 광맥 1/9회 size 12/노출 시 70% 제거)의 근사로 만든다. 바닐라 그 자체는 아니다.
 */
final class SimWorld implements BlockView {
    static final String NAME = "sim";
    static final int SX = 256, SY = 80, SZ = 256, Y0 = -64;
    static final int CX = SX / 16, CZ = SZ / 16;
    static final byte AIR = 0, STONE = 1, ORE = 2;

    final byte[] cells;
    /** 처음 생성된 실제 광석 좌표(배열 인덱스 기준 x,y,z). */
    final List<int[]> ores;
    /** 청크(cx*CZ+cz)별 처음 생성된 광석. */
    final List<List<int[]>> oresByChunk;

    private SimWorld(byte[] cells, List<int[]> ores, List<List<int[]>> byChunk) {
        this.cells = cells;
        this.ores = ores;
        this.oresByChunk = byChunk;
    }

    static SimWorld generate(RandomGenerator rng) {
        byte[] c = new byte[SX * SY * SZ];
        java.util.Arrays.fill(c, STONE);
        // 벌레 동굴
        for (int w = 0; w < 112; w++) {
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
        // 광맥
        List<int[]> ores = new ArrayList<>();
        List<List<int[]>> byChunk = new ArrayList<>();
        for (int i = 0; i < CX * CZ; i++) {
            byChunk.add(new ArrayList<>());
        }
        for (int cx = 0; cx < CX; cx++) {
            for (int cz = 0; cz < CZ; cz++) {
                for (int i = 0; i < 7; i++) {
                    vein(c, ores, rng, cx, cz, 4, 0.5);
                }
                for (int i = 0; i < 4; i++) {
                    vein(c, ores, rng, cx, cz, 8, 1.0);
                }
                if (rng.nextDouble() < 1 / 9.0) {
                    vein(c, ores, rng, cx, cz, 12, 0.7);
                }
            }
        }
        for (int[] o : ores) {
            byChunk.get((o[0] / 16) * CZ + o[2] / 16).add(o);
        }
        return new SimWorld(c, ores, byChunk);
    }

    private static void vein(byte[] c, List<int[]> ores, RandomGenerator rng, int cx, int cz, int size, double discard) {
        int[][] cells = VeinShapes.blob(rng, size);
        // 높이: 바닐라 다이아는 y ∈ [-144, 16] 삼각 분포(최빈값 -64). 월드 바닥(-64) 아래로 떨어진 시도는 사라진다.
        double u = rng.nextDouble(), lo = -144, mode = -64, hi = 16;
        double fc = (mode - lo) / (hi - lo);
        double wy = u < fc ? lo + Math.sqrt(u * (hi - lo) * (mode - lo)) : hi - Math.sqrt((1 - u) * (hi - lo) * (hi - mode));
        if (wy < Y0) {
            return;
        }
        int oy = (int) Math.floor(wy) - Y0, ox = cx * 16 + rng.nextInt(16), oz = cz * 16 + rng.nextInt(16);
        for (int[] cell : cells) {
            int x = ox + cell[0], y = oy + cell[1], z = oz + cell[2];
            if (x < 0 || y < 0 || z < 0 || x >= SX || y >= SY || z >= SZ || c[idx(x, y, z)] != STONE) {
                continue;
            }
            if (discard > 0 && touchesAir(c, x, y, z) && rng.nextDouble() < discard) {
                continue;
            }
            c[idx(x, y, z)] = ORE;
            ores.add(new int[] {x, y, z});
        }
    }

    private static boolean touchesAir(byte[] c, int x, int y, int z) {
        int[][] f = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
        for (int[] d : f) {
            int nx = x + d[0], ny = y + d[1], nz = z + d[2];
            if (nx >= 0 && ny >= 0 && nz >= 0 && nx < SX && ny < SY && nz < SZ && c[idx(nx, ny, nz)] == AIR) {
                return true;
            }
        }
        return false;
    }

    /** 처음 생성된 진짜 광석을 26방향 연결 덩어리로 묶는다. 각 원소는 {크기, 최소 y(배열), y 두께}. */
    List<int[]> realClusters() {
        boolean[] seen = new boolean[cells.length];
        List<int[]> out = new ArrayList<>();
        for (int[] o : ores) {
            if (seen[idx(o[0], o[1], o[2])]) {
                continue;
            }
            ArrayDeque<int[]> q = new ArrayDeque<>();
            q.add(o);
            seen[idx(o[0], o[1], o[2])] = true;
            int n = 0, miny = Integer.MAX_VALUE, maxy = Integer.MIN_VALUE;
            while (!q.isEmpty()) {
                int[] p = q.poll();
                n++;
                miny = Math.min(miny, p[1]);
                maxy = Math.max(maxy, p[1]);
                for (int dx = -1; dx <= 1; dx++) {
                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dz = -1; dz <= 1; dz++) {
                            int nx = p[0] + dx, ny = p[1] + dy, nz = p[2] + dz;
                            if (in(nx, ny, nz) && cells[idx(nx, ny, nz)] == ORE && !seen[idx(nx, ny, nz)]) {
                                seen[idx(nx, ny, nz)] = true;
                                q.add(new int[] {nx, ny, nz});
                            }
                        }
                    }
                }
            }
            out.add(new int[] {n, miny, maxy - miny + 1});
        }
        return out;
    }

    SimWorld copy() {
        return new SimWorld(cells.clone(), ores, oresByChunk);
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

    @Override
    public boolean isDiamondOre(int x, int y, int z) {
        int ay = y - Y0;
        return in(x, ay, z) && cells[idx(x, ay, z)] == ORE;
    }

    @Override
    public boolean chunkLoaded(int cx, int cz) {
        return cx >= 0 && cz >= 0 && cx < CX && cz < CZ;
    }
}

package dev.anchormc.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 바닐라 광맥(OreFeature)의 덩어리 생성을 옮긴 것. 표본이 아직 없을 때의 기본 모양이고, 시뮬레이터의 진짜 광맥도 이걸로 만든다.
 * 결과 좌표는 최소 모서리가 (0,0,0)이 되게 옮겨 정렬한다.
 */
public final class VeinShapes {
    private VeinShapes() {
    }

    /** size는 바닐라의 광맥 크기 설정값(실제 블록 수는 이보다 작을 수 있다). */
    public static int[][] blob(RandomGenerator r, int size) {
        double f = r.nextDouble() * Math.PI;
        double g = size / 8.0;
        double ax = Math.sin(f) * g, az = Math.cos(f) * g;
        double y0 = r.nextInt(3) - 2, y1 = r.nextInt(3) - 2;
        Set<Long> seen = new HashSet<>();
        List<int[]> cells = new ArrayList<>();
        for (int k = 0; k < size; k++) {
            double t = (double) k / size;
            double cx = ax + (-ax - ax) * t, cy = y0 + (y1 - y0) * t, cz = az + (-az - az) * t;
            double d9 = r.nextDouble() * size / 16.0;
            double rad = ((Math.sin(Math.PI * t) + 1.0) * d9 + 1.0) / 2.0;
            for (int bx = (int) Math.floor(cx - rad); bx <= (int) Math.floor(cx + rad); bx++) {
                double nx = (bx + 0.5 - cx) / rad;
                if (nx * nx >= 1) {
                    continue;
                }
                for (int by = (int) Math.floor(cy - rad); by <= (int) Math.floor(cy + rad); by++) {
                    double ny = (by + 0.5 - cy) / rad;
                    if (nx * nx + ny * ny >= 1) {
                        continue;
                    }
                    for (int bz = (int) Math.floor(cz - rad); bz <= (int) Math.floor(cz + rad); bz++) {
                        double nz = (bz + 0.5 - cz) / rad;
                        if (nx * nx + ny * ny + nz * nz < 1 && seen.add(key(bx, by, bz))) {
                            cells.add(new int[] {bx, by, bz});
                        }
                    }
                }
            }
        }
        return normalize(cells);
    }

    private static long key(int x, int y, int z) {
        return ((long) (x + 512) << 40) | ((long) (y + 512) << 20) | (z + 512);
    }

    /** 최소 모서리를 원점으로 옮기고 (x,y,z) 사전순으로 정렬한다. */
    public static int[][] normalize(List<int[]> cells) {
        int mx = Integer.MAX_VALUE, my = Integer.MAX_VALUE, mz = Integer.MAX_VALUE;
        for (int[] c : cells) {
            mx = Math.min(mx, c[0]);
            my = Math.min(my, c[1]);
            mz = Math.min(mz, c[2]);
        }
        int[][] out = new int[cells.size()][];
        for (int i = 0; i < out.length; i++) {
            int[] c = cells.get(i);
            out[i] = new int[] {c[0] - mx, c[1] - my, c[2] - mz};
        }
        java.util.Arrays.sort(out, (a, b) -> a[0] != b[0] ? a[0] - b[0] : a[1] != b[1] ? a[1] - b[1] : a[2] - b[2]);
        return out;
    }

    /** 수평 대칭 8가지(y축 회전 4 × 좌우 반전 2). 두께(y)는 그대로 둔다: 진짜 광맥은 옆으로 퍼지기 때문이다. */
    public static int[][] horizontalSymmetry(int[][] cells, int sym) {
        List<int[]> out = new ArrayList<>(cells.length);
        for (int[] c : cells) {
            int x = c[0], z = c[2];
            if ((sym & 4) != 0) {
                x = -x;
            }
            for (int k = 0; k < (sym & 3); k++) {
                int nx = z, nz = -x;
                x = nx;
                z = nz;
            }
            out.add(new int[] {x, c[1], z});
        }
        return normalize(out);
    }
}

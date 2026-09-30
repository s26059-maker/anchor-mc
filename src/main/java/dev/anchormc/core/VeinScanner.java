package dev.anchormc.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 로드된 청크의 진짜 다이아 광석을 26방향 연결 덩어리로 묶어 VeinProfile 표본으로 넣는다(서버 월드는 읽기만). */
public final class VeinScanner {
    private record P(int x, int y, int z) {
    }

    private VeinScanner() {
    }

    /**
     * 그 청크가 최소 모서리(x,y,z 사전순 최소)를 소유하는 덩어리만 센다. 이웃 청크가 로드 안 돼 잘렸을 수 있는 덩어리는 뺀다.
     * 같은 청크를 두 번 스캔해도 한 번만 센다. 돌려주는 값은 이번에 넣은 표본 수.
     */
    public static int scanChunk(BlockView v, String world, int cx, int cz, int yMin, int yMax, VeinProfile profile) {
        if (!profile.markScanned(world + ":" + cx + ":" + cz)) {
            return 0;
        }
        Set<P> seen = new HashSet<>();
        int added = 0;
        for (int x = cx * 16; x < cx * 16 + 16; x++) {
            for (int z = cz * 16; z < cz * 16 + 16; z++) {
                for (int y = yMin; y <= yMax; y++) {
                    P start = new P(x, y, z);
                    if (seen.contains(start) || !v.isDiamondOre(x, y, z)) {
                        continue;
                    }
                    List<P> comp = new ArrayList<>();
                    ArrayDeque<P> q = new ArrayDeque<>();
                    q.add(start);
                    seen.add(start);
                    boolean truncated = false;
                    while (!q.isEmpty() && comp.size() <= VeinProfile.MAX_SAMPLE_SIZE) {
                        P p = q.poll();
                        comp.add(p);
                        for (int dx = -1; dx <= 1; dx++) {
                            for (int dy = -1; dy <= 1; dy++) {
                                for (int dz = -1; dz <= 1; dz++) {
                                    if (dx == 0 && dy == 0 && dz == 0) {
                                        continue;
                                    }
                                    int nx = p.x + dx, ny = p.y + dy, nz = p.z + dz;
                                    if (!v.chunkLoaded(Math.floorDiv(nx, 16), Math.floorDiv(nz, 16))) {
                                        truncated = true;
                                        continue;
                                    }
                                    P n = new P(nx, ny, nz);
                                    if (!seen.contains(n) && v.isDiamondOre(nx, ny, nz)) {
                                        seen.add(n);
                                        q.add(n);
                                    }
                                }
                            }
                        }
                    }
                    if (truncated || comp.size() > VeinProfile.MAX_SAMPLE_SIZE || !q.isEmpty()) {
                        continue;
                    }
                    P min = comp.get(0);
                    for (P p : comp) {
                        if (p.x < min.x || p.x == min.x && (p.y < min.y || p.y == min.y && p.z < min.z)) {
                            min = p;
                        }
                    }
                    if (Math.floorDiv(min.x, 16) != cx || Math.floorDiv(min.z, 16) != cz) {
                        continue;
                    }
                    List<int[]> cells = new ArrayList<>();
                    int minY = Integer.MAX_VALUE;
                    for (P p : comp) {
                        cells.add(new int[] {p.x, p.y, p.z});
                        minY = Math.min(minY, p.y);
                    }
                    profile.add(VeinShapes.normalize(cells), minY);
                    added++;
                }
            }
        }
        return added;
    }
}

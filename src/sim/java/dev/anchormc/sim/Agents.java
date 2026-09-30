package dev.anchormc.sim;

import dev.anchormc.AnchorCore;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 전략들. 정직: 무작위 동굴 탐색, 브랜치 마이닝. 엑스레이: 클라이언트가 아는 광석 쪽으로 직진(신뢰율 / 검증형 / 검증형 필터). */
final class Agents {
    private Agents() {
    }

    /** 무작위 동굴 탐색. 막다른 곳에서는 몇 칸 파고 나간다. 엑스레이 에이전트도 볼 광석이 없으면 이걸 쓴다. */
    static class Wander extends Agent {
        private int dir;
        private int digLeft;

        Wander(SimWorld w, AnchorCore c, UUID id, String name, RandomGenerator r, int[] start) {
            super(w, c, id, name, r, start);
            dir = r.nextInt(6);
        }

        @Override
        boolean step() {
            return wanderStep();
        }

        boolean wanderStep() {
            if (digLeft > 0) {
                int[] d = DIRS[dir];
                if (stepTo(x + d[0], y + d[1], z + d[2])) {
                    digLeft--;
                } else {
                    digLeft = 0;
                    dir = rng.nextInt(6);
                }
                return true;
            }
            List<Integer> free = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                int[] d = DIRS[i];
                if (!world.solid(x + d[0], y + d[1], z + d[2]) && world.in(x + d[0], y + d[1], z + d[2])) {
                    free.add(i);
                }
            }
            if (free.isEmpty()) {
                dir = rng.nextInt(6);
                digLeft = 1 + rng.nextInt(4);
                return true;
            }
            dir = free.contains(dir) && rng.nextDouble() < 0.7 ? dir : free.get(rng.nextInt(free.size()));
            int[] d = DIRS[dir];
            return stepTo(x + d[0], y + d[1], z + d[2]);
        }
    }

    /** 브랜치 마이닝: y=-58 층에서 긴 본갱도를 파고 3칸마다 좌우로 24칸 가지를 판다. */
    static final class Branch extends Agent {
        private final ArrayDeque<int[]> moves = new ArrayDeque<>();

        Branch(SimWorld w, AnchorCore c, UUID id, String name, RandomGenerator r) {
            super(w, c, id, name, r, new int[] {6 + r.nextInt(10), -58 - SimWorld.Y0, 30 + r.nextInt(30)});
            int side = 1;
            for (int i = 0; i < 100; i++) {
                moves.add(new int[] {1, 0, 0});
                if (i % 3 == 2) {
                    side = -side;
                    for (int k = 0; k < 24; k++) {
                        moves.add(new int[] {0, 0, side});
                    }
                    for (int k = 0; k < 24; k++) {
                        moves.add(new int[] {0, 0, -side});
                    }
                }
            }
        }

        @Override
        boolean step() {
            int[] m = moves.poll();
            return m != null && stepTo(x + m[0], y + m[1], z + m[2]);
        }
    }

    /**
     * 검증형 엑스레이가 미끼를 거르려고 쓰는 규칙. 모두 클라이언트가 실제로 볼 수 있는 정보만 쓴다.
     * timing: 그 광석이 속한 청크가 로드된 뒤 tolerance틱보다 늦게 나타난 광석은 무시.
     * shape: 26방향으로 이어진 광석 덩어리의 크기가 [minSize, maxSize] 밖이면(단일 블록 포함) 무시.
     * packet: 청크 데이터가 아니라 블록 갱신 패킷으로 온 광석은 무시(가장 강한 필터).
     */
    record Filters(boolean timing, long tolerance, boolean shape, int minSize, int maxSize, boolean packet) {
        static final Filters NONE = new Filters(false, 0, false, 2, 12, false);

        static Filters onlyTiming() {
            return new Filters(true, 0, false, 2, 12, false);
        }

        static Filters onlyShape() {
            return new Filters(false, 0, true, 2, 12, false);
        }

        static Filters timingAndShape() {
            return new Filters(true, 0, true, 2, 12, false);
        }

        static Filters all() {
            return new Filters(true, 0, true, 2, 12, true);
        }

        static Filters packetOnly() {
            return new Filters(false, 0, false, 2, 12, true);
        }
    }

    /**
     * 엑스레이. 클라이언트가 아는 광석(진짜·화면에 뜬 미끼) 중 시야 32블록 안에서 가장 가까운 것으로 직진한다.
     * trust: 미끼 뭉치를 (처음 볼 때) 믿을 확률. 진짜 광석은 항상 믿는다.
     * verifierK: 0이면 검증 없음. k번 속으면(가는 도중 미끼가 사라짐) 그 뒤로는 "가까운 곳(28 미만)에서 갑자기 나타난 광석"을 전부 무시한다.
     * filters: 위 Filters. 처음부터 켜져 있다(속아 보지 않아도 적용).
     */
    static final class Xray extends Wander {
        static final double VIEW = 32, POP_LIMIT = 28;

        private final double trust;
        private final int verifierK;
        private final Filters filters;
        private Integer targetKey;
        private boolean targetReal;
        private int fooled;
        private Map<Integer, Integer> comps;
        private int compsVersion = -1;

        Xray(SimWorld w, AnchorCore c, UUID id, String name, RandomGenerator r, int[] start, double trust, int verifierK, Filters filters) {
            super(w, c, id, name, r, start);
            this.trust = trust;
            this.verifierK = verifierK;
            this.filters = filters;
        }

        @Override
        boolean tracksClient() {
            return true;
        }

        @Override
        boolean rollTrust() {
            return rng.nextDouble() < trust;
        }

        @Override
        void onUnload(int cx, int cz) {
            if (targetKey != null) {
                ClientOre t = known.get(targetKey);
                if (t == null && targetTileChunk(cx, cz)) {
                    targetKey = null; // 청크가 사라진 것이지 속은 것이 아니다
                }
            }
        }

        private int[] targetXyz;

        private boolean targetTileChunk(int cx, int cz) {
            return targetXyz != null && (targetXyz[0] >> 4) == cx && (targetXyz[2] >> 4) == cz;
        }

        private boolean learned() {
            return verifierK > 0 && fooled >= verifierK;
        }

        /** 클라이언트가 아는 광석의 26방향 연결 덩어리 크기. */
        private Map<Integer, Integer> components() {
            if (comps != null && compsVersion == version) {
                return comps;
            }
            Map<Integer, Integer> out = new HashMap<>();
            Map<Integer, Integer> size = new HashMap<>();
            for (Map.Entry<Integer, ClientOre> e : known.entrySet()) {
                if (out.containsKey(e.getKey())) {
                    continue;
                }
                List<Integer> members = new ArrayList<>();
                ArrayDeque<ClientOre> q = new ArrayDeque<>();
                q.add(e.getValue());
                out.put(e.getKey(), -1);
                while (!q.isEmpty()) {
                    ClientOre o = q.poll();
                    members.add(SimWorld.idx(o.x, o.y, o.z));
                    for (int dx = -1; dx <= 1; dx++) {
                        for (int dy = -1; dy <= 1; dy++) {
                            for (int dz = -1; dz <= 1; dz++) {
                                if (dx == 0 && dy == 0 && dz == 0 || !world.in(o.x + dx, o.y + dy, o.z + dz)) {
                                    continue;
                                }
                                int k = SimWorld.idx(o.x + dx, o.y + dy, o.z + dz);
                                ClientOre n = known.get(k);
                                if (n != null && !out.containsKey(k)) {
                                    out.put(k, -1);
                                    q.add(n);
                                }
                            }
                        }
                    }
                }
                for (int m : members) {
                    out.put(m, members.size());
                }
            }
            comps = out;
            compsVersion = version;
            size.clear();
            return comps;
        }

        private boolean passesFilters(ClientOre o) {
            if (filters.timing() && o.arrival - o.chunkLoad > filters.tolerance()) {
                return false;
            }
            if (filters.packet() && !o.viaChunk) {
                return false;
            }
            if (filters.shape()) {
                int n = components().getOrDefault(SimWorld.idx(o.x, o.y, o.z), 1);
                if (n < filters.minSize() || n > filters.maxSize()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        boolean step() {
            if (targetKey != null && !known.containsKey(targetKey)) {
                if (!targetReal) {
                    fooled++; // 가는 도중 미끼가 사라졌다(또는 캤더니 돌이었다)
                }
                targetKey = null;
                targetXyz = null;
            }
            if (targetKey == null) {
                chooseTarget();
            }
            if (targetKey == null) {
                return wanderStep();
            }
            int tx = targetXyz[0], ty = targetXyz[1], tz = targetXyz[2];
            int dx = tx - x, dy = ty - y, dz = tz - z;
            int ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
            int nx = x, ny = y, nz = z;
            if (ax >= ay && ax >= az && ax > 0) {
                nx += Integer.signum(dx);
            } else if (ay >= az && ay > 0) {
                ny += Integer.signum(dy);
            } else if (az > 0) {
                nz += Integer.signum(dz);
            }
            if (nx == x && ny == y && nz == z) {
                targetKey = null;
                targetXyz = null;
                return false;
            }
            if (!stepTo(nx, ny, nz)) {
                targetKey = null;
                targetXyz = null;
                return false;
            }
            return true;
        }

        private void chooseTarget() {
            double best = Double.MAX_VALUE;
            ClientOre pick = null;
            for (ClientOre o : known.values()) {
                double d = dist(o.x, o.y, o.z);
                if (d > VIEW || d >= best) {
                    continue;
                }
                if (!o.real && !o.trusted) {
                    continue;
                }
                if (learned() && o.popDist < POP_LIMIT) {
                    continue;
                }
                if (!passesFilters(o)) {
                    continue;
                }
                best = d;
                pick = o;
            }
            if (pick != null) {
                targetKey = SimWorld.idx(pick.x, pick.y, pick.z);
                targetReal = pick.real;
                targetXyz = new int[] {pick.x, pick.y, pick.z};
            }
        }
    }
}

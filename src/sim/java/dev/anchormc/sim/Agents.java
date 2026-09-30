package dev.anchormc.sim;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.Pos;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 전략들. 정직: 무작위 동굴 탐색, 브랜치 마이닝. 엑스레이: 보이는 광석 쪽으로 직진(신뢰율 / 검증형). */
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
            // 시작점이 이미 돌이면 캐고 들어간다(stepTo가 아니라 제자리 표시는 필요 없다: 첫 이동에서 판다).
        }

        @Override
        boolean step() {
            int[] m = moves.poll();
            return m != null && stepTo(x + m[0], y + m[1], z + m[2]);
        }
    }

    /**
     * 엑스레이. 시야 32블록 안의 진짜 광석과 화면에 뜬 가짜 광석 중 가장 가까운 것으로 직진한다.
     * trust: 가짜 광석(을 처음 볼 때) 믿을 확률. 진짜 광석은 항상 믿는다.
     * verifierK: 0이면 검증 없음. k번 속으면(가는 도중 광석이 사라짐) 그 뒤로는 "시야 가장자리(28 미만)가 아니라
     * 가까운 곳에서 갑자기 나타난 광석"을 전부 무시한다(진짜 광석은 청크가 로드될 때 가장자리에서 보이기 때문).
     */
    static final class Xray extends Wander {
        static final double VIEW = 32, POP_LIMIT = 28;

        private final double trust;
        private final int verifierK;
        private final Map<Pos, Boolean> trustRoll = new HashMap<>();
        private final Map<Integer, Double> realFirstSeen = new HashMap<>();
        private Pos decoyTarget;
        private int[] realTarget;
        private int fooled;
        private long lastScan = -1;

        Xray(SimWorld w, AnchorCore c, UUID id, String name, RandomGenerator r, int[] start, double trust, int verifierK) {
            super(w, c, id, name, r, start);
            this.trust = trust;
            this.verifierK = verifierK;
        }

        private boolean learned() {
            return verifierK > 0 && fooled >= verifierK;
        }

        private double dist(int px, int py, int pz) {
            double dx = px - x, dy = py - y, dz = pz - z;
            return Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        private void scanRealOres() {
            if (lastScan >= 0 && tick - lastScan < 20) {
                return;
            }
            boolean first = lastScan < 0;
            lastScan = tick;
            for (int i = 0; i < world.ores.size(); i++) {
                int[] o = world.ores.get(i);
                if (realFirstSeen.containsKey(i)) {
                    continue;
                }
                double d = dist(o[0], o[1], o[2]);
                if (d <= VIEW) {
                    realFirstSeen.put(i, first ? VIEW : d); // 처음부터 로드돼 있던 것은 가장자리로 친다
                }
            }
        }

        @Override
        boolean step() {
            scanRealOres();
            if (decoyTarget != null && !shown.contains(decoyTarget)) {
                fooled++; // 가는 도중 사라졌다
                decoyTarget = null;
            }
            if (realTarget != null && world.get(realTarget[0], realTarget[1], realTarget[2]) != SimWorld.ORE) {
                realTarget = null;
            }
            if (decoyTarget == null && realTarget == null) {
                chooseTarget();
            }
            if (decoyTarget == null && realTarget == null) {
                return wanderStep();
            }
            int tx, ty, tz;
            if (decoyTarget != null) {
                tx = decoyTarget.x();
                ty = decoyTarget.y() - SimWorld.Y0;
                tz = decoyTarget.z();
            } else {
                tx = realTarget[0];
                ty = realTarget[1];
                tz = realTarget[2];
            }
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
                decoyTarget = null;
                realTarget = null;
                return false;
            }
            if (!stepTo(nx, ny, nz)) {
                decoyTarget = null;
                realTarget = null;
                return false;
            }
            return true;
        }

        private void chooseTarget() {
            double best = Double.MAX_VALUE;
            Pos bestDecoy = null;
            int[] bestReal = null;
            for (Map.Entry<Integer, Double> e : realFirstSeen.entrySet()) {
                int[] o = world.ores.get(e.getKey());
                if (world.get(o[0], o[1], o[2]) != SimWorld.ORE || (learned() && e.getValue() < POP_LIMIT)) {
                    continue;
                }
                double d = dist(o[0], o[1], o[2]);
                if (d <= VIEW && d < best) {
                    best = d;
                    bestReal = o;
                    bestDecoy = null;
                }
            }
            for (Pos p : shown) {
                double pd = popDistance.getOrDefault(p, 0.0);
                if (learned() && pd < POP_LIMIT) {
                    continue;
                }
                if (!trustRoll.computeIfAbsent(p, k -> rng.nextDouble() < trust)) {
                    continue;
                }
                double d = dist(p.x(), p.y() - SimWorld.Y0, p.z());
                if (d < best) {
                    best = d;
                    bestDecoy = p;
                    bestReal = null;
                }
            }
            decoyTarget = bestDecoy;
            realTarget = bestReal;
        }
    }
}

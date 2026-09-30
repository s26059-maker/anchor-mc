package dev.anchormc.sim;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.PlayerState;
import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 채굴 에이전트. 시간은 틱(20틱=1초). 이동·채굴에 비용을 붙이고 1초마다 서버 엔진을 돌린다.
 * 서버가 청크를 보내는 것도 흉내 낸다: 이동해서 청크 칸이 바뀌면 반경 R청크 안의 새 청크를 가까운 순으로 "전송"하고
 * (엔진 onChunkSent 호출), 멀어진 청크는 버린다(dropChunkFor). 클라이언트가 아는 광석(진짜·미끼)은 known에 시각·경로와 함께 쌓인다.
 */
abstract class Agent {
    static final int DIG_TICKS = 9;
    static final int MOVE_TICKS = 5;
    static final int R = 4;
    static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    /** 클라이언트 화면에 있는 광석 블록 하나. */
    static final class ClientOre {
        final int x, y, z;
        final boolean real;
        /** 이 블록이 클라이언트에 나타난 틱, 그 블록이 속한 청크가 로드된 틱. */
        final long arrival, chunkLoad;
        /** 청크 데이터에 들어 있었나(진짜) / 블록 갱신 패킷으로 왔나(미끼: false). */
        final boolean viaChunk;
        /** 나타났을 때의 에이전트와의 거리(첫 접속 때 받은 것은 먼 것으로 친다). */
        final double popDist;
        final boolean trusted;
        /** 이 청크를 다시 받았을 때, 처음 받았을 때의 데이터에 없던 광석(클라이언트가 청크를 캐시하면 알 수 있다). */
        final boolean changed;

        ClientOre(int x, int y, int z, boolean real, long arrival, long chunkLoad, boolean viaChunk, double popDist, boolean trusted,
                  boolean changed) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.real = real;
            this.arrival = arrival;
            this.chunkLoad = chunkLoad;
            this.viaChunk = viaChunk;
            this.popDist = popDist;
            this.trusted = trusted;
            this.changed = changed;
        }
    }

    final SimWorld world;
    final AnchorCore core;
    final UUID id;
    final String name;
    final RandomGenerator rng;

    int x, y, z;
    long tick;
    long engineTick;
    long nextEngineTick = 20;
    int oresMined;
    /** 미끼가 나타난 시각에 더할 지연(틱). 전송이 청크 이벤트보다 늦게 나가는 경우를 흉내 낸다. */
    long showDelay;
    /** true면 미끼가 청크 데이터가 아니라 별도 블록 갱신 패킷으로 온다(1.1단계 방식, 대조군). false면 청크 데이터에 들어 있다. */
    boolean legacy;

    // ---- 증거 추적: 규칙별 확정 시각(틱, 없으면 -1)과 최대 log10E ----
    static final double CONFIRM = 9, GUARD = 3;
    final long[] crossTick = {-1, -1, -1, -1};
    double maxMix, maxPair, maxFirst;
    boolean stopOnAll = true;

    // ---- 클라이언트 ----
    final Map<Integer, ClientOre> known = new HashMap<>();
    final Map<Integer, Long> loaded = new HashMap<>();
    int version;
    private int curCx = Integer.MIN_VALUE, curCz;
    private boolean firstBatch = true;
    /** 클라이언트가 캐시한 청크별 처음 받은 광석(청크를 버려도 남는다). */
    private final Map<Integer, java.util.Set<Integer>> firstOres = new HashMap<>();
    private final java.util.Set<Integer> visited = new java.util.HashSet<>();
    boolean currentRevisit;
    int revisits;

    Agent(SimWorld world, AnchorCore core, UUID id, String name, RandomGenerator rng, int[] start) {
        this.world = world;
        this.core = core;
        this.id = id;
        this.name = name;
        this.rng = rng;
        this.x = start[0];
        this.y = start[1];
        this.z = start[2];
    }

    /** 클라이언트가 아는 광석을 쓰는 에이전트인가(엑스레이). 아니면 known을 안 쌓는다. */
    boolean tracksClient() {
        return false;
    }

    /** 미끼 뭉치 하나를 (신뢰할지) 정하는 굴림. */
    boolean rollTrust() {
        return true;
    }

    private PlayerState state() {
        return new PlayerState(id, name, SimWorld.NAME, x + 0.5, y + SimWorld.Y0 + 0.5, z + 0.5, true);
    }

    double dist(int px, int py, int pz) {
        double dx = px - x, dy = py - y, dz = pz - z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ---- 서버가 보낸 것을 받는다 ----

    void receiveDecoys(List<Voxel> vs) {
        if (!tracksClient()) {
            return;
        }
        boolean trusted = rollTrust();
        for (Voxel v : vs) {
            int ax = v.pos().x(), ay = v.pos().y() - SimWorld.Y0, az = v.pos().z();
            int ck = (ax >> 4) * SimWorld.CZ + (az >> 4);
            Long lt = loaded.get(ck);
            if (lt == null) {
                continue; // 클라이언트가 그 청크를 갖고 있지 않으면 무시된다
            }
            int idx = SimWorld.idx(ax, ay, az);
            boolean changed = noteFirst(ck, idx);
            // 청크 데이터에 들어 있으면 도착 시각이 청크와 같고 경로도 청크 데이터다. 아니면 별도 패킷(지연 가능).
            known.put(idx, new ClientOre(ax, ay, az, false, legacy ? tick + showDelay : tick, lt, !legacy,
                    firstBatch ? 99 : dist(ax, ay, az), trusted, changed));
        }
        version++;
    }

    /** 이 청크의 첫 방문이면 기록하고 false, 재방문이면 처음 데이터에 없던 것인지 돌려준다. */
    private boolean noteFirst(int chunkKey, int idx) {
        java.util.Set<Integer> first = firstOres.computeIfAbsent(chunkKey, k -> new java.util.HashSet<>());
        if (!currentRevisit) {
            first.add(idx);
            return false;
        }
        return !first.contains(idx);
    }

    void retract(List<Pos> ps) {
        if (!tracksClient()) {
            return;
        }
        for (Pos p : ps) {
            ClientOre o = known.get(SimWorld.idx(p.x(), p.y() - SimWorld.Y0, p.z()));
            if (o != null && !o.real) {
                known.remove(SimWorld.idx(p.x(), p.y() - SimWorld.Y0, p.z()));
            }
        }
        version++;
    }

    /** 이동해서 청크 칸이 바뀌었으면 새 청크를 받고 먼 청크를 버린다. */
    void updateChunks() {
        int pcx = x >> 4, pcz = z >> 4;
        if (pcx == curCx && pcz == curCz) {
            return;
        }
        curCx = pcx;
        curCz = pcz;
        List<int[]> drop = new ArrayList<>();
        for (int key : loaded.keySet()) {
            int cx = key / SimWorld.CZ, cz = key % SimWorld.CZ;
            if (Math.max(Math.abs(cx - pcx), Math.abs(cz - pcz)) > R) {
                drop.add(new int[] {cx, cz});
            }
        }
        for (int[] c : drop) {
            unload(c[0], c[1]);
        }
        List<int[]> add = new ArrayList<>();
        for (int cx = Math.max(0, pcx - R); cx <= Math.min(SimWorld.CX - 1, pcx + R); cx++) {
            for (int cz = Math.max(0, pcz - R); cz <= Math.min(SimWorld.CZ - 1, pcz + R); cz++) {
                if (!loaded.containsKey(cx * SimWorld.CZ + cz)) {
                    add.add(new int[] {cx, cz, (cx - pcx) * (cx - pcx) + (cz - pcz) * (cz - pcz)});
                }
            }
        }
        add.sort((a, b) -> Integer.compare(a[2], b[2])); // 서버는 가까운 청크부터 보낸다
        for (int[] c : add) {
            load(c[0], c[1]);
        }
        firstBatch = false;
    }

    private void load(int cx, int cz) {
        int key = cx * SimWorld.CZ + cz;
        loaded.put(key, tick);
        currentRevisit = !visited.add(key);
        if (currentRevisit) {
            revisits++;
        }
        if (tracksClient()) {
            for (int[] o : world.oresByChunk.get(key)) {
                if (world.get(o[0], o[1], o[2]) == SimWorld.ORE) {
                    int idx = SimWorld.idx(o[0], o[1], o[2]);
                    boolean changed = noteFirst(key, idx);
                    known.put(idx, new ClientOre(o[0], o[1], o[2], true, tick, tick, true,
                            firstBatch ? 99 : dist(o[0], o[1], o[2]), true, changed));
                }
            }
            version++;
        }
        long t0 = System.nanoTime();
        core.decoys.onChunkSent(state(), cx, cz, tick);
        Metrics.chunkSend(System.nanoTime() - t0, currentRevisit);
    }

    private void unload(int cx, int cz) {
        loaded.remove(cx * SimWorld.CZ + cz);
        if (tracksClient()) {
            known.values().removeIf(o -> (o.x >> 4) == cx && (o.z >> 4) == cz);
            version++;
        }
        core.decoys.dropChunkFor(id, SimWorld.NAME, cx, cz, tick);
        onUnload(cx, cz);
    }

    void onUnload(int cx, int cz) {
    }

    // ---- 행동 ----

    /** 한 칸 움직인다(막혀 있으면 캔다). 월드 밖이면 false. */
    boolean stepTo(int nx, int ny, int nz) {
        if (!world.in(nx, ny, nz)) {
            return false;
        }
        int cost = MOVE_TICKS;
        if (world.solid(nx, ny, nz)) {
            core.decoys.onPlayerBreak(id, SimWorld.pos(nx, ny, nz), tick); // 변경 전에 알린다
            if (world.get(nx, ny, nz) == SimWorld.ORE) {
                oresMined++;
            }
            world.set(nx, ny, nz, SimWorld.AIR);
            if (tracksClient() && known.remove(SimWorld.idx(nx, ny, nz)) != null) {
                version++;
            }
            cost += DIG_TICKS;
        }
        x = nx;
        y = ny;
        z = nz;
        core.decoys.onMove(id, SimWorld.NAME, x + 0.5, y + SimWorld.Y0 + 0.5, z + 0.5, tick);
        updateChunks();
        poll();
        advance(cost);
        return true;
    }

    void advance(int ticks) {
        tick += ticks;
        while (tick >= nextEngineTick) {
            engineTick = nextEngineTick;
            core.decoys.tick(state(), engineTick);
            core.decoys.expire(engineTick);
            core.decoys.verifyAll(engineTick);
            poll();
            nextEngineTick += 20;
        }
    }

    /**
     * 지금의 e-value를 읽어 최대값과 규칙별 첫 확정 시각을 갱신한다.
     * 규칙 0: 혼합 ≥ 10^9. 1: 쌍(한쪽만 반응, 1.1 정의) ≥ 10^9. 2: 기본 규칙 BOTH = 혼합 ≥ 10^9 그리고 먼저 반응한 쪽 ≥ 10^3. 3: 먼저 반응한 쪽 ≥ 10^9. (규칙 2는 두 e-과정이 각각 한 번이라도 문턱을 넘었으면 된다: 엔진의 ever 표시와 같다.)
     */
    void poll() {
        var v = core.evidence.viewOf(id);
        if (v == null) {
            return;
        }
        double m = v.log10E(), p = v.log10EPaired(), f = v.log10EFirst();
        maxMix = Math.max(maxMix, m);
        maxPair = Math.max(maxPair, p);
        maxFirst = Math.max(maxFirst, f);
        long t = Math.max(tick, engineTick);
        if (crossTick[0] < 0 && m >= CONFIRM) {
            crossTick[0] = t;
        }
        if (crossTick[1] < 0 && p >= CONFIRM) {
            crossTick[1] = t;
        }
        if (crossTick[2] < 0 && maxMix >= CONFIRM && maxFirst >= GUARD) { // 두 e-과정 각각 "언젠가 넘음"
            crossTick[2] = t;
        }
        if (crossTick[3] < 0 && f >= CONFIRM) {
            crossTick[3] = t;
        }
    }

    boolean allCrossed() {
        for (long t : crossTick) {
            if (t < 0) {
                return false;
            }
        }
        return true;
    }

    /** 다음 행동 하나. 아무것도 못 했으면 false(호출자가 시간을 흘린다). */
    abstract boolean step();

    /** 모든 규칙이 확정하거나 시간이 다 될 때까지. */
    void run(long capTicks) {
        updateChunks();
        while (tick < capTicks && !(stopOnAll && allCrossed())) {
            if (!step()) {
                advance(20);
            }
        }
        core.decoys.forgetPlayer(id, engineTick);
    }

    double minutes(long ticks) {
        return ticks / 1200.0;
    }
}

package dev.anchormc.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * 미끼·위약 배치와 회수. Bukkit을 모른다(BlockView·Display로만 바깥과 닿는다).
 * 배치는 플레이어에게 청크가 전송되는 순간({@link #onChunkSent})에만 일어나고, 미끼는 그 즉시 나간다.
 * 미끼가 클라이언트로 나가는 길은 {@link #showGuarded} 하나뿐이고 거기서 불변식 1을 뭉치의 모든 블록에 대해 다시 검사한다.
 */
public final class DecoyEngine {
    private Params params;
    private final Function<String, BlockView> views;
    private final RandomGenerator rng;
    private final ResponseTracker tracker;
    private final Display display;
    private final Map<UUID, Long> nextSpawn = new HashMap<>();
    private VeinProfile profile;
    private long pairCounter;

    public DecoyEngine(Params params, Function<String, BlockView> views, Display display,
                       Consumer<Outcome> sink, RandomGenerator rng) {
        this.params = params;
        this.views = views;
        this.display = display;
        this.rng = rng;
        this.profile = new VeinProfile(params.profileSamples());
        this.tracker = new ResponseTracker(params, new ResponseTracker.Hooks() {
            @Override
            public void outcome(Outcome o) {
                sink.accept(o);
            }

            @Override
            public void retired(Site s, boolean restoreBlock) {
                // 종류에 따라 달라지는 곳은 여기와 showGuarded뿐이다(위약은 보낸 적이 없으니 되돌릴 것도 없다).
                if (s.kind == SiteKind.DECOY && restoreBlock) {
                    List<Pos> ps = new ArrayList<>(s.voxels.size());
                    for (Voxel v : s.voxels) {
                        ps.add(v.pos());
                    }
                    display.hide(s.player, ps);
                }
            }
        });
    }

    public void setParams(Params p) {
        if (p.profileSamples() != params.profileSamples()) {
            this.profile = new VeinProfile(p.profileSamples());
        }
        this.params = p;
        tracker.setParams(p);
    }

    public ResponseTracker tracker() {
        return tracker;
    }

    public VeinProfile profile() {
        return profile;
    }

    public List<Site> activeSites() {
        return tracker.allActive();
    }

    /** 주기적으로(예: 1초마다) 플레이어별로 호출. 위치 판정만 한다. 새 자리는 만들지 않는다. */
    public void tick(PlayerState p, long tick) {
        if (!p.eligible()) {
            tracker.dropPlayer(p.id(), tick, true);
            return;
        }
        tracker.observePosition(p.id(), p.world(), p.x(), p.y(), p.z(), tick);
    }

    /**
     * 이 플레이어에게 청크 (cx, cz)가 전송됐다. 이때만 새 (미끼, 위약) 쌍을 만든다: 미끼는 즉시 보낸다.
     * 진짜 광석도 청크와 함께 도착하므로, 미끼는 진짜와 같은 시점에 나타난다.
     */
    public void onChunkSent(PlayerState p, int cx, int cz, long tick) {
        if (!p.eligible()) {
            return;
        }
        BlockView view = views.apply(p.world());
        if (view == null || !view.chunkLoaded(cx, cz)) {
            return;
        }
        if (profile.wantsSamples()) {
            VeinScanner.scanChunk(view, p.world(), cx, cz, params.yMin(), params.yMax(), profile);
        }
        // 이 청크에 시도할 쌍 수: pairsPerChunk의 정수부 + 소수부 확률로 하나 더.
        double lambda = params.pairsPerChunk();
        int tries = (int) Math.floor(lambda) + (rng.nextDouble() < lambda - Math.floor(lambda) ? 1 : 0);
        for (int i = 0; i < tries; i++) {
            if (tick < nextSpawn.getOrDefault(p.id(), 0L)
                    || tracker.sitesOf(p.id()).size() + 2 > 2 * params.maxActivePairs()) {
                return;
            }
            nextSpawn.put(p.id(), tick + params.cooldownTicks());
            spawnPair(p, view, cx, cz, tick);
        }
    }

    private void spawnPair(PlayerState p, BlockView view, int cx, int cz, long tick) {
        List<List<Voxel>> taken = new ArrayList<>();
        for (Site s : tracker.sitesOf(p.id())) {
            taken.add(s.voxels);
        }
        List<Voxel> a = pick(view, p, cx, cz, taken);
        if (a == null) {
            return;
        }
        taken.add(a);
        List<Voxel> b = pick(view, p, cx, cz, taken);
        if (b == null) {
            return;
        }
        // 안전 검사는 둘 다 통과해야 한다(한쪽만 통과해 쌍이 깨지면 종류 간 분포가 달라진다).
        if (!sealedAll(view, a) || !sealedAll(view, b)) {
            return;
        }
        boolean aDecoy = rng.nextBoolean(); // 동전 던지기
        long pair = ++pairCounter;
        Site sa = new Site(p.id(), p.name(), aDecoy ? SiteKind.DECOY : SiteKind.PLACEBO, a, pair, tick);
        Site sb = new Site(p.id(), p.name(), aDecoy ? SiteKind.PLACEBO : SiteKind.DECOY, b, pair, tick);
        register(sa, view);
        register(sb, view);
    }

    private void register(Site s, BlockView view) {
        tracker.add(s);
        if (s.kind == SiteKind.DECOY) {
            showGuarded(s, view);
        }
    }

    /** 미끼가 나가는 유일한 길. 뭉치의 어느 블록이든 여섯 면이 다 막혀 있지 않으면 보내지 않고 자리를 없앤다. */
    private void showGuarded(Site s, BlockView view) {
        if (!sealedAll(view, s.voxels)) {
            tracker.retire(s, Result.VOID, s.createdTick, false);
            return;
        }
        display.show(s.player, s.voxels);
    }

    static boolean sealedAll(BlockView view, List<Voxel> voxels) {
        for (Voxel v : voxels) {
            if (!DecoyGuard.sealed(view, v.pos())) {
                return false;
            }
        }
        return true;
    }

    /**
     * 같은 규칙으로 뭉치 하나를 고른다. 미끼와 위약이 같은 함수를 쓴다.
     * 모양·크기·높이는 진짜 광맥 표본에서, 위치는 그 청크 안에서 균등하게 뽑고,
     * 모든 블록이 돌 속·봉인 상태이며 진짜 다이아 광석과 붙어 있지 않아야 한다.
     */
    private List<Voxel> pick(BlockView view, PlayerState p, int cx, int cz, List<List<Voxel>> taken) {
        double sep = params.minSeparation();
        for (int i = 0; i < params.maxAttempts(); i++) {
            VeinProfile.Placed pl = profile.draw(rng, params.yMin(), params.yMax());
            int w = 0, h = 0, d = 0;
            for (int[] c : pl.cells()) {
                w = Math.max(w, c[0] + 1);
                h = Math.max(h, c[1] + 1);
                d = Math.max(d, c[2] + 1);
            }
            if (w > 16 || d > 16 || pl.y() < params.yMin() || pl.y() + h - 1 > params.yMax()) {
                continue;
            }
            int ox = cx * 16 + rng.nextInt(17 - w), oz = cz * 16 + rng.nextInt(17 - d);
            List<Voxel> voxels = new ArrayList<>(pl.cells().length);
            boolean ok = true;
            for (int[] c : pl.cells()) {
                Pos pos = new Pos(p.world(), ox + c[0], pl.y() + c[1], oz + c[2]);
                Host host = view.hostAt(pos.x(), pos.y(), pos.z());
                if (host == null || !DecoyGuard.sealed(view, pos)) {
                    ok = false;
                    break;
                }
                voxels.add(new Voxel(pos, host));
            }
            if (!ok || touchesOre(view, voxels) || !farEnough(voxels, p) || !separated(voxels, taken, sep)) {
                continue;
            }
            return voxels;
        }
        return null;
    }

    private static boolean touchesOre(BlockView view, List<Voxel> voxels) {
        for (Voxel v : voxels) {
            Pos q = v.pos();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx != 0 || dy != 0 || dz != 0) && view.isDiamondOre(q.x() + dx, q.y() + dy, q.z() + dz)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private boolean farEnough(List<Voxel> voxels, PlayerState p) {
        for (Voxel v : voxels) {
            if (v.pos().distanceTo(p.x(), p.y(), p.z()) < params.minDistance()) {
                return false;
            }
        }
        return true;
    }

    private static boolean separated(List<Voxel> voxels, List<List<Voxel>> taken, double sep) {
        for (List<Voxel> t : taken) {
            for (Voxel a : voxels) {
                for (Voxel b : t) {
                    if (b.pos().distanceTo(a.pos().x() + 0.5, a.pos().y() + 0.5, a.pos().z() + 0.5) < sep) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    public void onMove(UUID player, String world, double x, double y, double z, long tick) {
        tracker.observePosition(player, world, x, y, z, tick);
    }

    /** 플레이어가 블록을 깬다(변경 전). 반응을 먼저 기록하고, 그 다음 노출 처리로 미끼를 거둔다. */
    public void onPlayerBreak(UUID player, Pos block, long tick) {
        tracker.observeDig(player, block, tick);
        tracker.blockChanging(block, tick);
    }

    /** 폭발·유체·피스톤·낙하 블록 등 누가 깨든 블록이 바뀌려 할 때(변경 전). */
    public void onBlockChanging(Pos block, long tick) {
        tracker.blockChanging(block, tick);
    }

    /** 이벤트 없이 바뀐 경우를 잡는 주기 검사. 뭉치의 어느 블록이든 여섯 면이 하나라도 뚫렸으면 즉시 뭉치째 거둔다. */
    public void verifyAll(long tick) {
        for (Site s : tracker.allActive()) {
            BlockView v = views.apply(s.pos.world());
            if (v == null || !sealedAll(v, s.voxels)) {
                tracker.retire(s, Result.VOID, tick, true);
            }
        }
    }

    public void expire(long tick) {
        tracker.expire(tick);
    }

    public void dropPlayer(UUID player, long tick, boolean restoreBlock) {
        tracker.dropPlayer(player, tick, restoreBlock);
        nextSpawn.remove(player);
    }

    /** 서버가 청크를 내렸다. */
    public void dropChunk(String world, int cx, int cz, long tick) {
        tracker.dropChunk(world, cx, cz, tick);
    }

    /** 이 플레이어의 클라이언트가 청크를 버렸다(PlayerChunkUnloadEvent). */
    public void dropChunkFor(UUID player, String world, int cx, int cz, long tick) {
        tracker.dropChunkFor(player, world, cx, cz, tick);
    }

    /** 플러그인 종료·리로드 시: 모든 미끼를 진짜 블록으로 되돌린다. */
    public void shutdown(long tick) {
        tracker.dropAll(tick);
    }
}

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
 * 미끼가 클라이언트로 나가는 길은 {@link #showGuarded} 하나뿐이고 거기서 불변식 1을 다시 검사한다.
 */
public final class DecoyEngine {
    private Params params;
    private final Function<String, BlockView> views;
    private final RandomGenerator rng;
    private final ResponseTracker tracker;
    private final Display display;
    private final Map<UUID, Long> nextSpawn = new HashMap<>();

    public DecoyEngine(Params params, Function<String, BlockView> views, Display display,
                       Consumer<Outcome> sink, RandomGenerator rng) {
        this.params = params;
        this.views = views;
        this.display = display;
        this.rng = rng;
        this.tracker = new ResponseTracker(params, new ResponseTracker.Hooks() {
            @Override
            public void outcome(Outcome o) {
                sink.accept(o);
            }

            @Override
            public void retired(Site s, boolean restoreBlock) {
                // 종류에 따라 달라지는 곳은 여기와 showGuarded뿐이다(위약은 보낸 적이 없으니 되돌릴 것도 없다).
                if (s.kind == SiteKind.DECOY && restoreBlock) {
                    display.hide(s.player, s.pos);
                }
            }
        });
    }

    public void setParams(Params p) {
        this.params = p;
        tracker.setParams(p);
    }

    public ResponseTracker tracker() {
        return tracker;
    }

    public List<Site> activeSites() {
        return tracker.allActive();
    }

    /** 주기적으로(예: 1초마다) 플레이어별로 호출. 새 쌍을 만들 때가 되면 만든다. */
    public void tick(PlayerState p, long tick) {
        if (!p.eligible()) {
            tracker.dropPlayer(p.id(), tick, true);
            return;
        }
        tracker.observePosition(p.id(), p.world(), p.x(), p.y(), p.z(), tick);
        if (tick < nextSpawn.getOrDefault(p.id(), 0L)) {
            return;
        }
        if (tracker.sitesOf(p.id()).size() + 2 > 2 * params.maxActivePairs()) {
            return;
        }
        nextSpawn.put(p.id(), tick + params.cooldownTicks());
        spawnPair(p, tick);
    }

    private void spawnPair(PlayerState p, long tick) {
        BlockView view = views.apply(p.world());
        if (view == null) {
            return;
        }
        List<Pos> taken = new ArrayList<>();
        for (Site s : tracker.sitesOf(p.id())) {
            taken.add(s.pos);
        }
        Pos a = pick(view, p, taken);
        if (a == null) {
            return;
        }
        taken.add(a);
        Pos b = pick(view, p, taken);
        if (b == null) {
            return;
        }
        // 안전 검사는 둘 다 통과해야 한다(한쪽만 통과해 쌍이 깨지면 종류 간 분포가 달라진다).
        Host ha = view.hostAt(a.x(), a.y(), a.z());
        Host hb = view.hostAt(b.x(), b.y(), b.z());
        if (ha == null || hb == null || !DecoyGuard.sealed(view, a) || !DecoyGuard.sealed(view, b)) {
            return;
        }
        boolean aDecoy = rng.nextBoolean(); // 동전 던지기
        Site sa = new Site(p.id(), p.name(), aDecoy ? SiteKind.DECOY : SiteKind.PLACEBO, a, ha, tick);
        Site sb = new Site(p.id(), p.name(), aDecoy ? SiteKind.PLACEBO : SiteKind.DECOY, b, hb, tick);
        register(sa, view);
        register(sb, view);
    }

    private void register(Site s, BlockView view) {
        tracker.add(s);
        if (s.kind == SiteKind.DECOY) {
            showGuarded(s, view);
        }
    }

    /** 미끼가 나가는 유일한 길. 여섯 면이 다 막혀 있지 않으면 보내지 않고 자리를 없앤다. */
    private void showGuarded(Site s, BlockView view) {
        if (!DecoyGuard.sealed(view, s.pos)) {
            tracker.retire(s, Result.VOID, s.createdTick, false);
            return;
        }
        display.show(s.player, s.pos, s.host);
    }

    /** 같은 규칙으로 좌표 하나를 고른다. 미끼와 위약이 같은 함수를 쓴다. */
    private Pos pick(BlockView view, PlayerState p, List<Pos> taken) {
        double sep = params.minSeparation();
        for (int i = 0; i < params.maxAttempts(); i++) {
            double dx = rng.nextGaussian(), dy = rng.nextGaussian(), dz = rng.nextGaussian();
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1e-9) {
                continue;
            }
            double r = params.minDistance() + rng.nextDouble() * (params.maxDistance() - params.minDistance());
            int x = (int) Math.floor(p.x() + dx / len * r);
            int y = (int) Math.floor(p.y() + dy / len * r);
            int z = (int) Math.floor(p.z() + dz / len * r);
            if (y < params.yMin() || y > params.yMax()) {
                continue;
            }
            Pos pos = new Pos(p.world(), x, y, z);
            if (!DecoyGuard.sealed(view, pos)) {
                continue;
            }
            boolean clear = true;
            for (Pos t : taken) {
                if (t.distanceTo(x + 0.5, y + 0.5, z + 0.5) < sep) {
                    clear = false;
                    break;
                }
            }
            if (clear) {
                return pos;
            }
        }
        return null;
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

    /** 이벤트 없이 바뀐 경우를 잡는 주기 검사. 여섯 면이 하나라도 뚫렸으면 즉시 거둔다. */
    public void verifyAll(long tick) {
        for (Site s : tracker.allActive()) {
            BlockView v = views.apply(s.pos.world());
            if (v == null || !DecoyGuard.sealed(v, s.pos)) {
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

    public void dropChunk(String world, int cx, int cz, long tick) {
        tracker.dropChunk(world, cx, cz, tick);
    }

    /** 플러그인 종료·리로드 시: 모든 미끼를 진짜 블록으로 되돌린다. */
    public void shutdown(long tick) {
        tracker.dropAll(tick);
    }
}

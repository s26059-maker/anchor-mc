package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static dev.anchormc.core.InvariantTest.PLAYER;
import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.at;
import static dev.anchormc.core.InvariantTest.params;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 실서버 1차 테스트에서 미끼·위약이 거의 전부 회수된 버그의 재현과 회수 사유 기록.
 * 원인 둘: (1) 자격 상실(게임모드 변경 등)이 계획 전체를 영구 회수했다, (2) 주기 검사가 로드 안 된 청크를 "뚫림"으로 봤다.
 */
class RetirementCauseTest {
    /** 청크 단위로 "로드 안 됨"을 만들 수 있는 창. 로드 안 된 청크의 블록은 고체 아님·돌 아님으로 보인다(BukkitBlockView와 같다). */
    static final class GatedView implements BlockView {
        final GridWorld w;
        final Set<Long> unloaded = new HashSet<>();

        GatedView(GridWorld w) {
            this.w = w;
        }

        static long key(int cx, int cz) {
            return ((long) cx << 32) ^ (cz & 0xffffffffL);
        }

        @Override
        public boolean chunkLoaded(int cx, int cz) {
            return !unloaded.contains(key(cx, cz)) && w.chunkLoaded(cx, cz);
        }

        @Override
        public boolean isStableOpaque(int x, int y, int z) {
            return chunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) && w.isStableOpaque(x, y, z);
        }

        @Override
        public Host hostAt(int x, int y, int z) {
            return chunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16)) ? w.hostAt(x, y, z) : null;
        }

        @Override
        public boolean isDiamondOre(int x, int y, int z) {
            return w.isDiamondOre(x, y, z);
        }
    }

    static final class Counting implements Display {
        int shows, hides;

        @Override
        public void show(UUID player, List<Voxel> voxels) {
            shows++;
        }

        @Override
        public void hide(UUID player, List<Pos> positions) {
            hides++;
        }
    }

    private static int statusCount(DecoyEngine e, String prefix) {
        int n = 0;
        for (DecoyEngine.SiteDebug d : e.debugSites(PLAYER)) {
            if (d.status().startsWith(prefix)) {
                n++;
            }
        }
        return n;
    }

    // ---- 원인 1: 자격 상실이 계획을 영구 회수 ----

    @Test
    void losingEligibilityHidesDecoysButDoesNotRetirePlansForever() {
        GridWorld w = GridWorld.solid(48);
        Counting d = new Counting();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(31));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        int planned = e.debugSites(PLAYER).size();
        assertTrue(planned > 0 && !e.activeSites().isEmpty());

        // 크리에이티브로 바꿨다: 미끼는 화면에서 거두지만 그 플레이어의 계획이 사라지면 안 된다.
        e.dropPlayer(PLAYER, 5, true);
        e.tick(new PlayerState(PLAYER, "tester", W, 24, 24, 24, false), 6);
        assertTrue(e.activeSites().isEmpty());
        assertEquals(0, statusCount(e, "회수됨"), "자격 상실이 계획을 영구 회수로 표시했다");

        // 서바이벌로 돌아와 청크를 다시 받으면 같은 미끼가 다시 나간다.
        int showsBefore = d.shows;
        InvariantTest.sendChunks(e, at(24, 24, 24), 100);
        assertTrue(d.shows > showsBefore, "자격을 되찾았는데 미끼가 다시 나가지 않는다");
        assertFalse(e.activeSites().isEmpty());
    }

    // ---- 원인 2: 로드 안 된 청크를 "뚫림"으로 봤다 ----

    @Test
    void periodicCheckHoldsWhenChunkIsNotLoaded() {
        GridWorld w = GridWorld.solid(48);
        GatedView v = new GatedView(w);
        Counting d = new Counting();
        DecoyEngine e = new DecoyEngine(params(), name -> v, d, o -> { }, rng(32));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        int active = e.activeSites().size();
        assertTrue(active > 0);

        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                v.unloaded.add(GatedView.key(cx, cz));
            }
        }
        for (int t = 1; t <= 40; t++) {
            e.verifyAll(t); // 로드 여부를 모르는 것이지 뚫린 것이 아니다
        }
        assertEquals(active, e.activeSites().size(), "로드 안 된 청크를 이유로 자리를 회수했다");
        assertEquals(0, d.hides);
        assertEquals(0, statusCount(e, "회수됨"));
    }

    @Test
    void heldSitesAreRetiredWhenChunkLoadsAndIsActuallyExposed() {
        GridWorld w = GridWorld.solid(48);
        GatedView v = new GatedView(w);
        Counting d = new Counting();
        DecoyEngine e = new DecoyEngine(params(), name -> v, d, o -> { }, rng(33));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Site victim = e.activeSites().get(0);
        Pos vp = victim.voxels.get(0).pos();
        int cx = vp.chunkX(), cz = vp.chunkZ();

        v.unloaded.add(GatedView.key(cx, cz));
        e.verifyAll(1);
        assertTrue(victim.active(), "판단 보류 중이어야 한다");

        // 그 사이 이벤트 없이 바뀌었고(월드에딧), 청크가 다시 로드됐다.
        w.set(vp.x(), vp.y() + 1, vp.z(), GridWorld.AIR);
        w.set(vp.x(), vp.y() - 1, vp.z(), GridWorld.AIR);
        v.unloaded.remove(GatedView.key(cx, cz));
        e.onChunkLoaded(W, cx, cz, 2);
        assertFalse(victim.active(), "로드된 뒤 실제로 노출됐는데 회수하지 않았다(불변식 3)");
        assertTrue(d.hides > 0);
    }

    @Test
    void guardDistinguishesBreachFromUnknownNeighbor() {
        GridWorld w = GridWorld.solid(48);
        GatedView v = new GatedView(w);
        Pos edge = new Pos(W, 16, 20, 16); // 청크 (1,1)의 북서쪽 끝: 서쪽 이웃이 청크 (0,1), 북쪽 이웃이 청크 (1,0)
        assertEquals(DecoyGuard.State.SEALED, DecoyGuard.check(v, edge).state());
        v.unloaded.add(GatedView.key(0, 1));
        DecoyGuard.Seal s = DecoyGuard.check(v, edge);
        assertEquals(DecoyGuard.State.UNKNOWN, s.state());
        assertFalse(DecoyGuard.sealed(v, edge), "판단 보류여도 미끼를 새로 보내는 데는 쓸 수 없다");
        assertEquals(new Pos(W, 15, 20, 16), s.blocker());
        v.unloaded.clear();
        w.set(15, 20, 16, GridWorld.AIR);
        s = DecoyGuard.check(v, edge);
        assertEquals(DecoyGuard.State.BREACHED, s.state());
        assertEquals(new Pos(W, 15, 20, 16), s.blocker());
        // 한쪽이 뚫렸고 다른 쪽이 모르는 상태면 뚫림이 이긴다.
        v.unloaded.add(GatedView.key(1, 0));
        assertEquals(DecoyGuard.State.BREACHED, DecoyGuard.check(v, edge).state());
    }

    // ---- 사유 기록 ----

    @Test
    void everyRetirementRecordsCauseAndDetail() {
        GridWorld w = GridWorld.solid(48);
        Counting d = new Counting();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(34));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        List<Site> sites = new ArrayList<>(e.activeSites());
        assertTrue(sites.size() >= 4);

        // 이벤트 없이 뚫림: 주기 검사가 잡고, 좌표·블록 종류·청크 로드 여부를 남긴다.
        Site a = sites.get(0);
        Pos ap = a.voxels.get(0).pos();
        Pos n = firstFaceNeighborOutside(a, ap);
        w.set(n.x(), n.y(), n.z(), GridWorld.AIR);
        for (int t = 1; t <= 10; t++) {
            e.verifyAll(t);
        }
        assertFalse(a.active());
        assertEquals(1, e.retireCounts().get(RetireCause.PERIODIC_BREACH).longValue());
        String status = statusOf(e, a);
        assertTrue(status.startsWith("회수됨"), status);
        assertTrue(status.contains("주기 검사"), status);
        assertTrue(status.contains(n.x() + ", " + n.y() + ", " + n.z()), status);
        assertTrue(status.contains("청크 로드=true"), status);

        // 블록 변경 이벤트.
        Site b = sites.stream().filter(x -> x.active() && x.pairId != a.pairId).findFirst().orElseThrow();
        e.onBlockChanging(b.voxels.get(0).pos(), 20);
        assertFalse(b.active());
        assertTrue(e.retireCounts().get(RetireCause.BLOCK_EVENT) >= 1);
        assertTrue(statusOf(e, b).contains("블록 변경 이벤트"), statusOf(e, b));

        // 자격 상실은 회수 사유로 집계되지만 (다른 사유로 접히지 않은) 쌍을 영구 회수로 표시하지 않는다.
        Site c = sites.stream().filter(x -> x.active() && x.pairId != a.pairId && x.pairId != b.pairId).findFirst().orElseThrow();
        e.dropPlayer(PLAYER, 30, true);
        assertTrue(e.retireCounts().get(RetireCause.INELIGIBLE) >= 1);
        String cs = statusOf(e, c);
        assertFalse(cs.startsWith("회수됨"), cs);
        assertTrue(cs.contains("플레이어 자격 상실"), cs);
    }

    private static Pos firstFaceNeighborOutside(Site s, Pos p) {
        Set<Pos> own = new HashSet<>();
        s.voxels.forEach(v -> own.add(v.pos()));
        for (int[] f : Pos.FACES) {
            Pos q = p.offset(f[0], f[1], f[2]);
            if (!own.contains(q)) {
                return q;
            }
        }
        throw new AssertionError();
    }

    private static String statusOf(DecoyEngine e, Site s) {
        for (DecoyEngine.SiteDebug d : e.debugSites(PLAYER)) {
            if (d.pos().equals(s.pos) && d.kind() == s.kind) {
                return d.status();
            }
        }
        throw new AssertionError("자리를 찾지 못했다: " + s.pos);
    }

    @Test
    void terminalRetirementCannotBeUndoneByLateConsume() {
        PlannedPair pp = new PlannedPair(1, 0, List.of(new Voxel(new Pos(W, 1, 1, 1), Host.STONE)),
                List.of(new Voxel(new Pos(W, 5, 5, 5), Host.STONE)), true);
        pp.retire(new Reason(RetireCause.PACKET_UNSEALED, "x", 0));
        pp.consume(Result.HIT);
        assertTrue(pp.retired());
        assertFalse(pp.consumed());
    }
}

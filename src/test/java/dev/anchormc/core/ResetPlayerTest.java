package dev.anchormc.core;

import dev.anchormc.AnchorCore;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static dev.anchormc.core.InvariantTest.PLAYER;
import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.at;
import static dev.anchormc.core.InvariantTest.params;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /anchor reset의 배치 쪽: 판정 없이 거두고, 시드가 같아 같은 자리가 새 상태로 돌아오며, 판정 로직과 다른 플레이어는 그대로다. */
class ResetPlayerTest {
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    private static Set<Pos> decoyPositions(DecoyEngine e, UUID player) {
        return e.debugSites(player).stream().filter(s -> s.kind() == SiteKind.DECOY).map(DecoyEngine.SiteDebug::pos).collect(Collectors.toSet());
    }

    /** 뭉치의 가장 가까운 블록에서 2.7블록 떨어진(반응 반경 안, 노클립 거리 밖) 점. */
    private static double[] approach(Site s) {
        Pos mn = s.voxels.stream().map(Voxel::pos).min(Comparator.comparingInt(Pos::x)).orElseThrow();
        return new double[] {mn.x() + 0.5 - 2.7, mn.y() + 0.5, mn.z() + 0.5};
    }

    @Test
    void resetHidesDecoysWithoutAnyOutcomeAndForgetsPlans() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, rng(61));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        assertFalse(e.activeSites().isEmpty());
        assertFalse(d.shown.isEmpty());

        List<Long> ids = e.resetPlayer(PLAYER, 5);
        assertFalse(ids.isEmpty());
        assertTrue(e.activeSites().isEmpty());
        assertTrue(e.debugSites(PLAYER).isEmpty());
        assertTrue(d.shown.isEmpty(), "화면에 미끼가 남았다");
        assertTrue(outs.isEmpty(), "초기화가 판정(VOID 포함)을 냈다");
        var counts = e.retireCounts();
        assertTrue(counts.get(RetireCause.RESET) > 0);
        assertEquals(0, counts.get(RetireCause.INELIGIBLE), "초기화가 자격 상실 통계를 부풀렸다");
        assertEquals(0, e.restoredPairs());
    }

    @Test
    void sameSeedBringsTheSameSitesBackWithFreshState() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, rng(62));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Set<Pos> before = decoyPositions(e, PLAYER);
        // 한 자리를 판정시켜 쌍 상태(판정·반응 기록)를 더럽힌다.
        Site target = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(target);
        e.onMove(PLAYER, W, p[0], p[1], p[2], 10);
        assertEquals(Result.HIT, target.result());
        assertTrue(e.debugSites(PLAYER).stream().anyMatch(s -> s.status().contains("미끼=HIT")));

        e.resetPlayer(PLAYER, 20);
        outs.clear();
        InvariantTest.sendChunks(e, at(24, 24, 24), 30);
        assertEquals(before, decoyPositions(e, PLAYER), "시드가 같은데 자리가 달라졌다");
        assertTrue(e.debugSites(PLAYER).stream().noneMatch(s -> s.status().contains("HIT")),
                "이전 판정·반응 기록이 남았다: " + e.debugSites(PLAYER));
        assertTrue(outs.isEmpty());
        // 같은 자리가 다시 한 번 정상적으로 판정된다.
        Site again = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY && s.pairId == target.pairId).findFirst().orElseThrow();
        e.onMove(PLAYER, W, p[0], p[1], p[2], 40);
        assertEquals(Result.HIT, again.result());
    }

    @Test
    void otherPlayersAreUntouched() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(63));
        PlayerState other = new PlayerState(OTHER, "other", W, 24, 24, 24, true);
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        InvariantTest.sendChunks(e, other, 0);
        Set<Pos> otherBefore = decoyPositions(e, OTHER);
        long otherActive = e.activeSites().stream().filter(s -> s.player.equals(OTHER)).count();
        assertTrue(otherActive > 0);

        e.resetPlayer(PLAYER, 5);
        assertEquals(otherBefore, decoyPositions(e, OTHER));
        assertEquals(otherActive, e.activeSites().stream().filter(s -> s.player.equals(OTHER)).count());
        assertTrue(e.activeSites().stream().noneMatch(s -> s.player.equals(PLAYER)));
    }

    @Test
    void coreResetWipesEvidenceAndAFreshPassCountsOnceNotTwice() {
        GridWorld w = GridWorld.solid(48);
        MemoryStore store = new MemoryStore();
        AnchorCore core = new AnchorCore(oneShotParams(), EvidenceParams.defaults(), name -> w, new Quiet(), store, rng(64), () -> 1L, c -> { });
        PlayerState pl = at(24, 24, 24);
        core.decoys.onChunkSent(pl, 1, 1, 0);
        Site decoy = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(decoy);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 10); // 미끼 HIT
        core.decoys.expire(5000); // 위약 MISS(창 종료)
        var v = core.evidence.view("tester");
        assertNotNull(v);
        assertEquals(1, v.decoyHits());
        assertEquals(1, v.placeboN());
        assertEquals(1, v.firstDecoy());
        assertNotNull(store.load(PLAYER));

        var old = core.resetPlayer(PLAYER, 6000);
        assertEquals(1, old.decoyHits());
        assertNull(core.evidence.view("tester"));
        assertNull(store.load(PLAYER));
        assertEquals(0, core.evidence.stats().placeboN());
        assertEquals(0, core.evidence.pendingPairs());
        assertTrue(core.decoys.activeSites().isEmpty());

        // 청크를 다시 받아 같은 일을 한 번 더: 기록은 처음부터 1이지 2가 아니다.
        core.decoys.onChunkSent(pl, 1, 1, 7000);
        Site decoy2 = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        assertEquals(decoy.pairId, decoy2.pairId);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 7010);
        core.decoys.expire(20000);
        var v2 = core.evidence.view("tester");
        assertEquals(1, v2.decoyN());
        assertEquals(1, v2.decoyHits());
        assertEquals(1, v2.placeboN());
        assertEquals(1, v2.firstDecoy(), "같은 쌍의 먼저 반응이 지워지지 않아 다시 세지 못했다");
        assertEquals(1, v2.pairDecoyOnly());
    }

    private static Params oneShotParams() {
        return new Params(3.0, 1000, 60.0, 2.0, 0, 63, 300, 1.0, 0);
    }

    private static final class Quiet implements Display {
        public void show(UUID p, List<Voxel> v) { }

        public void hide(UUID p, List<Pos> ps) { }
    }
}

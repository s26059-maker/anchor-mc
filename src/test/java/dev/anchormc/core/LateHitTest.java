package dev.anchormc.core;

import dev.anchormc.AnchorCore;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.MemoryStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

/** 판정 창이 끝난 뒤의 첫 반응(LATE_HIT)은 "먼저 반응한 쪽" 검정에만 쓰이고, 쌍당 한 번이다. */
class LateHitTest {
    private static final class Quiet implements Display {
        public void show(UUID p, List<Voxel> v) { }

        public void hide(UUID p, List<Pos> ps) { }
    }

    /** 뭉치의 가장 가까운 블록에서 2.7블록 떨어진(반응 반경 안, 노클립 거리 밖) 점. */
    private static double[] approach(Site s) {
        Pos mn = s.voxels.stream().map(Voxel::pos).min(Comparator.comparingInt(Pos::x)).orElseThrow();
        return new double[] {mn.x() + 0.5 - 2.7, mn.y() + 0.5, mn.z() + 0.5};
    }

    private static Params oneShotParams() {
        return new Params(3.0, 1000, 60.0, 2.0, 0, 63, 300, 1.0, 0);
    }

    @Test
    void aReactionAfterTheMissWindowIsReportedOnceAsLateHit() {
        GridWorld w = GridWorld.solid(48);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(oneShotParams(), name -> w, new Quiet(), outs::add, rng(1));
        e.onChunkSent(at(24, 24, 24), 1, 1, 0);
        e.expire(5000);
        assertEquals(2, outs.size());
        assertTrue(outs.stream().allMatch(o -> o.result() == Result.MISS));
        Site target = e.activeSites().stream().filter(s -> s.kind == SiteKind.PLACEBO).findFirst().orElseThrow();
        double[] p = approach(target);
        e.onMove(PLAYER, W, p[0], p[1], p[2], 6000);
        assertEquals(3, outs.size());
        assertEquals(Result.LATE_HIT, outs.get(2).result());
        assertEquals(SiteKind.PLACEBO, outs.get(2).kind());
        e.onMove(PLAYER, W, p[0], p[1], p[2], 6001);
        e.onMove(PLAYER, W, p[0] + 0.1, p[1], p[2], 6002);
        assertEquals(3, outs.size(), "같은 자리에서 반응이 또 알려졌다");
    }

    @Test
    void anUnjudgedPairKeepsBeingWatchedAfterItsWindowsAreSpentAndRevisitsRestoreBothArms() {
        GridWorld w = GridWorld.solid(48);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(oneShotParams(), name -> w, new Quiet(), outs::add, rng(2));
        PlayerState pl = at(24, 24, 24);
        e.onChunkSent(pl, 1, 1, 0);
        e.expire(5000);
        e.dropChunkFor(PLAYER, W, 1, 1, 5001);
        e.onChunkSent(pl, 1, 1, 6000);
        assertEquals(2, e.activeSites().size(), "판정은 끝났어도 반응이 없었으면 두 자리를 다시 지켜봐야 한다");
        assertTrue(e.activeSites().stream().allMatch(s -> s.result() == Result.MISS));
        int before = outs.size();
        Site decoy = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(decoy);
        e.onMove(PLAYER, W, p[0], p[1], p[2], 7000);
        assertEquals(before + 1, outs.size());
        assertEquals(Result.LATE_HIT, outs.get(outs.size() - 1).result());
        // 반응이 한 번 나온 쌍은 다시 받을 때 미끼만 (일관성을 위해) 보이고 더 지켜보지 않는다.
        e.dropChunkFor(PLAYER, W, 1, 1, 7001);
        e.onChunkSent(pl, 1, 1, 8000);
        assertEquals(1, e.activeSites().size());
        assertEquals(SiteKind.DECOY, e.activeSites().get(0).kind);
    }

    @Test
    void evidenceCountsTheFirstReactionOncePerPairWhicheverArmAndWhenever() {
        GridWorld w = GridWorld.solid(48);
        RandomGenerator r = rng(3);
        AnchorCore core = new AnchorCore(oneShotParams(), new EvidenceParams(1e-9, 2.0, 100), name -> w, new Quiet(),
                new MemoryStore(), r, () -> 1L, c -> { });
        core.decoys.onChunkSent(at(24, 24, 24), 1, 1, 0);
        core.expire(5000); // 양쪽 MISS
        Site placebo = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.PLACEBO).findFirst().orElseThrow();
        double[] p = approach(placebo);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 6000);
        core.expire(6000); // 다음 1초 틱이 창 끝 순서로 증거에 넣는다
        var v = core.evidence.viewOf(PLAYER);
        assertEquals(0, v.firstDecoy());
        assertEquals(1, v.firstPlacebo(), "창이 끝난 뒤의 첫 반응도 먼저 반응한 쪽으로 센다");
        // 혼합에는 늦은 반응이 안 들어간다: 위약 관측은 MISS 하나뿐이다.
        assertEquals(1, v.placeboN());
        assertEquals(0, v.placeboHits());
        // 다른 쪽이 나중에 반응해도 이 쌍은 다시 세지 않는다.
        Site decoy = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] q = approach(decoy);
        core.decoys.onMove(PLAYER, W, q[0], q[1], q[2], 7000);
        core.expire(7000);
        v = core.evidence.viewOf(PLAYER);
        assertEquals(0, v.firstDecoy());
        assertEquals(1, v.firstPlacebo());
        assertFalse(core.evidence.pendingPairs() < 0);
    }
}

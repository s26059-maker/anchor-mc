package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static dev.anchormc.core.InvariantTest.PLAYER;
import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.at;
import static dev.anchormc.core.InvariantTest.params;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실서버 HIT 테스트: 엑스레이 유저가 자리로 한 블록씩 곧장 파 들어갈 때의 이벤트 순서(이동 → 채굴 → 회수)를 재생한다. */
class DigApproachTest {
    record Run(List<Outcome> outs, List<String> log, Site target, DecoyEngine engine) {
    }

    /** 채굴은 서버 이벤트 순서대로: 플레이어가 한 칸 나아가고, 앞 블록을 캔다(변경 전 이벤트) → 월드에서 블록이 사라진다. */
    static Run dig(SiteKind kind, long seed) {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, rng(seed));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Site target = e.activeSites().stream().filter(s -> s.kind == kind).findFirst().orElseThrow();
        // 자리에서 가장 +z쪽 블록 하나를 정해 그 x, y 줄을 따라 +z쪽 8칸 밖에서 -z 방향으로 판다.
        Voxel end = target.voxels.get(0);
        for (Voxel v : target.voxels) {
            if (v.pos().z() > end.pos().z()) {
                end = v;
            }
        }
        int x = end.pos().x(), y = end.pos().y();
        List<String> log = new ArrayList<>();
        long tick = 10;
        int startZ = Math.min(end.pos().z() + 8, 46);
        for (int z = startZ; z > end.pos().z(); z--) {
            tick++;
            // 플레이어는 캘 블록 바로 뒤에 서 있다(눈높이 +1 아래로 파므로 y는 발 기준 y-1).
            e.onMove(PLAYER, W, x + 0.5, y + 0.5, z + 1.5, tick);
            Pos block = new Pos(W, x, y, z);
            e.onPlayerBreak(PLAYER, block, tick);
            w.set(x, y, z, GridWorld.AIR);
            log.add("tick " + tick + " 캠 " + block + " → 활성 " + target.active() + " 결과 " + target.result());
            if (!target.active()) {
                break;
            }
        }
        return new Run(outs, log, target, e);
    }

    private static void assertReactionThenRetireInSameTick(Run r) {
        assertFalse(r.target().active(), "자리가 회수돼야 한다(노출 방지)");
        List<Outcome> mine = r.outs().stream().filter(o -> o.pairId() == r.target().pairId && o.kind() == r.target().kind).toList();
        assertEquals(1, mine.size(), "이 자리의 판정은 정확히 하나: " + r.outs() + "\n" + String.join("\n", r.log()));
        assertEquals(Result.HIT, mine.get(0).result(), String.join("\n", r.log()));
        assertTrue(r.outs().stream().noneMatch(o -> o.pairId() == r.target().pairId && o.kind() == r.target().kind && o.result() == Result.VOID),
                "반응이 기록되지 않고 VOID로 제외됐다");
    }

    @Test
    void decoyDugTowardIsRecordedAsHitAndRetiredNotVoided() {
        assertReactionThenRetireInSameTick(dig(SiteKind.DECOY, 51));
    }

    @Test
    void placeboDugTowardIsTreatedIdentically() {
        assertReactionThenRetireInSameTick(dig(SiteKind.PLACEBO, 51));
    }

    // ---- 반응 반경을 건너뛰는 이동과 다른 이유의 회수 ----

    private static Site freshTarget(DecoyEngine e, SiteKind kind) {
        return e.activeSites().stream().filter(x -> x.kind == kind).findFirst().orElseThrow();
    }

    private static DecoyEngine engine(List<Outcome> outs, long seed) {
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new InvariantTest.CheckingDisplay(w), outs::add, rng(seed));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        return e;
    }

    /** 자리에서 x 방향으로 거리 dist만큼 떨어진 점(중심 기준으로 가장 가까운 블록까지의 거리가 dist). */
    private static double[] pointAt(Site s, double dist) {
        Pos p = s.pos;
        double lo = 0, hi = 60;
        for (int i = 0; i < 60; i++) {
            double mid = (lo + hi) / 2;
            if (s.distanceTo(p.x() + 0.5 + mid, p.y() + 0.5, p.z() + 0.5) < dist) {
                lo = mid;
            } else {
                hi = mid;
            }
        }
        return new double[] {p.x() + 0.5 + hi, p.y() + 0.5, p.z() + 0.5};
    }

    @Test
    void oneStepFromOutsideTheRadiusToInsideRetractDistanceIsStillReactionFirst() {
        for (SiteKind kind : SiteKind.values()) {
            List<Outcome> outs = new ArrayList<>();
            DecoyEngine e = engine(outs, 52);
            Site s = freshTarget(e, kind);
            double[] far = pointAt(s, 3.3), near = pointAt(s, 1.6); // 걸음 하나(1.7블록)로 반응 띠(2.0~3.0)를 건너뜀
            e.onMove(PLAYER, W, far[0], far[1], far[2], 20);
            e.onMove(PLAYER, W, near[0], near[1], near[2], 21);
            assertFalse(s.active());
            List<Outcome> mine = outs.stream().filter(o -> o.pairId() == s.pairId && o.kind() == kind).toList();
            assertEquals(1, mine.size(), kind + ": " + outs);
            assertEquals(Result.HIT, mine.get(0).result(), kind + ": 반응 띠를 건너뛰어 반응이 기록되지 않았다");
            assertEquals(RetireCause.TOO_CLOSE, s.reason.cause());
        }
    }

    @Test
    void teleportNextToASiteStaysExcludedFromEvidence() {
        for (SiteKind kind : SiteKind.values()) {
            List<Outcome> outs = new ArrayList<>();
            DecoyEngine e = engine(outs, 53);
            Site s = freshTarget(e, kind);
            double[] far = pointAt(s, 30), near = pointAt(s, 1.6);
            e.onMove(PLAYER, W, far[0], far[1], far[2], 20);
            e.onMove(PLAYER, W, near[0], near[1], near[2], 21); // 텔레포트
            assertFalse(s.active());
            assertTrue(outs.stream().noneMatch(o -> o.pairId() == s.pairId && o.kind() == kind && o.result() == Result.HIT), kind + ": 텔레포트가 반응으로 기록됐다");
            assertTrue(outs.stream().anyMatch(o -> o.pairId() == s.pairId && o.kind() == kind && o.result() == Result.VOID));
        }
    }

    @Test
    void otherPlayersDigsAndNonPlayerChangesStayExcluded() {
        for (SiteKind kind : SiteKind.values()) {
            List<Outcome> outs = new ArrayList<>();
            DecoyEngine e = engine(outs, 54);
            Site s = freshTarget(e, kind);
            e.onPlayerBreak(java.util.UUID.fromString("00000000-0000-0000-0000-0000000000ff"), s.voxels.get(0).pos().offset(0, 0, 1), 30);
            assertFalse(s.active());
            assertTrue(outs.stream().noneMatch(o -> o.pairId() == s.pairId && o.kind() == kind && o.result() == Result.HIT), kind + ": 남의 채굴이 반응으로 기록됐다");
            assertEquals(RetireCause.BLOCK_EVENT, s.reason.cause());

            List<Outcome> outs2 = new ArrayList<>();
            DecoyEngine e2 = engine(outs2, 55);
            Site s2 = freshTarget(e2, kind);
            e2.onBlockChanging(s2.voxels.get(0).pos().offset(1, 0, 0), 30); // 폭발·유체·피스톤
            assertFalse(s2.active());
            assertTrue(outs2.stream().noneMatch(o -> o.pairId() == s2.pairId && o.kind() == kind && o.result() == Result.HIT));
        }
    }

    @Test
    void debugShowsWhichReactionsWereRecordedForRetiredPairs() {
        Run r = dig(SiteKind.DECOY, 51);
        String status = r.engine().debugSites(PLAYER).stream()
                .filter(d -> d.pos().equals(r.target().pos) && d.kind() == SiteKind.DECOY).findFirst().orElseThrow().status();
        assertTrue(status.startsWith("회수됨"), status);
        assertTrue(status.contains("반응 기록: 미끼=HIT"), "회수된 쌍의 상태에 기록된 판정이 보여야 한다: " + status);
    }
}

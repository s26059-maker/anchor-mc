package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
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

/** 배치는 청크 전송(패킷) 시점에만, 미끼는 그 호출 안에서 즉시. 언로드하면 정리. */
class ChunkPlacementTest {
    /** onChunkSent 호출 중에만 show가 불려야 한다. */
    static final class TimedDisplay implements Display {
        boolean inChunkCall;
        int shows, hides, showsOutsideCall;
        final List<Pos> lastChunkVoxels = new ArrayList<>();

        @Override
        public void show(UUID player, List<Voxel> voxels) {
            shows++;
            if (!inChunkCall) {
                showsOutsideCall++;
            }
            voxels.forEach(v -> lastChunkVoxels.add(v.pos()));
        }

        @Override
        public void hide(UUID player, List<Pos> positions) {
            hides++;
        }
    }

    @Test
    void sitesAndDecoysAppearOnlyInsideTheChunkSentCall() {
        RandomGenerator r = rng(21);
        GridWorld w = GridWorld.solid(48);
        TimedDisplay d = new TimedDisplay();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
        PlayerState p = at(24, 24, 24);
        for (int t = 0; t < 500; t++) {
            e.tick(p, t);
            e.expire(t);
            e.verifyAll(t);
            e.onMove(PLAYER, W, 24 + t % 5, 24, 24, t);
        }
        assertEquals(0, d.shows);
        assertTrue(e.activeSites().isEmpty(), "청크 전송 없이 자리가 생겼다");
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                d.inChunkCall = true;
                d.lastChunkVoxels.clear();
                e.onChunkSent(p, cx, cz, 1000);
                d.inChunkCall = false;
                for (Pos q : d.lastChunkVoxels) {
                    assertEquals(cx, q.chunkX(), "이 청크에 속하지 않는 미끼");
                    assertEquals(cz, q.chunkZ(), "이 청크에 속하지 않는 미끼");
                }
            }
        }
        assertTrue(d.shows > 0);
        assertEquals(0, d.showsOutsideCall, "청크 전송 호출 밖에서 미끼가 나갔다");
        int shows = d.shows;
        for (int t = 1001; t < 3000; t++) {
            e.tick(p, t);
            e.verifyAll(t);
            e.expire(t);
        }
        assertEquals(shows, d.shows, "시간이 흐르면서 새 미끼가 나왔다");
    }

    @Test
    void engineSourceHasNoTimerBasedPlacementPath() throws Exception {
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/anchormc/core/DecoyEngine.java"));
        // 계획(planner.plan)은 planFor에서만, planFor는 prepareChunk에서만 불린다. tick()에는 배치가 없다.
        assertEquals(1, src.lines().filter(l -> l.contains("planner.plan(")).count());
        assertEquals(2, src.lines().filter(l -> l.contains("planFor(")).count(), "planFor는 정의 하나와 prepareChunk의 호출 하나");
        String tickBody = src.substring(src.indexOf("public void tick("), src.indexOf("// ---- 광맥 표본 학습"));
        assertFalse(tickBody.contains("prepareChunk") || tickBody.contains("planFor") || tickBody.contains("registerChunk"));
    }

    @Test
    void playerChunkUnloadDropsThatChunksSitesWithoutSendingRestore() {
        RandomGenerator r = rng(22);
        GridWorld w = GridWorld.solid(48);
        List<Outcome> outs = new ArrayList<>();
        TimedDisplay d = new TimedDisplay();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, r);
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        assertFalse(e.activeSites().isEmpty());
        int hidesBefore = d.hides;
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                e.dropChunkFor(PLAYER, W, cx, cz, 5);
                for (Site s : e.activeSites()) {
                    assertFalse(s.touchesChunk(W, cx, cz), "언로드한 청크의 자리가 남았다");
                }
            }
        }
        assertTrue(e.activeSites().isEmpty());
        assertEquals(hidesBefore, d.hides, "클라이언트가 버린 청크에 되돌리기 패킷을 보냈다");
        assertTrue(outs.stream().allMatch(o -> o.result() == Result.VOID));
    }

    @Test
    void serverChunkUnloadDropsEveryPlayersSites() {
        RandomGenerator r = rng(23);
        GridWorld w = GridWorld.solid(48);
        TimedDisplay d = new TimedDisplay();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
        for (int i = 0; i < 5; i++) {
            InvariantTest.sendChunks(e, new PlayerState(new UUID(9, i), "p" + i, W, 24, 24, 24, true), 0);
        }
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                e.dropChunk(W, cx, cz, 9);
            }
        }
        assertTrue(e.activeSites().isEmpty());
    }

    @Test
    void pairsHaveOneDecoyOneAndOnePlaceboSharingAPairId() {
        RandomGenerator r = rng(25);
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new TimedDisplay(), o -> { }, r);
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        List<Site> all = e.activeSites();
        assertFalse(all.isEmpty());
        for (Site s : all) {
            long same = all.stream().filter(o -> o.pairId == s.pairId).count();
            long decoys = all.stream().filter(o -> o.pairId == s.pairId && o.kind == SiteKind.DECOY).count();
            assertEquals(2, same);
            assertEquals(1, decoys);
        }
    }

    @Test
    void sendingTheSameChunkTwiceWithoutUnloadDoesNotDuplicateSites() {
        RandomGenerator r = rng(26);
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new TimedDisplay(), o -> { }, r);
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        int n = e.activeSites().size();
        InvariantTest.sendChunks(e, at(24, 24, 24), 5);
        assertEquals(n, e.activeSites().size());
    }

    @Test
    void windowExpiryJudgesMissButKeepsTheDecoyOnScreen() {
        RandomGenerator r = rng(27);
        GridWorld w = GridWorld.solid(48);
        List<Outcome> outs = new ArrayList<>();
        TimedDisplay d = new TimedDisplay();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, r);
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        int hides = d.hides;
        e.expire(1_000_000);
        assertTrue(outs.stream().allMatch(o -> o.result() == Result.MISS), "만료는 MISS여야 한다");
        assertFalse(outs.isEmpty());
        assertEquals(hides, d.hides, "시간이 지났다고 미끼를 화면에서 거두면 안 된다(사라지는 것이 단서가 된다)");
        assertFalse(e.activeSites().isEmpty(), "판정이 끝나도 노출 회수를 위해 추적은 남아야 한다");
    }
}

package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;

import static dev.anchormc.core.InvariantTest.PLAYER;
import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.at;
import static dev.anchormc.core.InvariantTest.params;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 재방문 일관성: (플레이어, 청크, 비밀 시드)로 결정적, 회수된 것은 다시 안 보냄, 한 번 판정된 쌍은 새 관측을 안 만듦. */
class RevisitTest {
    static final class Rec implements Display {
        final List<List<Voxel>> shows = new ArrayList<>();
        final List<List<Pos>> hides = new ArrayList<>();

        @Override
        public void show(UUID player, List<Voxel> voxels) {
            shows.add(new ArrayList<>(voxels));
        }

        @Override
        public void hide(UUID player, List<Pos> positions) {
            hides.add(new ArrayList<>(positions));
        }
    }

    private static List<Pos> flat(List<List<Voxel>> l) {
        List<Pos> out = new ArrayList<>();
        l.forEach(c -> c.forEach(v -> out.add(v.pos())));
        return out;
    }

    /** 사이트(종류별) 좌표 목록. */
    private static Map<SiteKind, List<Pos>> siteMap(DecoyEngine e) {
        Map<SiteKind, List<Pos>> m = new HashMap<>();
        for (Site s : e.activeSites()) {
            for (Voxel v : s.voxels) {
                m.computeIfAbsent(s.kind, k -> new ArrayList<>()).add(v.pos());
            }
        }
        m.values().forEach(l -> l.sort(java.util.Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)));
        return m;
    }

    private static void unloadAll(DecoyEngine e, long t) {
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                e.dropChunkFor(PLAYER, W, cx, cz, t);
            }
        }
    }

    @Test
    void sameChunkAgainGivesTheSamePlacesTheSameDecoysAndTheSameCoins() {
        GridWorld w = GridWorld.solid(48);
        Rec d = new Rec();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(1));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Map<SiteKind, List<Pos>> first = siteMap(e);
        List<Pos> firstDecoys = flat(d.shows);
        assertFalse(first.get(SiteKind.DECOY).isEmpty());
        for (int round = 1; round <= 3; round++) {
            unloadAll(e, round * 10);
            assertTrue(e.activeSites().isEmpty());
            d.shows.clear();
            InvariantTest.sendChunks(e, at(24 + round, 24, 24), round * 10 + 1); // 위치가 달라도 같다
            assertEquals(first, siteMap(e), "다시 받은 청크의 미끼·위약 자리나 동전이 달라졌다");
            List<Pos> again = flat(d.shows);
            assertEquals(firstDecoys.stream().sorted(java.util.Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).toList(),
                    again.stream().sorted(java.util.Comparator.comparingInt(Pos::x).thenComparingInt(Pos::y).thenComparingInt(Pos::z)).toList());
        }
    }

    @Test
    void differentPlayersAndDifferentSecretsGetDifferentPlaces() {
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e1 = new DecoyEngine(params(), name -> w, new Rec(), o -> { }, rng(1));
        DecoyEngine e2 = new DecoyEngine(params(), name -> w, new Rec(), o -> { }, rng(2));
        InvariantTest.sendChunks(e1, at(24, 24, 24), 0);
        InvariantTest.sendChunks(e2, at(24, 24, 24), 0);
        InvariantTest.sendChunks(e1, new PlayerState(new UUID(7, 7), "other", W, 24, 24, 24, true), 0);
        Map<SiteKind, List<Pos>> mineOnE1 = new HashMap<>(), otherOnE1 = new HashMap<>();
        for (Site s : e1.activeSites()) {
            (s.player.equals(PLAYER) ? mineOnE1 : otherOnE1).computeIfAbsent(s.kind, k -> new ArrayList<>()).add(s.pos);
        }
        assertNotEquals(mineOnE1.get(SiteKind.DECOY), otherOnE1.get(SiteKind.DECOY), "다른 플레이어에게 같은 자리가 갔다");
        Map<SiteKind, List<Pos>> onE2 = new HashMap<>();
        for (Site s : e2.activeSites()) {
            onE2.computeIfAbsent(s.kind, k -> new ArrayList<>()).add(s.pos);
        }
        assertNotEquals(mineOnE1.get(SiteKind.DECOY), onE2.get(SiteKind.DECOY), "다른 비밀 시드에서 같은 자리가 나왔다");
    }

    @Test
    void aRetiredDecoyIsNeverSentAgain() {
        GridWorld w = GridWorld.solid(48);
        Rec d = new Rec();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(3));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Site victim = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        Pos p = victim.pos;
        // 이웃이 깨져 노출된다(변경 전 알림): 이 쌍은 회수되고 월드도 바뀐다.
        e.onBlockChanging(p.offset(0, 1, 0), 5);
        w.set(p.x(), p.y() + 1, p.z(), GridWorld.AIR);
        assertFalse(victim.active());
        unloadAll(e, 6);
        d.shows.clear();
        InvariantTest.sendChunks(e, at(24, 24, 24), 7);
        assertFalse(flat(d.shows).contains(p), "회수된 미끼가 다시 나갔다");
        assertTrue(e.activeSites().stream().noneMatch(s -> s.pairId == victim.pairId), "회수된 쌍이 다시 추적에 올랐다");
    }

    @Test
    void aJudgedPairShowsItsDecoyAgainButMakesNoNewObservation() {
        GridWorld w = GridWorld.solid(48);
        Rec d = new Rec();
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, rng(4));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Site decoy = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        Pos c = decoy.pos;
        // 플레이어가 그 미끼 곁(가장 가까운 블록에서 2.7블록, 노클립 거리보다 멀고 반응 반경 안)으로 가서 반응(HIT)한다.
        Pos mn = decoy.voxels.stream().map(Voxel::pos).min(java.util.Comparator.comparingInt(Pos::x)).orElseThrow();
        double px = mn.x() + 0.5 - 2.7, py = mn.y() + 0.5, pz = mn.z() + 0.5;
        e.onMove(PLAYER, W, px, py, pz, 3);
        assertEquals(1, outs.stream().filter(o -> o.result() == Result.HIT && o.pairId() == decoy.pairId).count());
        unloadAll(e, 4);
        d.shows.clear();
        InvariantTest.sendChunks(e, at(24, 24, 24), 5);
        assertTrue(flat(d.shows).contains(c), "이미 판정된 쌍의 미끼도 (일관성을 위해) 다시 보여야 한다");
        Site again = e.activeSites().stream().filter(s -> s.pairId == decoy.pairId && s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        assertTrue(again.result() != null, "다시 받은 판정 완료 쌍이 새 관측이 될 수 있다");
        assertTrue(e.activeSites().stream().noneMatch(s -> s.pairId == decoy.pairId && s.kind == SiteKind.PLACEBO),
                "판정된 쌍의 위약이 새 관측으로 다시 올랐다");
        int n1 = outs.size();
        e.onMove(PLAYER, W, px, py, pz, 6);
        assertEquals(n1, outs.size(), "이미 쓰인 쌍이 다시 반응으로 세어졌다");
    }

    @Test
    void anUnjudgedPairDroppedByUnloadComesBackAsAFreshObservation() {
        GridWorld w = GridWorld.solid(48);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(params(), name -> w, new Rec(), outs::add, rng(5));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Map<SiteKind, List<Pos>> first = siteMap(e);
        unloadAll(e, 1);
        assertTrue(outs.stream().allMatch(o -> o.result() == Result.VOID));
        InvariantTest.sendChunks(e, at(24, 24, 24), 2);
        assertEquals(first, siteMap(e));
        assertTrue(e.activeSites().stream().allMatch(s -> s.result() == null), "언로드로만 빠진 쌍은 새 관측으로 돌아와야 한다");
    }

    @Test
    void rerollModeReproducesTheOldBehaviourOfNewPlacesOnEveryVisit() {
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new Rec(), o -> { }, rng(6));
        e.setConsistentRevisit(false);
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        Map<SiteKind, List<Pos>> first = siteMap(e);
        unloadAll(e, 1);
        InvariantTest.sendChunks(e, at(24, 24, 24), 2);
        assertNotEquals(first, siteMap(e), "대조군 모드에서는 재방문마다 새로 뽑혀야 한다");
    }

    @Test
    void prepareChunkGivesTheSameAnswerFromManyThreadsAtOnce() {
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new Rec(), o -> { }, rng(7));
        Map<String, List<Pos>> seen = new ConcurrentHashMap<>();
        IntStream.range(0, 800).parallel().forEach(i -> {
            int cx = i % 3, cz = (i / 3) % 3;
            ChunkPlan.Patch patch = e.prepareChunk(PLAYER, W, cx, cz, new ChunkOnlyView(w, cx, cz));
            List<Pos> got = patch.decoyVoxels().stream().map(Voxel::pos).toList();
            List<Pos> prev = seen.putIfAbsent(cx + "," + cz, got);
            if (prev != null) {
                assertEquals(prev, got, "같은 청크가 스레드마다 다르게 계산됐다");
            }
        });
        assertTrue(seen.values().stream().anyMatch(l -> !l.isEmpty()));
    }

    @Test
    void packetPathRefusesToSendWhereTheSentDataIsNotSealed() {
        // 이웃이 전부 공기인 월드: 봉인될 자리가 없다.
        GridWorld w = new GridWorld(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new Rec(), o -> { }, rng(8));
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                assertTrue(e.prepareChunk(PLAYER, W, cx, cz, new ChunkOnlyView(w, cx, cz)).decoyVoxels().isEmpty());
            }
        }
        // 한 번 계획된 쌍이라도, 다시 받을 때 보내는 데이터에서 봉인이 깨졌으면 영영 접는다.
        GridWorld solid = GridWorld.solid(48);
        DecoyEngine e2 = new DecoyEngine(params(), name -> solid, new Rec(), o -> { }, rng(9));
        ChunkPlan.Patch p1 = e2.prepareChunk(PLAYER, W, 1, 1, new ChunkOnlyView(solid, 1, 1));
        assertFalse(p1.decoyVoxels().isEmpty());
        Pos target = p1.decoyVoxels().get(0).pos();
        solid.set(target.x(), target.y() + 1, target.z(), GridWorld.AIR);
        ChunkPlan.Patch p2 = e2.prepareChunk(PLAYER, W, 1, 1, new ChunkOnlyView(solid, 1, 1));
        assertFalse(p2.decoyVoxels().contains(p1.decoyVoxels().get(0)), "봉인이 깨진 미끼가 다시 나갔다");
        solid.set(target.x(), target.y() + 1, target.z(), GridWorld.STONE); // 원래대로 돌려도
        ChunkPlan.Patch p3 = e2.prepareChunk(PLAYER, W, 1, 1, new ChunkOnlyView(solid, 1, 1));
        assertFalse(p3.pairs().contains(p1.pairs().get(0)), "한 번 접은 쌍이 되살아났다");
    }

    @Test
    void debugListsEveryPlannedSiteWithAStatus() {
        GridWorld w = GridWorld.solid(48);
        DecoyEngine e = new DecoyEngine(params(), name -> w, new Rec(), o -> { }, rng(10));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        List<DecoyEngine.SiteDebug> l = e.debugSites(PLAYER);
        assertFalse(l.isEmpty());
        assertTrue(l.stream().allMatch(s -> s.status().startsWith("활성")));
        assertEquals(l.size(), e.activeSites().size());
        Site victim = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        e.onBlockChanging(victim.pos.offset(0, 1, 0), 3);
        assertTrue(e.debugSites(PLAYER).stream().anyMatch(s -> s.status().startsWith("회수됨")));
        unloadAll(e, 4);
        assertTrue(e.debugSites(PLAYER).stream().anyMatch(s -> s.status().startsWith("대기")));
        e.forgetPlayer(PLAYER, 5);
        assertTrue(e.debugSites(PLAYER).isEmpty());
    }

    @Test
    void heightBalanceMakesAcceptedHeightsFollowTheSourceDistribution() {
        // 띠별 통과율이 다른 상황(띠0: 100%, 띠1: 50%, 띠2: 25%)에서 원본 높이는 균등이다. 보정 뒤 받아들인 높이도 균등해야 한다.
        YBalance yb = new YBalance(0, 23);
        RandomGenerator r = rng(11);
        double[] pass = {1.0, 0.5, 0.25};
        int[] accepted = new int[3];
        for (int i = 0; i < 400_000; i++) {
            int band = r.nextInt(3);
            int y = band * YBalance.BAND + r.nextInt(YBalance.BAND);
            boolean ok = r.nextDouble() < pass[band];
            yb.record(y, ok);
            if (ok && yb.accept(y, r)) {
                accepted[band]++;
            }
        }
        double n = accepted[0] + accepted[1] + accepted[2];
        for (int b = 0; b < 3; b++) {
            assertEquals(1.0 / 3, accepted[b] / n, 0.02, "띠 " + b + " 비율이 균등하지 않다");
        }
    }
}

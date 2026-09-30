package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static dev.anchormc.core.InvariantTest.PLAYER;
import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 미끼와 위약이 같은 코드 경로로 같은 판정을 받는지 확인한다. */
class PathIdentityTest {
    /** 종류를 뺀 판정 내용. */
    record Seen(Result result, long tick, Pos pos) {
    }

    static final class Rec implements ResponseTracker.Hooks {
        final List<Seen> outcomes = new ArrayList<>();
        final List<String> retired = new ArrayList<>();

        @Override
        public void outcome(Outcome o) {
            outcomes.add(new Seen(o.result(), o.tick(), o.pos()));
        }

        @Override
        public void retired(Site s, boolean restoreBlock, long tick) {
            retired.add(s.pos + "/" + restoreBlock + "/" + s.result());
        }
    }

    @Test
    void sameEventTraceGivesIdenticalOutcomesForDecoyAndPlacebo() {
        for (long seed = 0; seed < 300; seed++) {
            RandomGenerator r = rng(seed);
            Params p = InvariantTest.params();
            Rec a = new Rec(), b = new Rec();
            ResponseTracker decoy = new ResponseTracker(p, a);
            ResponseTracker placebo = new ResponseTracker(p, b);
            List<Pos> sites = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Pos pos = new Pos(W, r.nextInt(40), r.nextInt(40), r.nextInt(40));
                sites.add(pos);
                decoy.add(new Site(PLAYER, "t", SiteKind.DECOY, pos, Host.STONE, 0));
                placebo.add(new Site(PLAYER, "t", SiteKind.PLACEBO, pos, Host.STONE, 0));
            }
            double x = 20, y = 20, z = 20;
            for (long t = 1; t < 400; t++) {
                switch (r.nextInt(6)) {
                    case 0, 1 -> {
                        x += r.nextGaussian() * 3;
                        y += r.nextGaussian() * 3;
                        z += r.nextGaussian() * 3;
                        decoy.observePosition(PLAYER, W, x, y, z, t);
                        placebo.observePosition(PLAYER, W, x, y, z, t);
                    }
                    case 2 -> {
                        Pos blk = new Pos(W, (int) x + r.nextInt(5) - 2, (int) y + r.nextInt(5) - 2, (int) z + r.nextInt(5) - 2);
                        decoy.observeDig(PLAYER, blk, t);
                        placebo.observeDig(PLAYER, blk, t);
                        decoy.blockChanging(blk, t);
                        placebo.blockChanging(blk, t);
                    }
                    case 3 -> {
                        Pos n = sites.get(r.nextInt(sites.size())).offset(r.nextInt(3) - 1, 0, 0);
                        decoy.blockChanging(n, t);
                        placebo.blockChanging(n, t);
                    }
                    case 4 -> {
                        decoy.expire(t * 300);
                        placebo.expire(t * 300);
                    }
                    default -> {
                        decoy.observePosition(PLAYER, "other", x, y, z, t);
                        placebo.observePosition(PLAYER, "other", x, y, z, t);
                    }
                }
            }
            assertEquals(a.outcomes, b.outcomes, "seed " + seed);
            assertEquals(a.retired, b.retired, "seed " + seed);
        }
    }

    @Test
    void trackerNeverReadsSiteKind() throws Exception {
        // 판정 경로(ResponseTracker)의 소스에 kind 접근이 없음을 강제한다: Hooks로 넘길 때 Outcome을 만드는 한 줄뿐.
        String src = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/java/dev/anchormc/core/ResponseTracker.java"));
        long uses = src.lines().filter(l -> !l.trim().startsWith("*") && !l.trim().startsWith("//")).filter(l -> l.contains(".kind") || l.contains("SiteKind.")).count();
        assertEquals(1, uses, "ResponseTracker는 kind를 Outcome 생성에서만 넘겨야 한다");
    }

    @Test
    void coinFlipIsFairAndBothArmsUseTheSameDistribution() {
        RandomGenerator r = rng(1);
        GridWorld w = GridWorld.solid(64);
        int decoyFirst = 0, pairs = 0;
        double dDecoy = 0, dPlacebo = 0, distSq = 0;
        int nDecoy = 0, nPlacebo = 0;
        for (int i = 0; i < 1500; i++) {
            // 매번 새 엔진: 쿨다운·상한 영향 없이 쌍 하나씩.
            UUID id = new UUID(0, i);
            DecoyEngine e = new DecoyEngine(InvariantTest.params(), name -> w, new Display() {
                public void show(UUID p, java.util.List<Voxel> voxels) { }
                public void hide(UUID p, java.util.List<Pos> positions) { }
            }, o -> { }, r);
            e.onChunkSent(new PlayerState(id, "t", W, 32, 32, 32, true), 2, 2, 0);
            List<Site> s = e.activeSites();
            if (s.size() != 2) {
                continue;
            }
            pairs++;
            for (Site site : s) {
                double d = site.pos.distanceTo(32.5, 32.5, 32.5);
                distSq += d * d;
                if (site.kind == SiteKind.DECOY) {
                    dDecoy += d;
                    nDecoy++;
                } else {
                    dPlacebo += d;
                    nPlacebo++;
                }
            }
            // 쌍 안의 첫 자리가 미끼인 비율
            if (s.get(0).kind == SiteKind.DECOY) {
                decoyFirst++;
            }
        }
        assertTrue(pairs > 1400);
        assertEquals(pairs, nDecoy);
        assertEquals(pairs, nPlacebo);
        double frac = (double) decoyFirst / pairs;
        // 이항 표준편차 ≈ 0.013. 5σ 안.
        assertTrue(Math.abs(frac - 0.5) < 0.065, "동전이 치우쳤다: " + frac);
        double gap = Math.abs(dDecoy / nDecoy - dPlacebo / nPlacebo);
        // 두 평균의 차이는 표준오차 sqrt(2)·sd/sqrt(n) 정도로 흔들린다(뭉치 배치 뒤에는 거리 산포가 커서 고정 임계값 0.5는 1σ대였다).
        // 표준편차를 표본에서 구해 4σ 안에 있는지 본다.
        double mean = (dDecoy + dPlacebo) / (nDecoy + nPlacebo);
        double sd = Math.sqrt(Math.max(distSq / (nDecoy + nPlacebo) - mean * mean, 0));
        double se = Math.sqrt(2.0) * sd / Math.sqrt(pairs);
        assertTrue(gap < 4 * se, "미끼와 위약의 거리 분포가 다르다: 평균 차이 " + gap + ", 표준오차 " + se);
    }
}

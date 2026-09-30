package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 안전 불변식 1~4를 코드로 강제하는지 확인한다. */
class InvariantTest {
    static final String W = "w";
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static RandomGenerator rng(long seed) {
        return RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(seed);
    }

    static Params params() {
        // 작은 테스트 월드에 맞춘 값. 시간 창은 길게 둔다(만료가 아니라 회수를 시험한다).
        return new Params(3.0, 100_000, 60.0, 2.0, 0, 63, 6.0, 20.0, 3, 0, 300);
    }

    /** show/hide를 기록하고, 그 순간의 월드 상태를 검사한다. */
    static final class CheckingDisplay implements Display {
        final GridWorld world;
        final Set<Pos> shown = new HashSet<>();
        int shows, hides;
        /** hide 시점에 여전히 봉인돼 있으면(=노출 전 회수) true를 센다. */
        int hidesBeforeExposure;

        CheckingDisplay(GridWorld world) {
            this.world = world;
        }

        @Override
        public void show(UUID player, Pos pos, Host host) {
            // 불변식 1: 보내는 순간 여섯 면이 전부 막혀 있어야 한다.
            assertTrue(DecoyGuard.sealed(world, pos), "노출 가능한 좌표에 미끼를 보냈다: " + pos);
            assertTrue(shown.add(pos), "이미 보낸 자리에 또 보냈다");
            shows++;
        }

        @Override
        public void hide(UUID player, Pos pos) {
            assertTrue(shown.remove(pos), "보낸 적 없는 자리를 거뒀다(위약에 되돌리기 발생)");
            hides++;
            if (DecoyGuard.sealed(world, pos)) {
                hidesBeforeExposure++;
            }
        }
    }

    static PlayerState at(double x, double y, double z) {
        return new PlayerState(PLAYER, "tester", W, x, y, z, true);
    }

    // ---- 불변식 1 ----

    @Test
    void sealedRequiresAllSixFacesToBeStableOpaque() {
        int c = 5;
        for (byte bad : new byte[] {GridWorld.AIR, GridWorld.GLASS, GridWorld.SAND}) {
            for (int[] f : Pos.FACES) {
                GridWorld w = GridWorld.solid(11);
                assertTrue(DecoyGuard.sealed(w, new Pos(W, c, c, c)));
                w.set(c + f[0], c + f[1], c + f[2], bad);
                assertFalse(DecoyGuard.sealed(w, new Pos(W, c, c, c)),
                        "면 하나가 " + bad + "인데 봉인으로 판정");
            }
        }
    }

    @Test
    void notSealedWhenHostIsNotStoneOrNeighborIsUnloaded() {
        GridWorld w = GridWorld.solid(11);
        w.set(5, 5, 5, GridWorld.AIR);
        assertFalse(DecoyGuard.sealed(w, new Pos(W, 5, 5, 5)));
        // 월드 가장자리: 이웃이 범위 밖(로드 안 됨)
        assertFalse(DecoyGuard.sealed(w, new Pos(W, 0, 5, 5)));
        assertFalse(DecoyGuard.sealed(w, new Pos(W, 10, 10, 10)));
    }

    @Test
    void neverSendsToExposableCoordinatesInRandomWorlds() {
        int totalShows = 0;
        for (long seed = 0; seed < 40; seed++) {
            RandomGenerator r = rng(seed);
            GridWorld w = GridWorld.random(48, r, 0.25);
            CheckingDisplay d = new CheckingDisplay(w);
            DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
            for (int t = 0; t < 30; t++) {
                e.tick(at(24 + r.nextInt(8) - 4, 24 + r.nextInt(8) - 4, 24 + r.nextInt(8) - 4), t);
            }
            totalShows += d.shows;
        }
        assertTrue(totalShows > 100, "시험이 의미 있으려면 미끼가 충분히 나가야 한다: " + totalShows);
    }

    // ---- 불변식 2, 3 ----

    /** 어떤 순간에도 화면에 떠 있는 미끼는 여섯 면이 다 막혀 있다. */
    private static void assertNoVisibleDecoy(DecoyEngine e, CheckingDisplay d, GridWorld w) {
        for (Site s : e.activeSites()) {
            if (s.kind == SiteKind.DECOY) {
                assertTrue(DecoyGuard.sealed(w, s.pos), "화면에 보일 수 있는 미끼가 남아 있다: " + s.pos);
            }
        }
        for (Pos p : d.shown) {
            assertTrue(DecoyGuard.sealed(w, p), "클라이언트에 노출된 미끼: " + p);
        }
    }

    @Test
    void retractsDecoysBeforeNeighborsAreExposedByBreakOrExplosion() {
        for (long seed = 0; seed < 30; seed++) {
            RandomGenerator r = rng(seed);
            GridWorld w = GridWorld.solid(48);
            CheckingDisplay d = new CheckingDisplay(w);
            DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
            for (int t = 0; t < 10; t++) {
                e.tick(at(24, 24, 24), t);
            }
            assertTrue(d.shows > 0);
            // 미끼 이웃을 하나씩(때로는 한꺼번에 폭발로) 깬다. 항상 "변경 전 알림 → 변경" 순서.
            List<Site> decoys = new ArrayList<>();
            e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).forEach(decoys::add);
            for (Site s : decoys) {
                Pos p = s.pos;
                if (r.nextBoolean()) {
                    int[] f = Pos.FACES[r.nextInt(6)];
                    Pos n = p.offset(f[0], f[1], f[2]);
                    e.onBlockChanging(n, 100);
                    assertNoVisibleDecoy(e, d, w);
                    w.set(n.x(), n.y(), n.z(), GridWorld.AIR);
                } else {
                    List<Pos> boom = new ArrayList<>();
                    for (int dx = -2; dx <= 2; dx++) {
                        for (int dy = -2; dy <= 2; dy++) {
                            for (int dz = -2; dz <= 2; dz++) {
                                boom.add(p.offset(dx, dy, dz));
                            }
                        }
                    }
                    boom.forEach(b -> e.onBlockChanging(b, 100));
                    assertNoVisibleDecoy(e, d, w);
                    boom.forEach(b -> w.set(b.x(), b.y(), b.z(), GridWorld.AIR));
                }
                assertNoVisibleDecoy(e, d, w);
            }
            assertTrue(d.hides > 0);
            assertEquals(d.hides, d.hidesBeforeExposure, "회수는 전부 노출 전에 이뤄져야 한다");
        }
    }

    @Test
    void periodicVerifyRetractsAfterChangesThatFiredNoEvent() {
        RandomGenerator r = rng(7);
        GridWorld w = GridWorld.solid(48);
        CheckingDisplay d = new CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
        for (int t = 0; t < 10; t++) {
            e.tick(at(24, 24, 24), t);
        }
        Site victim = e.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        // 이벤트 없이 이웃이 사라진다(월드에딧류)
        w.set(victim.pos.x() + 1, victim.pos.y(), victim.pos.z(), GridWorld.AIR);
        e.verifyAll(50);
        assertTrue(e.activeSites().stream().noneMatch(s -> s == victim), "재검사가 노출된 미끼를 거두지 않았다");
        assertFalse(d.shown.contains(victim.pos));
    }

    @Test
    void randomizedEventStreamNeverLeavesVisibleDecoyOrShowsUnsealed() {
        for (long seed = 100; seed < 130; seed++) {
            RandomGenerator r = rng(seed);
            GridWorld w = GridWorld.random(48, r, 0.1);
            CheckingDisplay d = new CheckingDisplay(w);
            DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
            for (int t = 0; t < 400; t++) {
                e.tick(at(24 + r.nextInt(6), 24 + r.nextInt(6), 24 + r.nextInt(6)), t);
                // 무작위 블록 파괴(플레이어 또는 폭발): 반드시 변경 전에 알린다.
                for (int k = 0; k < 3; k++) {
                    int x = 8 + r.nextInt(32), y = 8 + r.nextInt(32), z = 8 + r.nextInt(32);
                    if (r.nextInt(4) == 0) {
                        e.onPlayerBreak(PLAYER, new Pos(W, x, y, z), t);
                    } else {
                        e.onBlockChanging(new Pos(W, x, y, z), t);
                    }
                    assertNoVisibleDecoy(e, d, w);
                    w.set(x, y, z, GridWorld.AIR);
                    assertNoVisibleDecoy(e, d, w);
                }
                e.expire(t);
            }
        }
    }

    @Test
    void retractedOrExposedDecoyIsExcludedFromEvidenceUnlessAlreadyResolved() {
        RandomGenerator r = rng(3);
        GridWorld w = GridWorld.solid(48);
        List<Outcome> outs = new ArrayList<>();
        CheckingDisplay d = new CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, r);
        for (int t = 0; t < 10; t++) {
            e.tick(at(24, 24, 24), t);
        }
        List<Site> all = new ArrayList<>(e.activeSites());
        for (Site s : all) {
            e.onBlockChanging(s.pos.offset(1, 0, 0), 20);
        }
        assertFalse(outs.isEmpty());
        // 먼 플레이어라 반응한 것이 없다: 전부 VOID(증거 제외)
        assertTrue(outs.stream().allMatch(o -> o.result() == Result.VOID), outs.toString());
        assertEquals(all.size(), outs.size());
    }

    // ---- 불변식 4 ----

    @Test
    void engineNeverMutatesTheWorld() {
        RandomGenerator r = rng(5);
        GridWorld w = GridWorld.random(48, r, 0.15);
        int before = w.checksum();
        CheckingDisplay d = new CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
        for (int t = 0; t < 200; t++) {
            e.tick(at(24, 24, 24), t);
            e.verifyAll(t);
            e.expire(t);
        }
        e.shutdown(999);
        assertEquals(before, w.checksum());
    }

    @Test
    void shutdownRestoresEveryShownDecoy() {
        RandomGenerator r = rng(9);
        GridWorld w = GridWorld.solid(48);
        CheckingDisplay d = new CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
        for (int t = 0; t < 10; t++) {
            e.tick(at(24, 24, 24), t);
        }
        assertFalse(d.shown.isEmpty());
        e.shutdown(50);
        assertTrue(d.shown.isEmpty(), "종료 후 클라이언트에 미끼가 남았다");
        assertTrue(e.activeSites().isEmpty());
    }

    @Test
    void ineligiblePlayerLosesAllSitesAndGetsNoNewOnes() {
        RandomGenerator r = rng(11);
        GridWorld w = GridWorld.solid(48);
        CheckingDisplay d = new CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, r);
        e.tick(at(24, 24, 24), 0);
        assertFalse(e.activeSites().isEmpty());
        e.tick(new PlayerState(PLAYER, "tester", W, 24, 24, 24, false), 20);
        assertTrue(e.activeSites().isEmpty());
        assertTrue(d.shown.isEmpty());
    }
}

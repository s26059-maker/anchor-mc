package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimRouteTest {
    private static final String W = "w";
    private static final Pos START = new Pos(W, 0, 40, 0);

    private static DecoyEngine.SiteDebug site(SiteKind k, int x, int y, int z, String status) {
        return new DecoyEngine.SiteDebug(k, new Pos(W, x, y, z), 1, status);
    }

    @Test
    void picksOnlyActiveDecoysNearestFirstNeverPlacebo() {
        List<DecoyEngine.SiteDebug> all = List.of(
                site(SiteKind.PLACEBO, 1, 40, 1, "활성"),
                site(SiteKind.DECOY, 20, 40, 0, "활성"),
                site(SiteKind.DECOY, 5, 40, 0, "활성"),
                site(SiteKind.DECOY, 3, 40, 0, "회수됨(다시 안 보냄) · 사유: x"),
                site(SiteKind.DECOY, 2, 40, 0, "판정 완료(HIT, 화면 유지)"),
                site(SiteKind.DECOY, 4, 40, 0, "대기(청크 밖: 다시 받으면 복원)"),
                new DecoyEngine.SiteDebug(SiteKind.DECOY, new Pos("nether", 1, 40, 0), 1, "활성"),
                site(SiteKind.DECOY, 500, 40, 0, "활성"),
                site(SiteKind.DECOY, 10, 40, 0, "활성"));
        var got = SimRoute.pickDecoys(all, START, 2);
        assertEquals(List.of(new Pos(W, 5, 40, 0), new Pos(W, 10, 40, 0)), got.stream().map(DecoyEngine.SiteDebug::pos).toList());
        var many = SimRoute.pickDecoys(all, START, 99);
        assertEquals(3, many.size());
        assertTrue(many.stream().allMatch(d -> d.kind() == SiteKind.DECOY));
        assertEquals(0, SimRoute.pickDecoys(all, START, 0).size());
    }

    @Test
    void placeboOnlyListPicksNothing() {
        var all = List.of(site(SiteKind.PLACEBO, 1, 40, 0, "활성"), site(SiteKind.PLACEBO, 2, 40, 0, "활성"));
        assertTrue(SimRoute.pickDecoys(all, START, 5).isEmpty());
    }

    @Test
    void everyStepIsExactlyOneBlockAndTargetEndsAdjacent() {
        Pos[] targets = {new Pos(W, 9, 40, 0), new Pos(W, -7, 33, 12), new Pos(W, 3, 52, -4), new Pos(W, 0, 40, 1), new Pos(W, 0, 40, 0)};
        for (Pos t : targets) {
            var acts = SimRoute.xray(START, List.of(t));
            Pos cur = START;
            for (int i = 0; i < acts.size() - 1; i++) {
                var a = acts.get(i);
                assertFalse(a.mine());
                assertEquals(1, SimRoute.manhattan(cur, a.moveTo()), "한 걸음은 1블록: " + t);
                assertEquals(List.of(a.moveTo(), a.moveTo().offset(0, 1, 0)), a.dig(), "발·머리 두 칸");
                cur = a.moveTo();
            }
            var last = acts.get(acts.size() - 1);
            assertTrue(last.mine());
            assertEquals(List.of(t), last.dig());
            assertTrue(SimRoute.reached(cur, t), "목표와 맞닿아야 한다: " + t);
            assertTrue(acts.size() <= SimRoute.MAX_STEPS + 1);
        }
    }

    @Test
    void multipleTargetsChainFromWhereTheLastOneEnded() {
        var a = new Pos(W, 6, 40, 0);
        var b = new Pos(W, 6, 40, 8);
        var acts = SimRoute.xray(START, List.of(a, b));
        assertEquals(2, acts.stream().filter(SimRoute.Action::mine).count());
        Pos cur = START;
        for (var act : acts) {
            if (act.moveTo() != null) {
                assertEquals(1, SimRoute.manhattan(cur, act.moveTo()));
                cur = act.moveTo();
            }
        }
        assertTrue(SimRoute.reached(cur, b));
    }

    @Test
    void straightBranchIsTwoHighOneBlockPerStep() {
        var acts = SimRoute.straight(START, 0, -1, 5);
        assertEquals(5, acts.size());
        for (int i = 0; i < 5; i++) {
            Pos p = new Pos(W, 0, 40, -(i + 1));
            assertEquals(p, acts.get(i).moveTo());
            assertEquals(List.of(p, p.offset(0, 1, 0)), acts.get(i).dig());
            assertFalse(acts.get(i).mine());
        }
        assertTrue(SimRoute.straight(START, 1, 0, 0).isEmpty());
        for (var m : SimRoute.class.getDeclaredMethods()) {
            if (m.getName().equals("straight")) {
                for (var p : m.getParameterTypes()) {
                    assertFalse(List.class.isAssignableFrom(p), "straight는 자리 정보를 받지 않는다");
                }
            }
        }
    }

    @Test
    void commandWiringKeepsSurvivalAndOpOnly() throws Exception {
        String plugin = Files.readString(Path.of("src/main/java/dev/anchormc/plugin/AnchorPlugin.java"));
        assertTrue(plugin.contains("case \"simtest\""));
        assertTrue(plugin.contains("!sender.hasPermission(\"anchor.admin\")"));
        String sim = Files.readString(Path.of("src/main/java/dev/anchormc/plugin/SimTest.java"));
        assertFalse(sim.contains("setGameMode"), "게임모드를 바꾸지 않는다(서바이벌 유지)");
        assertFalse(sim.contains("debugSites") && sim.contains("PLACEBO"), "러너가 위약을 직접 다루지 않는다");
        String yml = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertTrue(yml.contains("permission: anchor.admin"));
    }

    // ---- honest-branch ----

    @Test
    void branchPlanIsMainTunnelWithThreeBlockSpacedAlternatingSideBranchesAndOneBlockSteps() {
        int main = 9, len = 5;
        var acts = SimRoute.branch(START, 1, 0, main, len);
        Pos cur = START;
        int branches = 0, mainSteps = 0;
        Pos lastMain = START;
        int lastSide = 0;
        for (var a : acts) {
            assertFalse(a.mine());
            assertEquals(1, SimRoute.manhattan(cur, a.moveTo()), "한 걸음은 1블록");
            if (a.moveTo().z() == lastMain.z() && a.moveTo().x() == lastMain.x() + 1 && a.moveTo().z() == START.z()) {
                mainSteps++;
                lastMain = a.moveTo();
                assertEquals(List.of(a.moveTo(), a.moveTo().offset(0, 1, 0)), a.dig());
            } else if (a.moveTo().z() != START.z() && !a.dig().isEmpty()) {
                int side = Integer.signum(a.moveTo().z() - START.z());
                if (lastSide != side) {
                    branches++;
                    assertTrue(lastSide == 0 || lastSide == -side, "곁가지는 좌우를 번갈아 간다");
                    lastSide = side;
                }
                assertEquals(lastMain.x(), a.moveTo().x(), "곁가지는 본갱도에 수직");
            }
            cur = a.moveTo();
        }
        assertEquals(main, mainSteps);
        assertEquals(main / SimRoute.BRANCH_SPACING, branches, "본갱도 3블록마다 곁가지 하나");
        assertEquals(new Pos(W, START.x() + main, START.y(), START.z()), cur, "마지막은 본갱도 위");
        // 곁가지로 나갔다 온 길은 이미 판 공기라 부수지 않는다.
        assertEquals(branches * len, acts.stream().filter(a -> a.moveTo().z() != START.z() && !a.dig().isEmpty()).count());
        assertEquals(branches * len, acts.stream().filter(a -> a.dig().isEmpty()).count());
        for (var m : SimRoute.class.getDeclaredMethods()) {
            if (m.getName().equals("branch")) {
                for (var t : m.getParameterTypes()) {
                    assertFalse(List.class.isAssignableFrom(t), "branch는 자리 정보를 받지 않는다");
                }
            }
        }
    }

    private static final class Fake implements SimRoute.Terrain {
        final java.util.Set<Pos> ores = new java.util.HashSet<>();
        final java.util.Set<Pos> air = new java.util.HashSet<>();

        @Override
        public boolean isDiamondOre(Pos p) {
            return ores.contains(p);
        }

        @Override
        public boolean isAir(Pos p) {
            return air.contains(p);
        }
    }

    @Test
    void onlyAirExposedNearbyRealOresAreChosenNearestFirstAndSkipListIsHonored() {
        Fake t = new Fake();
        Pos buried = new Pos(W, 2, 40, 0), near = new Pos(W, 3, 40, 1), far = new Pos(W, 5, 40, 3), tooFar = new Pos(W, 6, 40, 6);
        t.ores.addAll(List.of(buried, near, far, tooFar));
        assertNull(SimRoute.nearestExposedOre(t, START, java.util.Set.of()), "공기에 안 닿은 광석은 안 보인다");
        t.air.add(far.offset(0, 1, 0));
        t.air.add(tooFar.offset(1, 0, 0));
        assertEquals(far, SimRoute.nearestExposedOre(t, START, java.util.Set.of()));
        t.air.add(near.offset(1, 0, 0));
        assertEquals(near, SimRoute.nearestExposedOre(t, START, java.util.Set.of()), "더 가까운 보이는 광석이 먼저");
        assertEquals(far, SimRoute.nearestExposedOre(t, START, java.util.Set.of(near)), "이미 시도한 광석은 건너뛴다");
        assertNull(SimRoute.nearestExposedOre(t, START, java.util.Set.of(near, far)), "맨해튼 거리 상한(DETOUR_MAX) 밖은 안 간다");
    }

    @Test
    void detourMinesTheOreThenWalksBackToExactlyWhereItStarted() {
        Pos ore = new Pos(W, 4, 41, -3);
        var acts = SimRoute.detour(START, ore);
        Pos cur = START;
        int mines = 0;
        for (var a : acts) {
            if (a.moveTo() != null) {
                assertEquals(1, SimRoute.manhattan(cur, a.moveTo()), "한 걸음은 1블록");
                cur = a.moveTo();
            } else {
                assertTrue(a.mine());
                assertEquals(List.of(ore), a.dig());
                mines++;
            }
        }
        assertEquals(1, mines);
        assertEquals(START, cur, "원래 자리로 돌아와야 본 경로가 이어진다");
    }

    // ---- 액체 막기 ----

    private static java.util.function.Predicate<Pos> liquids(Pos... ps) {
        java.util.Set<Pos> set = new java.util.HashSet<>(List.of(ps));
        return set::contains;
    }

    @Test
    void sealPlanFillsLiquidAroundThePathButOpensTheCellsThePlayerWillStandIn() {
        Pos cur = new Pos(W, 0, 40, 0), dest = new Pos(W, 1, 40, 0);
        Pos ahead = new Pos(W, 2, 40, 0), aboveHead = new Pos(W, 1, 42, 0), side = new Pos(W, 1, 40, 2), floor = new Pos(W, 1, 38, 0);
        var plan = SimRoute.sealPlan(liquids(dest, dest.offset(0, 1, 0), cur, ahead, aboveHead, side, floor), cur, dest);
        java.util.Map<Pos, Boolean> byPos = new java.util.HashMap<>();
        plan.forEach(s -> byPos.put(s.pos(), s.air()));
        assertEquals(7, plan.size());
        assertTrue(byPos.get(dest) && byPos.get(dest.offset(0, 1, 0)) && byPos.get(cur), "서 있을 칸은 공기로(돌로 막으면 몸이 박힌다)");
        assertFalse(byPos.get(ahead), "경로 앞의 액체는 돌로 메운다");
        assertFalse(byPos.get(aboveHead), "머리 위의 액체도 막는다");
        assertFalse(byPos.get(side), "옆의 액체도 막는다");
        assertFalse(byPos.get(floor), "바닥 아래의 액체도 막는다");
    }

    @Test
    void sealPlanIgnoresLiquidOutsideTheBoxAndReturnsNothingWhenDry() {
        Pos cur = new Pos(W, 0, 40, 0), dest = new Pos(W, 1, 40, 0);
        assertTrue(SimRoute.sealPlan(liquids(), cur, dest).isEmpty());
        Pos farSide = dest.offset(SimRoute.SEAL_H + 1, 0, 0), tooHigh = dest.offset(0, SimRoute.SEAL_UP + 1, 0), tooLow = dest.offset(0, -SimRoute.SEAL_DOWN - 1, 0);
        assertTrue(SimRoute.sealPlan(liquids(farSide, tooHigh, tooLow), cur, dest).isEmpty());
        Pos edgeHigh = dest.offset(0, SimRoute.SEAL_UP, 0), edgeSide = dest.offset(0, 0, -SimRoute.SEAL_H);
        assertEquals(2, SimRoute.sealPlan(liquids(edgeHigh, edgeSide), cur, dest).size());
    }

    @Test
    void sealPlanForAMineActionAnchorsOnTheCurrentFeet() {
        Pos cur = new Pos(W, 5, 40, 5);
        var plan = SimRoute.sealPlan(liquids(cur.offset(1, 0, 0), cur.offset(0, 1, 0)), cur, null);
        assertEquals(2, plan.size());
        assertTrue(plan.stream().filter(s -> s.pos().equals(cur.offset(0, 1, 0))).findFirst().orElseThrow().air(), "머리 칸은 서 있는 곳");
        assertFalse(plan.stream().filter(s -> s.pos().equals(cur.offset(1, 0, 0))).findFirst().orElseThrow().air());
    }

    @Test
    void simTestNoLongerStopsOnLiquidAndSealsBeforeEveryAction() throws Exception {
        String sim = Files.readString(Path.of("src/main/java/dev/anchormc/plugin/SimTest.java"));
        assertFalse(sim.contains("b.isLiquid() ||"), "액체를 만나면 멈추던 검사가 남아 있다");
        assertTrue(sim.contains("sealLiquids(p, a);"), "걸음마다 액체를 먼저 막아야 한다");
        assertTrue(sim.indexOf("sealLiquids(p, a);") < sim.indexOf("p.breakBlock(b)"), "블록을 부수기 전에 막는다");
        assertTrue(sim.contains("setType(m, false)"), "블록 물리·이벤트를 일으키지 않는다");
    }
}

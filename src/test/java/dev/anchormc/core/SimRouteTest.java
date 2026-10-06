package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}

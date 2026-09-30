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

/** 자격 규칙(관전자 디버그 옵션), 자격 회복 시 미끼 복구, 관전자 반응의 증거 제외. */
class RecoveryTest {
    @Test
    void eligibilityMatrix() {
        assertTrue(Eligibility.eligible(true, false, false, true, false));   // 서바이벌·모험
        assertFalse(Eligibility.eligible(false, false, false, true, false)); // 크리에이티브
        assertFalse(Eligibility.eligible(false, true, false, true, false));  // 관전자, 옵션 꺼짐(기본)
        assertTrue(Eligibility.eligible(false, true, true, true, false));    // 관전자, 옵션 켜짐
        assertFalse(Eligibility.eligible(false, false, true, true, false));  // 옵션은 크리에이티브를 허용하지 않는다
        assertFalse(Eligibility.eligible(true, false, true, true, true));    // 죽음
        assertFalse(Eligibility.eligible(false, true, true, false, false));  // 접속 끊김
    }

    @Test
    void defaultConfigKeepsSpectatorOff() throws Exception {
        String cfg = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/config.yml"));
        assertTrue(cfg.lines().anyMatch(l -> l.strip().equals("allow-spectator: false")), "debug.allow-spectator 기본값은 false여야 한다");
        assertTrue(cfg.contains("debug:"));
    }

    private static PlayerState state(boolean eligible, boolean spectator) {
        return new PlayerState(PLAYER, "tester", W, 24, 24, 24, eligible, spectator);
    }

    @Test
    void regainedEligibilityShowsSuspendedDecoysAgainWithoutResendingChunks() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(41));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        int before = e.activeSites().size();
        assertTrue(before > 0);

        e.tick(state(false, false), 5); // 죽었다
        assertTrue(e.activeSites().isEmpty());
        int showsAfterHide = d.shows;

        e.tick(state(true, false), 6); // 리스폰: 청크 재전송 없이
        assertEquals(before, e.activeSites().size(), "자격을 되찾았는데 자리가 돌아오지 않았다");
        assertTrue(d.shows > showsAfterHide, "미끼가 화면에 다시 나가지 않았다");
        assertEquals(before / 2, e.restoredPairs());
        assertEquals(0, e.debugSites(PLAYER).stream().filter(s -> s.status().startsWith("회수됨")).count());

        int shows = d.shows;
        e.tick(state(true, false), 7); // 한 번만 복구한다
        assertEquals(shows, d.shows);
    }

    @Test
    void recoveryRechecksInvariantOneAndNeverShowsAnExposedDecoy() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w); // show마다 그 순간의 월드에서 봉인을 검사한다
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(42));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        List<Site> before = new ArrayList<>(e.activeSites());
        e.tick(state(false, false), 5);

        // 죽어 있는 사이 이벤트 없이 한 미끼의 이웃이 뚫렸다.
        Site victim = before.stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        Pos vp = victim.voxels.get(0).pos();
        w.set(vp.x(), vp.y() + 1, vp.z(), GridWorld.AIR);
        w.set(vp.x(), vp.y() - 1, vp.z(), GridWorld.AIR);

        e.tick(state(true, false), 6); // CheckingDisplay가 노출된 미끼를 보내면 여기서 실패한다
        assertTrue(e.activeSites().stream().noneMatch(s -> s.pairId == victim.pairId), "뚫린 쌍이 복구됐다");
        assertTrue(e.debugSites(PLAYER).stream().anyMatch(s -> s.status().startsWith("회수됨")));
        assertTrue(e.retireCounts().get(RetireCause.REGISTER_UNSEALED) >= 1);
        assertFalse(e.activeSites().isEmpty(), "멀쩡한 쌍까지 못 돌아왔다");
    }

    @Test
    void chunkDroppedWhileSuspendedIsNotRestored() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(43));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        e.tick(state(false, false), 5);
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                e.dropChunkFor(PLAYER, W, cx, cz, 6); // 클라이언트가 청크를 전부 버렸다
            }
        }
        int shows = d.shows;
        e.tick(state(true, false), 7);
        assertEquals(shows, d.shows, "클라이언트에 없는 청크에 미끼를 보냈다");
        assertTrue(e.activeSites().isEmpty());
        // 청크를 다시 받으면 같은 미끼가 돌아온다(재전송 경로).
        InvariantTest.sendChunks(e, at(24, 24, 24), 100);
        assertFalse(e.activeSites().isEmpty());
    }

    @Test
    void worldChangeForgetsSuspendedPairs() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(44));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        e.tick(state(false, false), 5);
        e.dropPlayer(PLAYER, 6, false);
        int shows = d.shows;
        e.tick(state(true, false), 7);
        assertEquals(shows, d.shows);
    }

    /** 미끼 근처(반응 반경 안, 밀착 거리 밖)로 다가가 반응 판정이 나는지 본다. */
    private static List<Outcome> approach(boolean spectator, long seed) {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        List<Outcome> outs = new ArrayList<>();
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, outs::add, rng(seed));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        e.tick(state(true, spectator), 1);
        Site s = e.activeSites().stream().filter(x -> x.kind == SiteKind.DECOY).findFirst().orElseThrow();
        Pos p = s.pos;
        for (double dx = 2.1; dx < 3.0; dx += 0.05) {
            double dist = s.distanceTo(p.x() + 0.5 + dx, p.y() + 0.5, p.z() + 0.5);
            if (dist > 2.05 && dist <= 2.95) {
                e.onMove(PLAYER, W, p.x() + 0.5 + dx, p.y() + 0.5, p.z() + 0.5, 2);
                break;
            }
        }
        return outs;
    }

    @Test
    void spectatorReactionsNeverReachEvidenceButNormalOnesDo() {
        assertTrue(approach(false, 45).stream().anyMatch(o -> o.result() == Result.HIT), "대조군: 서바이벌의 반응은 판정돼야 한다");
        assertTrue(approach(true, 45).isEmpty(), "관전자 상태의 반응이 증거로 갔다");
    }

    @Test
    void spectatorKeepsDecoysOnScreen() {
        GridWorld w = GridWorld.solid(48);
        InvariantTest.CheckingDisplay d = new InvariantTest.CheckingDisplay(w);
        DecoyEngine e = new DecoyEngine(params(), name -> w, d, o -> { }, rng(46));
        InvariantTest.sendChunks(e, at(24, 24, 24), 0);
        int n = e.activeSites().size();
        for (int t = 1; t < 50; t++) {
            e.tick(state(true, true), t); // 옵션이 켜져 자격이 유지된 관전자
        }
        assertEquals(n, e.activeSites().size());
        assertEquals(0, d.hides);
    }
}

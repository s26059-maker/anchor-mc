package dev.anchormc.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /anchor reset의 증거 쪽: 누적 기록·저장소·p0 합계·쌍 대기 상태를 지우고 다른 계정은 건드리지 않는다. */
class ResetTest {
    @TempDir
    Path dir;

    private static EvidenceEngine engine(EvidenceStore s) {
        return new EvidenceEngine(EvidenceParams.defaults(), s, () -> 1L);
    }

    @Test
    void resetClearsTheAccountButLeavesOthersAlone() {
        EvidenceEngine e = engine(new MemoryStore());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (long pair = 1; pair <= 5; pair++) {
            e.observe(a, "alice", true, true, pair);
            e.observe(a, "alice", false, false, pair);
            e.observe(b, "bob", true, false, 100 + pair);
            e.observe(b, "bob", false, true, 100 + pair);
        }
        var old = e.resetPlayer(a, List.of());
        assertEquals(5, old.decoyN());
        assertEquals(5, old.decoyHits());
        assertNull(e.view("alice"));
        assertNull(e.idOf("alice"));
        assertEquals(b, e.idOf("BOB"));
        assertEquals(5, e.view("bob").placeboHits());
        assertNull(e.resetPlayer(a, List.of()), "이미 지운 계정");
    }

    @Test
    void resetRemovesTheAccountsPlaceboFromTheGlobalP0() {
        EvidenceEngine e = engine(new MemoryStore());
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        for (int i = 0; i < 30; i++) {
            e.observe(a, "alice", false, true);
        }
        for (int i = 0; i < 70; i++) {
            e.observe(b, "bob", false, false);
        }
        assertEquals(30, e.stats().placeboHits());
        e.resetPlayer(a, List.of());
        assertEquals(70, e.stats().placeboN());
        assertEquals(0, e.stats().placeboHits());
    }

    @Test
    void resetPersistsInSqliteAndSurvivesRestart() {
        Path file = dir.resolve("r.db");
        UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        try (SqliteStore s = new SqliteStore(file)) {
            EvidenceEngine e = engine(s);
            for (int i = 0; i < 40; i++) {
                e.observe(a, "alice", true, true);
                e.observe(a, "alice", false, false);
            }
            e.observe(b, "bob", false, true);
            assertNotNull(s.load(a));
            e.resetPlayer(a, List.of());
            assertNull(s.load(a));
            assertEquals(0, s.confirmedCount());
        }
        try (SqliteStore s = new SqliteStore(file)) { // 다시 열어도 없다
            assertNull(s.load(a));
            assertNull(s.findByName("alice"));
            assertNotNull(s.load(b));
            assertEquals(1, engine(s).stats().placeboN());
        }
    }

    @Test
    void resetOfAnAccountNotInMemoryStillReadsItFromTheStoreToFixTotals() {
        MemoryStore m = new MemoryStore();
        UUID a = UUID.randomUUID();
        EvidenceEngine first = engine(m);
        for (int i = 0; i < 20; i++) {
            first.observe(a, "alice", false, i % 2 == 0);
        }
        EvidenceEngine second = engine(m); // 재시작: 캐시가 비어 있다
        assertEquals(a, second.idOf("alice"));
        var old = second.resetPlayer(a, List.of());
        assertEquals(20, old.placeboN());
        assertEquals(0, second.stats().placeboN());
        assertNull(m.load(a));
    }

    @Test
    void asyncDeleteIsOrderedAfterPendingSaves() {
        Path file = dir.resolve("a.db");
        UUID id = UUID.randomUUID();
        AsyncStore a = new AsyncStore(new SqliteStore(file), t -> { throw new AssertionError(t); });
        EvidenceEngine e = engine(a);
        for (int i = 0; i < 200; i++) {
            e.observe(id, "x", i % 2 == 0, i % 3 == 0);
        }
        e.resetPlayer(id, List.of()); // 저장이 아직 줄 서 있어도 삭제가 그 뒤에 와야 한다
        a.close();
        try (SqliteStore s = new SqliteStore(file)) {
            assertNull(s.load(id), "지운 기록이 되살아났다");
        }
    }

    @Test
    void resetForgetsPendingPairsAndFirstReactionSoTheSamePairCountsFresh() {
        EvidenceEngine e = engine(new MemoryStore());
        UUID id = UUID.randomUUID();
        e.observe(id, "a", true, true, 7); // 미끼가 먼저 반응, 짝(위약)은 아직 판정 전
        assertEquals(1, e.pendingPairs());
        assertEquals(1, e.view("a").firstDecoy());
        e.resetPlayer(id, List.of());
        assertEquals(0, e.pendingPairs(), "대기 중이던 쌍이 남았다");
        // 같은 쌍 번호가 다시 계획돼도(시드가 같다) 처음부터 센다: 이전 쌍의 짝으로 잘못 맞춰지지 않고 먼저 반응도 다시 센다.
        e.observe(id, "a", false, false, 7);
        var v = e.view("a");
        assertEquals(0, v.firstDecoy());
        assertEquals(0, v.pairDecoyOnly() + v.pairPlaceboOnly() + v.pairBoth() + v.pairNeither());
        e.observe(id, "a", true, true, 7);
        v = e.view("a");
        assertEquals(1, v.pairDecoyOnly());
        assertEquals(1, v.firstDecoy());
    }

    @Test
    void aConfirmedAccountCanBeResetAndStartsUnconfirmed() {
        EvidenceEngine e = engine(new MemoryStore());
        UUID id = UUID.randomUUID();
        EvidenceEngine.Confirmation c = null;
        for (long pair = 1; pair <= 200 && c == null; pair++) {
            e.observe(id, "cheat", false, false, pair);
            c = e.observe(id, "cheat", true, true, pair);
        }
        assertNotNull(c, "테스트 자료가 확정에 못 미친다");
        assertTrue(e.view("cheat").confirmed());
        e.resetPlayer(id, List.of());
        assertEquals(0, e.stats().confirmedAccounts());
        e.observe(id, "cheat", true, false, 1);
        assertFalse(e.view("cheat").confirmed());
        assertEquals(1, e.view("cheat").decoyN());
    }
}

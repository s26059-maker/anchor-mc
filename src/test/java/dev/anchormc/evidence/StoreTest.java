package dev.anchormc.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StoreTest {
    @TempDir
    Path dir;

    @Test
    void sqliteRoundTripKeepsEverythingIncludingLogs() {
        Path file = dir.resolve("t.db");
        UUID id = UUID.randomUUID();
        try (SqliteStore s = new SqliteStore(file)) {
            AccountRecord r = new AccountRecord(id, "Steve");
            Mixture.observe(r.logs, true, 0.05);
            Mixture.observe(r.logs, false, 0.05);
            r.decoyN = 2;
            r.decoyHits = 1;
            r.placeboN = 7;
            r.placeboHits = 3;
            r.confirmedAt = 99;
            s.save(r);
            r.placeboN = 8; // 같은 uuid 덮어쓰기
            s.save(r);
        }
        try (SqliteStore s = new SqliteStore(file)) { // 다시 열어도 남아 있다
            AccountRecord r = s.load(id);
            assertNotNull(r);
            assertEquals("Steve", r.name);
            assertEquals(8, r.placeboN);
            assertEquals(99, r.confirmedAt);
            double[] expect = Mixture.newLogs();
            Mixture.observe(expect, true, 0.05);
            Mixture.observe(expect, false, 0.05);
            assertArrayEquals(expect, r.logs, 0.0);
            assertEquals("Steve", s.findByName("sTeVe").name);
            assertNull(s.findByName("alex"));
            assertArrayEquals(new long[] {8, 3}, s.placeboTotals());
            assertEquals(1, s.confirmedCount());
        }
    }

    @Test
    void asyncStoreFlushesOnClose() {
        Path file = dir.resolve("a.db");
        UUID id = UUID.randomUUID();
        AsyncStore a = new AsyncStore(new SqliteStore(file), t -> { throw new AssertionError(t); });
        EvidenceEngine e = new EvidenceEngine(EvidenceParams.defaults(), a, () -> 1L);
        for (int i = 0; i < 50; i++) {
            e.observe(id, "x", i % 2 == 0, i % 5 == 0);
        }
        a.close();
        try (SqliteStore s = new SqliteStore(file)) {
            AccountRecord r = s.load(id);
            assertEquals(25, r.decoyN);
            assertEquals(25, r.placeboN);
        }
    }

    @Test
    void engineResumesFromStore() {
        MemoryStore m = new MemoryStore();
        UUID id = UUID.randomUUID();
        EvidenceEngine e1 = new EvidenceEngine(new EvidenceParams(1e-9, 2.0, 10), m, () -> 1L);
        for (int i = 0; i < 100; i++) {
            e1.observe(id, "x", false, i % 10 == 0);
        }
        double p0 = e1.currentP0();
        EvidenceEngine e2 = new EvidenceEngine(new EvidenceParams(1e-9, 2.0, 10), m, () -> 1L);
        assertEquals(p0, e2.currentP0(), 1e-12);
        assertTrue(e2.view("x") != null && e2.view("x").placeboN() == 100);
    }
}

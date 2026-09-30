package dev.anchormc.evidence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 쌍 정확 검정: 짝 맞추기, 타당성(상관이 있어도), 검출력, 저장소. */
class PairedEvidenceTest {
    static RandomGenerator rng(long seed) {
        return RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(seed);
    }

    private static EvidenceEngine engine(EvidenceParams p) {
        return new EvidenceEngine(p, new MemoryStore(), () -> 1L);
    }

    @Test
    void countsFourKindsOfPairsAndOnlyDiscordantOnesMoveTheEvalue() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        // 쌍 1: 미끼만 반응(미끼 먼저 판정)
        e.observe(id, "a", true, true, 1);
        e.observe(id, "a", false, false, 1);
        // 쌍 2: 위약만 반응(위약 먼저 판정)
        e.observe(id, "a", false, true, 2);
        e.observe(id, "a", true, false, 2);
        // 쌍 3: 둘 다, 쌍 4: 둘 다 안 함
        e.observe(id, "a", true, true, 3);
        e.observe(id, "a", false, true, 3);
        e.observe(id, "a", true, false, 4);
        e.observe(id, "a", false, false, 4);
        var v = e.view("a");
        assertEquals(1, v.pairDecoyOnly());
        assertEquals(1, v.pairPlaceboOnly());
        assertEquals(1, v.pairBoth());
        assertEquals(1, v.pairNeither());
        // 미끼만 1, 위약만 1: 어느 대안 q에서도 q(1-q)/0.25 ≤ 1 이라 증거는 1 미만(대칭이라 더 늘 수 없다).
        assertTrue(v.log10EPaired() < 0 && v.log10EPaired() > -1, "" + v.log10EPaired());
        assertEquals(0, e.pendingPairs());
    }

    @Test
    void discordantOrderOnlyChangesNothingButEvidenceGrowsWithDecoyOnlyPairs() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        double last = 0;
        for (long pair = 1; pair <= 20; pair++) {
            if (pair % 2 == 0) { // 미끼만 반응, 판정 순서는 번갈아
                e.observe(id, "a", true, true, pair);
                e.observe(id, "a", false, false, pair);
            } else {
                e.observe(id, "a", false, false, pair);
                e.observe(id, "a", true, true, pair);
            }
            double now = e.view("a").log10EPaired();
            assertTrue(now > last, "미끼만 반응한 쌍이 늘었는데 증거가 안 늘었다");
            last = now;
        }
    }

    @Test
    void voidedPairIsDroppedWhicheverSideVoidsFirst() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        // 한쪽이 먼저 판정되고 다른 쪽이 VOID
        e.observe(id, "a", true, true, 1);
        e.voidPair(1);
        // 한쪽이 먼저 VOID, 다른 쪽이 나중에 판정
        e.voidPair(2);
        e.observe(id, "a", false, true, 2);
        // 둘 다 VOID
        e.voidPair(3);
        e.voidPair(3);
        var v = e.view("a");
        assertEquals(0, v.pairDecoyOnly() + v.pairPlaceboOnly() + v.pairBoth() + v.pairNeither());
        assertEquals(0, e.pendingPairs(), "짝을 못 찾은 쌍이 메모리에 남았다");
        // 혼합 e-value 쪽은 판정된 관측을 그대로 센다
        assertEquals(1, v.decoyHits());
    }

    /**
     * 정직 계정인데 같은 쌍의 두 자리 반응이 강하게 상관돼 있고(같은 동굴을 지나감), 계정마다 반응률이 크게 다르다.
     * 동전이 독립이기만 하면 쌍 정확 e-value는 P(언젠가 E ≥ x) ≤ 1/x 를 지킨다.
     */
    @Test
    void honestWithStrongCorrelationAndHeterogeneityStaysBelowOneOverX() {
        RandomGenerator r = rng(61);
        int accounts = 6000, pairs = 300;
        double[] xs = {5, 20, 100};
        int[] crossed = new int[xs.length];
        for (int a = 0; a < accounts; a++) {
            double base = 0.01 + 0.10 * r.nextDouble();   // 계정마다 다른 기저 반응률
            double shared = r.nextDouble() < 0.5 ? 0.6 : 0.0; // 같은 쌍이 같이 반응하는 경향(상관)
            double[] logs = Mixture.newPairedLogs();
            double mx = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < pairs; i++) {
                boolean both = r.nextDouble() < base * shared;
                boolean ya = both || r.nextDouble() < base;
                boolean yb = both || r.nextDouble() < base;
                boolean aIsDecoy = r.nextBoolean(); // 동전
                boolean decoyHit = aIsDecoy ? ya : yb, placeboHit = aIsDecoy ? yb : ya;
                if (decoyHit != placeboHit) {
                    Mixture.observePaired(logs, decoyHit);
                    mx = Math.max(mx, Mixture.logE(logs));
                }
            }
            for (int k = 0; k < xs.length; k++) {
                if (mx >= Math.log(xs[k])) {
                    crossed[k]++;
                }
            }
        }
        for (int k = 0; k < xs.length; k++) {
            double bound = 1 / xs[k];
            double sd = Math.sqrt(bound * (1 - bound) / accounts);
            assertTrue((double) crossed[k] / accounts <= bound + 3 * sd, "x=" + xs[k] + " 관측 " + (double) crossed[k] / accounts);
        }
    }

    @Test
    void cheaterWhoTakesDecoysMoreThanPlaceboIsConfirmed() {
        RandomGenerator r = rng(62);
        int runs = 300, total = 0;
        for (int a = 0; a < runs; a++) {
            double[] logs = Mixture.newPairedLogs();
            int n = 0;
            while (Mixture.logE(logs) < Math.log(1e9) && n < 2000) {
                boolean decoyHit = r.nextDouble() < 0.8, placeboHit = r.nextDouble() < 0.04;
                if (decoyHit != placeboHit) {
                    Mixture.observePaired(logs, decoyHit);
                }
                n++;
            }
            assertTrue(n < 2000);
            total += n;
        }
        double mean = (double) total / runs;
        assertTrue(mean < 60, "쌍 수 평균 " + mean);
    }

    @Test
    void ruleBothNeedsBothEvidences() {
        EvidenceParams both = new EvidenceParams(1e-6, 2.0, 100, 1e-2, EvidenceParams.Rule.BOTH);
        EvidenceEngine e = engine(both);
        for (int i = 0; i < 500; i++) {
            e.observe(UUID.randomUUID(), "h" + i, false, i % 20 == 0);
        }
        UUID id = UUID.randomUUID();
        int confirmations = 0;
        // 미끼만 계속 반응하지만 쌍 번호가 없으면(쌍 검정 증거 없음) 혼합만 커진다: BOTH는 확정하지 않는다.
        for (int i = 0; i < 40; i++) {
            if (e.observe(id, "cheat", true, true) != null) {
                confirmations++;
            }
        }
        assertEquals(0, confirmations);
        assertTrue(e.view("cheat").log10E() > 6);
        // 쌍 증거가 쌓이면 확정
        for (long pair = 1; pair <= 40 && confirmations == 0; pair++) {
            if (e.observe(id, "cheat", true, true, pair) != null) {
                confirmations++;
            }
            if (e.observe(id, "cheat", false, false, pair) != null) {
                confirmations++;
            }
        }
        assertEquals(1, confirmations);
    }

    @Test
    void ruleMixtureAndPairedDifferInWhatTheyRequire() {
        EvidenceParams paired = new EvidenceParams(1e-3, 2.0, 100, 1e-3, EvidenceParams.Rule.PAIRED);
        EvidenceEngine e = engine(paired);
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 300; i++) {
            e.observe(UUID.randomUUID(), "h" + i, false, false);
        }
        int confirmations = 0;
        for (int i = 0; i < 40; i++) {
            if (e.observe(id, "x", true, true) != null) { // 쌍 번호 없음 → PAIRED 규칙은 반응 안 함
                confirmations++;
            }
        }
        assertEquals(0, confirmations);
        for (long pair = 1; pair <= 30; pair++) {
            if (e.observe(id, "x", true, true, pair) != null | e.observe(id, "x", false, false, pair) != null) {
                confirmations++;
            }
        }
        assertEquals(1, confirmations);
    }

    @Test
    void pairedFieldsSurviveSqliteRoundTripAndOldFilesAreMigrated(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("p.db");
        UUID id = UUID.randomUUID();
        try (SqliteStore s = new SqliteStore(file)) {
            AccountRecord r = new AccountRecord(id, "Steve");
            r.pairDecoyOnly = 5;
            r.pairPlaceboOnly = 2;
            r.pairBoth = 1;
            r.pairNeither = 9;
            for (int i = 0; i < 5; i++) {
                Mixture.observePaired(r.logsPaired, true);
            }
            Mixture.observePaired(r.logsPaired, false);
            s.save(r);
        }
        try (SqliteStore s = new SqliteStore(file)) {
            AccountRecord r = s.load(id);
            assertEquals(5, r.pairDecoyOnly);
            assertEquals(9, r.pairNeither);
            double[] expect = Mixture.newPairedLogs();
            for (int i = 0; i < 5; i++) {
                Mixture.observePaired(expect, true);
            }
            Mixture.observePaired(expect, false);
            org.junit.jupiter.api.Assertions.assertArrayEquals(expect, r.logsPaired, 0.0);
        }
        // 1차 MVP 스키마(쌍 열 없음) 파일을 열어도 된다.
        Path old = dir.resolve("old.db");
        UUID oldId = UUID.randomUUID();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + old.toAbsolutePath()); Statement st = c.createStatement()) {
            st.execute("CREATE TABLE accounts (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, decoy_n INTEGER NOT NULL,"
                    + " decoy_hits INTEGER NOT NULL, placebo_n INTEGER NOT NULL, placebo_hits INTEGER NOT NULL,"
                    + " logs TEXT NOT NULL, confirmed_at INTEGER NOT NULL)");
            String zeros = String.join(",", java.util.Collections.nCopies(Mixture.SIZE, "0.0"));
            st.execute("INSERT INTO accounts VALUES('" + oldId + "','Alex',3,1,4,0,'" + zeros + "',0)");
        }
        try (SqliteStore s = new SqliteStore(old)) {
            AccountRecord r = s.load(oldId);
            assertNotNull(r);
            assertEquals(3, r.decoyN);
            assertEquals(0, r.pairDecoyOnly);
            assertEquals(0.0, r.log10EPaired(), 1e-12);
            s.save(r); // 다시 저장해도 된다
        }
    }
}

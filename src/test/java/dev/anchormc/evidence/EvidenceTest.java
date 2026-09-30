package dev.anchormc.evidence;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EvidenceTest {
    static RandomGenerator rng(long seed) {
        return RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(seed);
    }

    /** 정직 계정 여러 개가 p로 반응할 때, 한 번이라도 E ≥ x에 닿은 비율. */
    static double everCrossFraction(int accounts, int obs, double p, double p0, double[] thresholds, double[] out, long seed) {
        RandomGenerator r = rng(seed);
        int[] crossed = new int[thresholds.length];
        double[] logT = new double[thresholds.length];
        for (int k = 0; k < thresholds.length; k++) {
            logT[k] = Math.log(thresholds[k]);
        }
        for (int a = 0; a < accounts; a++) {
            double[] logs = Mixture.newLogs();
            double maxLog = Double.NEGATIVE_INFINITY;
            for (int i = 0; i < obs; i++) {
                Mixture.observe(logs, r.nextDouble() < p, p0);
                maxLog = Math.max(maxLog, Mixture.logE(logs));
            }
            for (int k = 0; k < thresholds.length; k++) {
                if (maxLog >= logT[k]) {
                    crossed[k]++;
                }
            }
        }
        for (int k = 0; k < thresholds.length; k++) {
            out[k] = (double) crossed[k] / accounts;
        }
        return out[0];
    }

    @Test
    void honestAtP0_ever_crossing_probability_is_at_most_one_over_x() {
        double[] xs = {5, 20, 100};
        double[] frac = new double[xs.length];
        int accounts = 6000;
        everCrossFraction(accounts, 800, 0.10, 0.10, xs, frac, 42);
        for (int k = 0; k < xs.length; k++) {
            double bound = 1 / xs[k];
            double sd = Math.sqrt(bound * (1 - bound) / accounts);
            assertTrue(frac[k] <= bound + 3 * sd, "x=" + xs[k] + " 관측 " + frac[k] + " > 한계 " + bound);
        }
    }

    @Test
    void honestBelowP0_isEvenSafer() {
        double[] xs = {10, 100};
        double[] frac = new double[2];
        everCrossFraction(4000, 800, 0.05, 0.10, xs, frac, 43);
        assertTrue(frac[0] <= 0.1, "" + frac[0]);
        assertTrue(frac[1] <= 0.01 + 0.005, "" + frac[1]);
    }

    /** p0가 과거에만 의존해 시간에 따라 바뀌고, 정직 확률이 매번 그 p0인 최악의 경우. */
    @Test
    void honestWithTimeVaryingPredictableP0_stillValid() {
        RandomGenerator r = rng(44);
        int accounts = 5000, obs = 600;
        double x = 20;
        int crossed = 0;
        for (int a = 0; a < accounts; a++) {
            double[] logs = Mixture.newLogs();
            double p0 = 0.1;
            boolean over = false;
            for (int i = 0; i < obs && !over; i++) {
                boolean hit = r.nextDouble() < p0;
                Mixture.observe(logs, hit, p0);
                over = Mixture.logE(logs) >= Math.log(x);
                // 다음 p0는 과거(방금 관측 포함)로 정한다
                p0 = Math.min(0.5, Math.max(0.02, p0 + (hit ? 0.01 : -0.002)));
            }
            if (over) {
                crossed++;
            }
        }
        double frac = (double) crossed / accounts;
        assertTrue(frac <= 1 / x + 3 * Math.sqrt(0.05 * 0.95 / accounts), "" + frac);
    }

    @Test
    void cheaterWhoRespondsOftenIsConfirmedQuickly() {
        RandomGenerator r = rng(45);
        double p0 = 0.10;
        int total = 0, runs = 500;
        for (int a = 0; a < runs; a++) {
            double[] logs = Mixture.newLogs();
            int n = 0;
            while (Mixture.logE(logs) < Math.log(1e9) && n < 1000) {
                Mixture.observe(logs, r.nextDouble() < 0.9, p0);
                n++;
            }
            assertTrue(n < 1000);
            total += n;
        }
        double mean = (double) total / runs;
        assertTrue(mean < 15, "반응률 0.9인 계정의 평균 확정 관측 수: " + mean);
    }

    @Test
    void alternativesAtOrBelowP0AreSkipped() {
        double[] logs = Mixture.newLogs();
        Mixture.observe(logs, false, 0.9);
        // p0=0.9면 대부분 대안이 q ≤ p0라 건너뛴다: 건너뛴 성분은 0 그대로
        int untouched = 0;
        for (double v : logs) {
            if (v == 0) {
                untouched++;
            }
        }
        assertTrue(untouched > Mixture.SIZE / 2);
        assertTrue(Mixture.logE(logs) <= 0 + 1e-12);
    }

    // ---- p0 추정 ----

    private static EvidenceEngine engine(EvidenceParams p) {
        return new EvidenceEngine(p, new MemoryStore(), () -> 1234L);
    }

    @Test
    void p0IsCappedUntilEnoughPlaceboObservations() {
        EvidenceEngine e = engine(new EvidenceParams(1e-9, 2.0, 100));
        assertEquals(EvidenceParams.P0_CAP, e.currentP0());
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 99; i++) {
            e.observe(id, "a", false, false);
        }
        assertEquals(EvidenceParams.P0_CAP, e.currentP0());
    }

    @Test
    void p0IsTwiceMeasuredRateOrTwiceUpperBoundWhenZeroHits() {
        EvidenceEngine e = engine(new EvidenceParams(1e-9, 2.0, 100));
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 200; i++) {
            e.observe(id, "a", false, false);
        }
        double upper = 1 - Math.pow(0.05, 1.0 / 200);
        assertEquals(2 * upper, e.currentP0(), 1e-12);
        for (int i = 0; i < 200; i++) {
            e.observe(id, "a", false, i < 40); // 40/400
        }
        assertEquals(2 * 40.0 / 400, e.currentP0(), 1e-12);
    }

    @Test
    void placeboObservationsNeverMoveEvalueAndDecoysNeverMovePlaceboRate() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 300; i++) {
            e.observe(id, "a", false, i % 3 == 0);
        }
        assertEquals(0.0, e.view("a").log10E(), 1e-12);
        var before = e.stats();
        for (int i = 0; i < 50; i++) {
            e.observe(id, "a", true, true);
        }
        var after = e.stats();
        assertEquals(before.placeboN(), after.placeboN());
        assertEquals(before.placeboHits(), after.placeboHits());
        assertTrue(e.view("a").log10E() > 0);
    }

    @Test
    void confirmationIsReportedOnceWhenThresholdCrossed() {
        EvidenceEngine e = engine(new EvidenceParams(1e-6, 2.0, 100));
        UUID id = UUID.randomUUID();
        for (int i = 0; i < 500; i++) {
            e.observe(UUID.randomUUID(), "honest", false, i % 20 == 0); // p0 ≈ 0.1
        }
        int confirmations = 0;
        for (int i = 0; i < 40; i++) {
            if (e.observe(id, "cheat", true, true) != null) {
                confirmations++;
            }
        }
        assertEquals(1, confirmations);
        assertTrue(e.view("cheat").confirmed());
        assertFalse(e.view("honest") != null && e.view("honest").confirmed());
    }

    /** 위약 반응률을 실측해 p0를 쓰는 전체 파이프라인에서 정직 계정이 문턱을 넘는 비율. */
    @Test
    void estimatedP0PipelineKeepsHonestAccountsBelowAlpha() {
        RandomGenerator r = rng(46);
        double alpha = 0.05;
        EvidenceEngine e = engine(new EvidenceParams(alpha, 2.0, 100));
        int accounts = 400, confirmed = 0;
        UUID[] ids = new UUID[accounts];
        for (int a = 0; a < accounts; a++) {
            ids[a] = new UUID(1, a);
        }
        // 계정들이 뒤섞여 관측을 낸다. 정직 반응률 0.08은 미끼·위약 모두 같다.
        for (int round = 0; round < 300; round++) {
            for (int a = 0; a < accounts; a++) {
                boolean decoy = r.nextBoolean();
                var c = e.observe(ids[a], "p" + a, decoy, r.nextDouble() < 0.08);
                if (c != null) {
                    confirmed++;
                }
            }
        }
        assertTrue((double) confirmed / accounts <= alpha, "정직 계정 오탐 비율 " + (double) confirmed / accounts);
    }

    @Test
    void unknownAccountHasNoView() {
        assertNull(engine(EvidenceParams.defaults()).view("nobody"));
        assertNotNull(EvidenceParams.defaults());
    }
}

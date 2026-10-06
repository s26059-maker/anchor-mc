package dev.anchormc.evidence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 기본 확정 규칙은 PAIRED(먼저 반응한 쪽 정확 검정). 혼합 e-value는 확정에 쓰지 않고 참고로만 남는다. */
class DefaultRuleTest {
    private static EvidenceEngine engine(EvidenceParams p) {
        return new EvidenceEngine(p, new MemoryStore(), () -> 1L);
    }

    @Test
    void defaultRuleIsPairedInCodeAndConfig() throws Exception {
        assertEquals(EvidenceParams.Rule.PAIRED, EvidenceParams.defaults().rule());
        String cfg = Files.readString(Path.of("src/main/resources/config.yml"));
        assertTrue(cfg.lines().anyMatch(l -> l.strip().equals("confirm-rule: PAIRED")), "config.yml의 confirm-rule 기본값은 PAIRED여야 한다");
    }

    @Test
    void aHugeMixtureEvidenceAloneNeverConfirmsUnderTheDefault() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        // p0는 위약 100건으로 낮게 정하고, 그 뒤 미끼 반응만 쏟아붓는다(쌍 번호 없음 = 먼저 반응 검정에는 안 들어간다).
        UUID other = UUID.randomUUID();
        for (int i = 0; i < 200; i++) {
            e.observe(other, "other", false, false);
        }
        for (int i = 0; i < 300; i++) {
            assertEquals(null, e.observe(id, "x", true, true), "혼합만으로 확정됐다");
        }
        var v = e.view("x");
        assertTrue(v.log10E() > 9, "시나리오: 혼합 E가 문턱을 넘어야 한다: " + v.log10E());
        assertFalse(v.confirmed());
        assertEquals(0, e.stats().confirmedAccounts());
    }

    @Test
    void firstReactionEvidenceConfirmsUnderTheDefaultEvenWithoutAnyMixtureEvidence() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        EvidenceEngine.Confirmation c = null;
        // 쌍마다 미끼가 먼저 반응하고 위약은 무반응: 미끼 관측 수는 적어도 먼저 반응 증거가 쌓인다.
        for (long pair = 1; pair <= 200 && c == null; pair++) {
            c = e.observe(id, "cheat", true, true, pair);
            if (c == null) {
                c = e.observe(id, "cheat", false, false, pair);
            }
        }
        assertNotNull(c);
        assertTrue(e.view("cheat").confirmed());
        assertTrue(e.view("cheat").log10EFirst() >= 9, "먼저 반응 E가 1/alpha를 넘어 확정돼야 한다");
    }

    @Test
    void honestSymmetricFirstReactionsDoNotConfirmUnderTheDefault() {
        EvidenceEngine e = engine(EvidenceParams.defaults());
        UUID id = UUID.randomUUID();
        for (long pair = 1; pair <= 400; pair++) { // 미끼·위약이 번갈아 먼저 반응: 귀무
            boolean decoyFirst = pair % 2 == 0;
            e.observe(id, "honest", decoyFirst, true, pair);
            e.observe(id, "honest", !decoyFirst, true, pair);
        }
        assertFalse(e.view("honest").confirmed());
    }

    @Test
    void otherRulesStillWorkWhenChosenExplicitly() {
        EvidenceParams mixture = new EvidenceParams(1e-9, 2.0, 100, 1e-3, EvidenceParams.Rule.MIXTURE);
        EvidenceEngine e = engine(mixture);
        UUID id = UUID.randomUUID(), other = UUID.randomUUID();
        for (int i = 0; i < 200; i++) {
            e.observe(other, "other", false, false);
        }
        boolean confirmed = false;
        for (int i = 0; i < 300 && !confirmed; i++) {
            confirmed = e.observe(id, "x", true, true) != null;
        }
        assertTrue(confirmed, "MIXTURE를 직접 고르면 혼합으로 확정된다(선택 사항으로 남아 있다)");
    }
}

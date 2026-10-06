package dev.anchormc.evidence;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** /anchor status: 기본(PAIRED)에서는 확정에 쓰는 값(먼저 반응)에만 확정 문턱을 붙이고 혼합은 참고용이라고 밝힌다. */
class StatusTextTest {
    private static EvidenceEngine.View view(boolean confirmed) {
        return new EvidenceEngine.View("steve", 20, 6, 20, 1, 3.456, confirmed, confirmed ? 1 : 0,
                4, 1, 2, 13, 0.789, 5, 1, 1.234);
    }

    @Test
    void pairedRuleLabelsMixtureAsReferenceOnlyAndPutsTheThresholdOnFirstReaction() {
        String s = StatusText.format(view(false), EvidenceParams.defaults());
        assertTrue(s.contains("혼합 log10E=3.46(참고용, 확정에 안 씀)"), s);
        assertTrue(s.contains("먼저 반응: 미끼 5 위약 1 log10E=1.23(확정 문턱 9.0)"), s);
        assertTrue(s.contains("log10E쌍=0.79(참고용)"), s);
        assertTrue(s.contains("규칙 PAIRED: 미확정"), s);
        assertFalse(s.contains("혼합 log10E=3.46(문턱"), "혼합에 확정 문턱이 붙으면 확정에 쓰는 것처럼 보인다: " + s);
    }

    @Test
    void confirmedAccountsAreShownAsConfirmed() {
        assertTrue(StatusText.format(view(true), EvidenceParams.defaults()).endsWith("확정(섀도)"));
    }

    @Test
    void otherRulesPutTheThresholdWhereTheRuleUsesIt() {
        String mix = StatusText.format(view(false), new EvidenceParams(1e-9, 2.0, 100, 1e-3, EvidenceParams.Rule.MIXTURE));
        assertTrue(mix.contains("혼합 log10E=3.46(문턱 9.0)"), mix);
        assertTrue(mix.contains("log10E=1.23(참고용)"), mix);
        String both = StatusText.format(view(false), new EvidenceParams(1e-9, 2.0, 100, 1e-3, EvidenceParams.Rule.BOTH));
        assertTrue(both.contains("혼합 log10E=3.46(문턱 9.0)"), both);
        assertTrue(both.contains("log10E=1.23(문턱 3.0)"), both);
    }
}

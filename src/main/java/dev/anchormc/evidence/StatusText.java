package dev.anchormc.evidence;

import java.util.Locale;

/** /anchor status 한 줄(Bukkit과 무관해서 단위 테스트가 된다). */
public final class StatusText {
    private StatusText() {
    }

    /**
     * 확정 규칙이 PAIRED(기본)이면 확정은 "먼저 반응한 쪽" E만 보고(문턱 1/alpha), 혼합·쌍(한쪽만) E는 참고용이라고 밝힌다.
     * 다른 규칙이면 그 규칙이 쓰는 값에 문턱을 붙인다. 반응은 창이 끝난 뒤(기본 240초)에 증거에 반영된다.
     */
    public static String format(EvidenceEngine.View v, EvidenceParams p) {
        boolean paired = p.rule() == EvidenceParams.Rule.PAIRED;
        double alpha = -Math.log10(p.alpha()), guard = -Math.log10(p.pairedAlpha());
        String mix = paired ? String.format(Locale.ROOT, "혼합 log10E=%.2f(참고용, 확정에 안 씀)", v.log10E())
                : String.format(Locale.ROOT, "혼합 log10E=%.2f(문턱 %.1f)", v.log10E(), alpha);
        String first = String.format(Locale.ROOT, "먼저 반응: 미끼 %d 위약 %d log10E=%.2f(%s)", v.firstDecoy(), v.firstPlacebo(), v.log10EFirst(),
                paired ? String.format(Locale.ROOT, "확정 문턱 %.1f", alpha)
                        : p.rule() == EvidenceParams.Rule.BOTH ? String.format(Locale.ROOT, "문턱 %.1f", guard) : "참고용");
        return String.format(Locale.ROOT,
                "%s | 미끼 %d/%d (%s) | 위약 %d/%d (%s) | %s | 쌍(한쪽만): 미끼만 %d 위약만 %d 둘다 %d 없음 %d log10E쌍=%.2f(참고용) | %s | 규칙 %s: %s",
                v.name(), v.decoyHits(), v.decoyN(), pct(v.decoyRate()),
                v.placeboHits(), v.placeboN(), pct(v.placeboRate()),
                mix,
                v.pairDecoyOnly(), v.pairPlaceboOnly(), v.pairBoth(), v.pairNeither(), v.log10EPaired(),
                first, p.rule(), v.confirmed() ? "확정(섀도)" : "미확정");
    }

    private static String pct(double r) {
        return Double.isNaN(r) ? "-" : String.format(Locale.ROOT, "%.1f%%", r * 100);
    }
}

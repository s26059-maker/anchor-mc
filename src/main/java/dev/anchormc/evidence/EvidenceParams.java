package dev.anchormc.evidence;

/**
 * @param alpha              혼합 e-value 확정 문턱은 E ≥ 1/alpha
 * @param p0Multiplier       위약 반응률에 곱하는 안전 배수
 * @param minPlaceboSamples  위약 관측이 이보다 적으면 p0를 상한으로 둔다(사실상 확정 불가)
 * @param pairedAlpha        BOTH 규칙에서 쌍 정확 e-value의 문턱(E ≥ 1/pairedAlpha)
 * @param rule               무엇으로 확정하나
 */
public record EvidenceParams(double alpha, double p0Multiplier, int minPlaceboSamples, double pairedAlpha, Rule rule) {
    public static final double P0_FLOOR = 0.001;
    public static final double P0_CAP = 0.9;

    /**
     * MIXTURE: p0 기반 대안 혼합. PAIRED(기본): "먼저 반응한 쪽" 정확 검정만(문턱 1/alpha, 혼합은 확정에 안 씀).
     * BOTH: 혼합(1/alpha) 그리고 먼저 반응한 쪽(1/pairedAlpha).
     */
    public enum Rule { MIXTURE, PAIRED, BOTH }

    public EvidenceParams(double alpha, double p0Multiplier, int minPlaceboSamples) {
        this(alpha, p0Multiplier, minPlaceboSamples, 1e-3, Rule.MIXTURE);
    }

    public static EvidenceParams defaults() {
        return new EvidenceParams(1e-9, 2.0, 100, 1e-3, Rule.PAIRED);
    }

    public EvidenceParams {
        if (!(alpha > 0 && alpha < 1)) {
            throw new IllegalArgumentException("alpha는 (0,1)");
        }
        if (!(pairedAlpha > 0 && pairedAlpha < 1)) {
            throw new IllegalArgumentException("paired-alpha는 (0,1)");
        }
        if (p0Multiplier < 1) {
            throw new IllegalArgumentException("p0-multiplier는 1 이상");
        }
        if (minPlaceboSamples < 0) {
            throw new IllegalArgumentException("min-placebo-samples는 0 이상");
        }
    }
}

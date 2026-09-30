package dev.anchormc.evidence;

/**
 * @param alpha              확정 문턱은 E ≥ 1/alpha
 * @param p0Multiplier       위약 반응률에 곱하는 안전 배수
 * @param minPlaceboSamples  위약 관측이 이보다 적으면 p0를 상한으로 둔다(사실상 확정 불가)
 */
public record EvidenceParams(double alpha, double p0Multiplier, int minPlaceboSamples) {
    public static final double P0_FLOOR = 0.001;
    public static final double P0_CAP = 0.9;

    public static EvidenceParams defaults() {
        return new EvidenceParams(1e-9, 2.0, 100);
    }

    public EvidenceParams {
        if (!(alpha > 0 && alpha < 1)) {
            throw new IllegalArgumentException("alpha는 (0,1)");
        }
        if (p0Multiplier < 1) {
            throw new IllegalArgumentException("p0-multiplier는 1 이상");
        }
        if (minPlaceboSamples < 0) {
            throw new IllegalArgumentException("min-placebo-samples는 0 이상");
        }
    }
}

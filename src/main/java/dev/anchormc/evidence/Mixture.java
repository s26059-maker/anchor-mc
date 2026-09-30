package dev.anchormc.evidence;

/**
 * 대안 혼합 e-value (anchor의 evidence 패키지와 같은 수식). 정직한 반응 확률이 p0 이하이면
 * 몇 번을 들여다봐도 P(언젠가 E ≥ x) ≤ 1/x (Ville 부등식).
 *
 * anchor의 Go 구현과 다른 점은 p0가 관측마다 바뀔 수 있다는 것(위약 반응률을 계속 실측하므로).
 * 그래서 대안 격자를 p0에 상대적으로 잡지 않고 절대 확률로 고정한다. 관측 시점의 p0 이하인 대안은
 * 그 관측에서 곱하기 1(건너뜀)이다: q ≤ p0인 대안의 가능도비는 p ≤ p0에서 기대값이 1을 넘을 수 있어 쓰면 안 된다.
 * 각 대안의 곱은 상수 p0 아래에서 상위마팅게일이고, p0가 과거에만 의존하면 이 성질이 유지된다.
 */
public final class Mixture {
    public static final int SIZE = 48;
    private static final double[] Q = new double[SIZE];
    private static final double[] LOG_Q = new double[SIZE];
    private static final double[] LOG_1MQ = new double[SIZE];

    static {
        double lo = Math.log(0.005 / 0.995);
        double hi = Math.log(99.0);
        for (int j = 0; j < SIZE; j++) {
            double z = lo + (hi - lo) * j / (SIZE - 1);
            double q = 1 / (1 + Math.exp(-z));
            Q[j] = q;
            LOG_Q[j] = Math.log(q);
            LOG_1MQ[j] = Math.log(1 - q);
        }
    }

    private Mixture() {
    }

    public static double[] newLogs() {
        return new double[SIZE];
    }

    // ---- 쌍 정확 검정 ----
    // 동전으로 정한 (미끼, 위약) 쌍에서 한쪽만 반응한 쌍만 센다. 귀무(미끼가 반응에 영향 없음) 아래에서 "미끼 쪽만 반응"은
    // 정확히 확률 1/2이고(동전이 반응과 독립), p0 추정도 계정 내 독립도 필요 없다. 대안은 q in (0.5, 1)의 균등 혼합.

    public static final int PAIRED_SIZE = 40;
    private static final double[] PQ = new double[PAIRED_SIZE];
    private static final double[] PLOG_Q = new double[PAIRED_SIZE];
    private static final double[] PLOG_1MQ = new double[PAIRED_SIZE];

    static {
        double lo = Math.log(0.55 / 0.45);
        double hi = Math.log(0.999 / 0.001);
        for (int j = 0; j < PAIRED_SIZE; j++) {
            double z = lo + (hi - lo) * j / (PAIRED_SIZE - 1);
            PQ[j] = 1 / (1 + Math.exp(-z));
            PLOG_Q[j] = Math.log(PQ[j]) - Math.log(0.5);
            PLOG_1MQ[j] = Math.log(1 - PQ[j]) - Math.log(0.5);
        }
    }

    public static double[] newPairedLogs() {
        return new double[PAIRED_SIZE];
    }

    /** 한쪽만 반응한 쌍 하나. decoyOnly=true면 미끼 쪽만 반응. */
    public static void observePaired(double[] logs, boolean decoyOnly) {
        for (int j = 0; j < PAIRED_SIZE; j++) {
            logs[j] += decoyOnly ? PLOG_Q[j] : PLOG_1MQ[j];
        }
    }

    /** 반응 하나를 반영한다. p0는 이 관측 직전까지의 데이터로 정한 값이어야 한다. */
    public static void observe(double[] logs, boolean hit, double p0) {
        double lp0 = Math.log(p0);
        double l1p0 = Math.log(1 - p0);
        for (int j = 0; j < SIZE; j++) {
            if (Q[j] > p0) {
                logs[j] += hit ? LOG_Q[j] - lp0 : LOG_1MQ[j] - l1p0;
            }
        }
    }

    /** 현재 e-value의 자연로그. */
    public static double logE(double[] logs) {
        double mx = Double.NEGATIVE_INFINITY;
        for (double v : logs) {
            mx = Math.max(mx, v);
        }
        double s = 0;
        for (double v : logs) {
            s += Math.exp(v - mx);
        }
        return mx + Math.log(s) - Math.log(logs.length);
    }

    public static double log10E(double[] logs) {
        return logE(logs) / Math.log(10);
    }
}

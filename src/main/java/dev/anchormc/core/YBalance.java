package dev.anchormc.core;

import java.util.concurrent.atomic.AtomicLongArray;
import java.util.random.RandomGenerator;

/**
 * 높이 분포 보정. 표본에서 뽑은 후보가 조건(봉인·간격·월드 범위)을 통과할 확률은 높이 띠마다 다르다(바닥 근처는 진짜 광석이 많아 붙기 쉽고
 * 월드 바닥에 걸리기도 한다). 그대로 두면 통과한 것의 높이 분포가 표본과 어긋난다(1.1단계에서 밀도가 높을수록 커졌다).
 * 띠별 통과율을 실측하고, 통과한 후보를 (가장 낮은 띠 통과율 / 그 띠 통과율) 확률로만 받아들여 띠별 최종 통과율을 같게 만든다.
 * 그러면 받아들여진 후보의 높이 분포는 표본의 높이 분포에 비례한다. 스레드 안전(패킷을 만드는 여러 스레드가 함께 쓴다).
 */
final class YBalance {
    static final int BAND = 8;
    /** 통계가 이만큼 쌓인 띠만 믿는다. 그전에는 보정 없이 받아들인다. */
    private static final int MIN_ATTEMPTS = 200;

    private final int yMin;
    private final int bands;
    private final AtomicLongArray attempts, passes;

    YBalance(int yMin, int yMax) {
        this.yMin = yMin;
        this.bands = (yMax - yMin) / BAND + 1;
        this.attempts = new AtomicLongArray(bands);
        this.passes = new AtomicLongArray(bands);
    }

    private int band(int y) {
        return Math.max(0, Math.min(bands - 1, (y - yMin) / BAND));
    }

    void record(int srcY, boolean passed) {
        int b = band(srcY);
        attempts.incrementAndGet(b);
        if (passed) {
            passes.incrementAndGet(b);
        }
    }

    private double rate(int b) {
        return (passes.get(b) + 1.0) / (attempts.get(b) + 2.0);
    }

    /** 조건을 통과한 후보를 받아들일까. */
    boolean accept(int srcY, RandomGenerator rng) {
        int b = band(srcY);
        if (attempts.get(b) < MIN_ATTEMPTS) {
            return true;
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < bands; i++) {
            if (attempts.get(i) >= MIN_ATTEMPTS) {
                min = Math.min(min, rate(i));
            }
        }
        return rng.nextDouble() < min / rate(b);
    }
}

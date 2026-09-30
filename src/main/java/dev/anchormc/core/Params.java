package dev.anchormc.core;

/**
 * 미끼 배치와 반응 판정 설정. 시간은 틱(20틱=1초).
 * 1.1단계부터 배치는 "플레이어에게 청크가 전송될 때"만 일어난다(그래서 max-distance는 없다).
 */
public record Params(
        double reactionRadius,
        long windowTicks,
        double giveUpDistance,
        double retractDistance,
        int yMin,
        int yMax,
        double minDistance,
        int maxActivePairs,
        long cooldownTicks,
        int maxAttempts,
        double pairsPerChunk,
        int profileSamples) {

    public static Params defaults() {
        return new Params(3.0, 240 * 20L, 160.0, 2.0, -64, 16, 6.0, 24, 0, 60, 2.0, 600);
    }

    public Params {
        require(reactionRadius >= 2.5, "reaction-radius는 2.5 이상이어야 한다(채굴로 이웃 블록을 깨는 거리보다 커야 반응이 먼저 기록된다)");
        require(retractDistance > 0 && retractDistance <= reactionRadius - 0.5, "retract-distance는 (0, r-0.5] 범위");
        require(minDistance > reactionRadius + 1, "min-distance는 r+1보다 커야 한다(만들자마자 반응하면 안 된다)");
        require(giveUpDistance > minDistance, "give-up-distance는 min-distance보다 커야 한다");
        require(windowTicks > 0 && cooldownTicks >= 0, "시간 창/쿨다운이 잘못됐다");
        require(yMin < yMax, "y-min < y-max여야 한다");
        require(maxActivePairs >= 1 && maxAttempts >= 1, "max-active-pairs, max-attempts는 1 이상");
        require(pairsPerChunk > 0 && pairsPerChunk <= 8, "pairs-per-chunk는 (0, 8]");
        require(profileSamples >= 0, "profile-samples는 0 이상");
    }

    /** 같은 플레이어의 두 뭉치는 반응 영역이 겹치지 않게 (가장 가까운 블록끼리) 이만큼 떨어뜨린다. */
    public double minSeparation() {
        return 2 * reactionRadius + 2;
    }

    private static void require(boolean ok, String msg) {
        if (!ok) {
            throw new IllegalArgumentException(msg);
        }
    }
}

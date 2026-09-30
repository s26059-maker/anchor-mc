package dev.anchormc.core;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.random.RandomGenerator;

/**
 * 진짜 다이아 광맥의 모양·크기·높이 분포. 월드에서 표본을 모아(VeinScanner) 뱅크에 넣고, 미끼·위약은 뱅크에서
 * 광맥 하나를 통째로(모양과 원래 높이 함께) 뽑아 수평 대칭 변환한 것을 쓴다. 크기 분포와 Y 분포, 둘의 결합이
 * 표본 그대로 재현된다. 표본이 {@link #MIN_BANK}개 미만이면 바닐라 생성 규칙 기반 기본값을 쓴다.
 *
 * 1.2단계: 표본 추가(메인 스레드)와 뽑기(패킷을 만드는 스레드)가 동시에 일어난다. 뱅크는 불변 배열을 통째로 갈아 끼운다.
 */
public final class VeinProfile {
    public static final int MIN_BANK = 40;
    private static final int BANK_CAP = 4000;
    /** 이보다 큰 덩어리는 광맥 여러 개가 붙은 것으로 보고 표본에서 뺀다. */
    public static final int MAX_SAMPLE_SIZE = 40;

    /** 뽑힌 뭉치: 최소 모서리 기준 상대 좌표, 최소 y가 놓일 높이, 표본의 원래 높이(높이 보정이 쓴다). */
    public record Placed(int[][] cells, int y, int srcY) {
    }

    private record Sample(int[][] cells, int y) {
    }

    private volatile Sample[] bank = new Sample[0];
    private final Set<String> scanned = new HashSet<>();
    private final int target;

    /** target: 이만큼 모이면 더 스캔하지 않는다(0이면 스캔 안 함, 기본값만 사용). */
    public VeinProfile(int target) {
        this.target = target;
    }

    public boolean wantsSamples() {
        return bank.length < target;
    }

    public int bankSize() {
        return bank.length;
    }

    public boolean usingBank() {
        return bank.length >= MIN_BANK;
    }

    /** 처음 보는 청크면 true(그리고 기록). 메인 스레드에서만 부른다. */
    boolean markScanned(String key) {
        return scanned.add(key);
    }

    /** cells는 정규화된 상대 좌표, y는 그 뭉치의 최소 y. */
    synchronized void add(int[][] cells, int y) {
        Sample[] cur = bank;
        if (cur.length < BANK_CAP) {
            Sample[] next = Arrays.copyOf(cur, cur.length + 1);
            next[cur.length] = new Sample(cells, y);
            bank = next;
        }
    }

    /** 표본 크기 히스토그램(1..maxBin, 마지막 칸은 그 이상). */
    public int[] sizeHistogram(int maxBin) {
        int[] h = new int[maxBin];
        for (Sample s : bank) {
            h[Math.min(maxBin, s.cells.length) - 1]++;
        }
        return h;
    }

    public Placed draw(RandomGenerator rng, int yMin, int yMax) {
        Sample[] snap = bank;
        if (snap.length >= MIN_BANK) {
            Sample s = snap[rng.nextInt(snap.length)];
            int y = s.y + rng.nextInt(7) - 3;
            return new Placed(VeinShapes.horizontalSymmetry(s.cells, rng.nextInt(8)), y, s.y);
        }
        // 바닐라 기본값: 작은 광맥(size 4, 7회), 묻힌 광맥(size 8, 4회), 큰 광맥(size 12, 1/9회)의 비율.
        double w = rng.nextDouble() * (7 + 4 + 1 / 9.0);
        int size = w < 7 ? 4 : w < 11 ? 8 : 12;
        int[][] cells = VeinShapes.blob(rng, size);
        for (int i = 0; i < 50 && cells.length == 0; i++) { // 광맥이 아무것도 못 놓은 경우는 광맥이 아니다
            cells = VeinShapes.blob(rng, size);
        }
        if (cells.length == 0) {
            cells = new int[][] {{0, 0, 0}};
        }
        // 바닐라 다이아 높이는 [-144, 16] 삼각 분포(최빈값 -64)인데 월드가 -64에서 끝나므로, 남는 부분은 위로 갈수록 줄어드는
        // 선형 밀도(밀도 ∝ yMax - y)다.
        double y = yMax - (yMax - yMin) * Math.sqrt(1 - rng.nextDouble());
        int fy = (int) Math.floor(y);
        return new Placed(cells, fy, fy);
    }
}

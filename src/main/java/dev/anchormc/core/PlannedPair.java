package dev.anchormc.core;

import java.util.List;

/**
 * 한 청크의 (미끼, 위약) 쌍 한 개의 계획. 좌표·동전은 계획 시점에 정해져 바뀌지 않는다(같은 청크를 다시 받아도 같다).
 * 상태만 바뀐다: 활성 → 판정 완료(CONSUMED) → 회수(RETIRED). RETIRED는 다시 보내지 않는다.
 */
public final class PlannedPair {
    static final int ACTIVE = 0, CONSUMED = 1, RETIRED = 2;

    public final long pairId;
    public final int slot;
    private final List<Voxel> a, b;
    private final boolean aDecoy;
    private volatile int state = ACTIVE;
    private volatile Result result;
    private volatile long exposure;
    private volatile boolean hitSeen;
    private volatile Reason reason;

    PlannedPair(long pairId, int slot, List<Voxel> a, List<Voxel> b, boolean aDecoy) {
        this.pairId = pairId;
        this.slot = slot;
        this.a = List.copyOf(a);
        this.b = List.copyOf(b);
        this.aDecoy = aDecoy;
    }

    /** 계획에서 먼저 뽑힌 뭉치와 나중에 뽑힌 뭉치(어느 쪽이 미끼인지와 무관한 순서). */
    List<Voxel> first() {
        return a;
    }

    List<Voxel> second() {
        return b;
    }

    boolean firstIsDecoy() {
        return aDecoy;
    }

    public List<Voxel> decoy() {
        return aDecoy ? a : b;
    }

    public List<Voxel> placebo() {
        return aDecoy ? b : a;
    }

    public boolean retired() {
        return state == RETIRED;
    }

    /** 한쪽이라도 HIT/MISS가 나서 증거에 한 번 쓰였다. 다시 받으면 미끼는 보이되 새 관측은 만들지 않는다. */
    public boolean consumed() {
        return state == CONSUMED;
    }

    public Result result() {
        return result;
    }

    /** 청크가 화면에 있었던 누적 틱(양쪽 자리가 같은 시간을 겪는다). 판정 창은 이 시간으로 잰다. */
    long exposureTicks() {
        return exposure;
    }

    void addExposure(long ticks) {
        if (ticks > 0) {
            exposure += ticks;
        }
    }

    /** 이 쌍에서 (어느 쪽이든) 반응이 한 번이라도 나왔다. */
    public boolean hitSeen() {
        return hitSeen;
    }

    void markHit() {
        hitSeen = true;
    }

    // 상태 전이는 메인 스레드(추적)와 패킷 스레드(prepareChunk)가 함께 건드린다: 전이는 한 자물쇠 아래서 하고, RETIRED는 끝 상태다.
    synchronized void consume(Result r) {
        if (state == ACTIVE) {
            result = r;
            state = CONSUMED;
        }
    }

    /** 다시 보내지 않도록 접는다. 처음 접었으면 true(사유 집계를 한 번만 하려고). */
    synchronized boolean retire(Reason why) {
        reason = why;
        if (state == RETIRED) {
            return false;
        }
        state = RETIRED;
        return true;
    }

    /** 영구 회수가 아닌 거둠(자격 상실·청크 언로드 등)의 마지막 사유. 계획은 그대로다. */
    void note(Reason why) {
        if (state != RETIRED) {
            reason = why;
        }
    }

    /** 이 쌍이 마지막으로 거둬지거나 접힌 이유(한 번도 없으면 null). */
    public Reason reason() {
        return reason;
    }
}

package dev.anchormc.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 반응 판정과 자리의 수명 관리. 미끼와 위약을 완전히 같은 코드로 처리한다:
 * 이 클래스는 Site.kind를 읽지 않는다(Hooks로 그대로 넘길 뿐).
 * 자리는 블록 하나 이상의 뭉치다. 거리는 뭉치의 가장 가까운 블록 기준, 노출 검사는 뭉치의 모든 블록과 이웃 기준이다.
 */
public final class ResponseTracker {
    public interface Hooks {
        /** 판정이 정해졌을 때(HIT/MISS/VOID). */
        void outcome(Outcome o);

        /** 자리가 거둬졌을 때. restoreBlock=false면 클라이언트에 블록을 다시 보낼 필요 없음(청크 언로드). */
        void retired(Site s, boolean restoreBlock, long tick);
    }

    private Params params;
    private final Hooks hooks;
    private final Map<UUID, List<Site>> byPlayer = new HashMap<>();
    private final Map<Pos, List<Site>> byPos = new HashMap<>();
    private final Map<UUID, double[]> lastPos = new HashMap<>();

    public ResponseTracker(Params params, Hooks hooks) {
        this.params = params;
        this.hooks = hooks;
    }

    public void setParams(Params p) {
        this.params = p;
    }

    void add(Site s) {
        byPlayer.computeIfAbsent(s.player, k -> new ArrayList<>()).add(s);
        for (Voxel v : s.voxels) {
            byPos.computeIfAbsent(v.pos(), k -> new ArrayList<>(1)).add(s);
        }
    }

    public List<Site> sitesOf(UUID player) {
        return byPlayer.getOrDefault(player, List.of());
    }

    public List<Site> allActive() {
        List<Site> out = new ArrayList<>();
        byPlayer.values().forEach(out::addAll);
        return out;
    }

    /** 이 플레이어의 마지막으로 알려진 위치 {x, y, z}. 모르면 null. */
    double[] lastPos(UUID player) {
        return lastPos.get(player);
    }

    /** 플레이어 위치가 바뀌었을 때. */
    public void observePosition(UUID player, String world, double x, double y, double z, long tick) {
        lastPos.put(player, new double[] {x, y, z});
        List<Site> list = byPlayer.get(player);
        if (list == null) {
            return;
        }
        for (Site s : new ArrayList<>(list)) {
            if (!s.active) {
                continue;
            }
            double d = s.pos.world().equals(world) ? s.distanceTo(x, y, z) : Double.POSITIVE_INFINITY;
            if (d < params.retractDistance()) {
                // 정상 플레이에선 오지 못하는 거리(밀착·노클립·텔레포트). 판정 전이면 제외하고 거둔다.
                retire(s, Result.VOID, tick, true, new Reason(RetireCause.TOO_CLOSE,
                        String.format(java.util.Locale.ROOT, "거리 %.2f < %.2f, 플레이어 (%.1f, %.1f, %.1f)", d, params.retractDistance(), x, y, z), tick));
            } else if (!s.hitReported && d <= params.reactionRadius()) {
                react(s, tick);
            } else if (s.result == null && d > params.giveUpDistance()) {
                // 판정만 하고 화면에서는 거두지 않는다: 진짜 광석은 멀어진다고 사라지지 않는다.
                resolve(s, Result.MISS, tick);
            }
        }
    }

    /** 반응(HIT) 하나. 창 안이면 판정(HIT), 창이 끝난 뒤(MISS 판정 후)면 LATE_HIT로 알린다. 자리마다 한 번만. */
    private void react(Site s, long tick) {
        s.hitReported = true;
        if (s.result == null) {
            resolve(s, Result.HIT, tick);
        } else {
            emit(s, Result.LATE_HIT, tick);
        }
    }

    /** 플레이어가 블록을 캐려 할 때. 캐는 블록이 뭉치 반경 안이면 반응. 미끼는 이 직후 노출 처리로 거둬진다. */
    public void observeDig(UUID player, Pos block, long tick) {
        List<Site> list = byPlayer.get(player);
        if (list == null) {
            return;
        }
        for (Site s : new ArrayList<>(list)) {
            if (s.active && !s.hitReported && s.pos.world().equals(block.world())
                    && s.distanceTo(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5) <= params.reactionRadius()) {
                react(s, tick);
            }
        }
    }

    /**
     * 이 좌표의 블록이 바뀌려 한다(변경 전에 호출). 그 자리와 여섯 이웃에 블록이 있는 뭉치는 노출될 수 있으므로 통째로 거둔다.
     */
    public void blockChanging(Pos p, long tick) {
        if (byPos.isEmpty()) {
            return;
        }
        touch(p, p, tick);
        for (int[] f : Pos.FACES) {
            touch(p, p.offset(f[0], f[1], f[2]), tick);
        }
    }

    private void touch(Pos changed, Pos at, long tick) {
        List<Site> list = byPos.get(at);
        if (list != null) {
            for (Site s : new ArrayList<>(list)) {
                String detail = String.format(java.util.Locale.ROOT, "변경 좌표 (%d, %d, %d) → 자리의 %s (%d, %d, %d)",
                        changed.x(), changed.y(), changed.z(), changed.equals(at) ? "블록" : "이웃", at.x(), at.y(), at.z());
                retire(s, Result.VOID, tick, true, new Reason(RetireCause.BLOCK_EVENT, detail, tick));
            }
        }
    }

    /**
     * 시간 창이 끝난 자리를 MISS로 판정한다. 1.2단계부터 화면에서는 거두지 않는다(진짜 광석은 시간이 지나도 사라지지 않으므로
     * 미끼가 저절로 사라지면 그게 단서가 된다). 노출 회수·청크 언로드가 아니면 남아 있고, 판정만 끝난다.
     */
    public void expire(long tick) {
        for (Site s : allActive()) {
            if (s.result == null && tick - s.createdTick >= params.windowTicks()) {
                resolve(s, Result.MISS, tick);
            }
        }
    }

    /** 이 플레이어에게 이 쌍의 자리가 이미 있나. */
    boolean hasPair(UUID player, long pairId) {
        for (Site s : sitesOf(player)) {
            if (s.pairId == pairId) {
                return true;
            }
        }
        return false;
    }

    /** 이 플레이어의 위치 기억을 지운다(나갔을 때). */
    void forgetPosition(UUID player) {
        lastPos.remove(player);
    }

    /**
     * 이 플레이어의 자리를 모두 거둔다. restoreBlock=true는 자격 상실(게임모드 변경 등): 화면에서만 거두고 판정은 내지 않는다
     * (자격을 되찾아 청크를 다시 받으면 같은 미끼가 돌아온다). false는 월드 이동: 판정 전이던 자리는 증거에서 뺀다.
     */
    public void dropPlayer(UUID player, long tick, boolean restoreBlock) {
        List<Site> list = byPlayer.get(player);
        if (list != null) {
            for (Site s : new ArrayList<>(list)) {
                if (restoreBlock) {
                    retire(s, null, tick, true, new Reason(RetireCause.INELIGIBLE, "", tick));
                } else {
                    retire(s, Result.VOID, tick, false, new Reason(RetireCause.LEFT_WORLD, "", tick));
                }
            }
        }
    }

    /**
     * 서버가 청크를 내렸다: 모든 플레이어의 그 청크에 걸친 뭉치의 추적을 접는다(클라이언트도 청크를 버리므로 되돌릴 필요 없음).
     * 판정은 하지 않는다(미끼가 화면에서 노출된 시간이 멈출 뿐이다). 판정 전이던 쌍은 다시 받으면 이어서 시간이 쌓인다.
     */
    public void dropChunk(String world, int cx, int cz, long tick) {
        for (Site s : allActive()) {
            if (s.touchesChunk(world, cx, cz)) {
                retire(s, null, tick, false, chunkDropped(cx, cz, tick));
            }
        }
    }

    private static Reason chunkDropped(int cx, int cz, long tick) {
        return new Reason(RetireCause.CHUNK_DROPPED, "청크(" + cx + ", " + cz + ")", tick);
    }

    /** 이 플레이어의 클라이언트가 청크를 버렸다(위와 같다). */
    public void dropChunkFor(UUID player, String world, int cx, int cz, long tick) {
        for (Site s : new ArrayList<>(sitesOf(player))) {
            if (s.touchesChunk(world, cx, cz)) {
                retire(s, null, tick, false, chunkDropped(cx, cz, tick));
            }
        }
    }

    /** 플레이어가 나갔다: 판정 전이던 자리는 증거에서 뺀다(VOID). */
    public void dropPlayerFinal(UUID player, long tick) {
        List<Site> list = byPlayer.get(player);
        if (list != null) {
            for (Site s : new ArrayList<>(list)) {
                retire(s, Result.VOID, tick, false, new Reason(RetireCause.QUIT, "", tick));
            }
        }
    }

    public void dropAll(long tick) {
        for (Site s : allActive()) {
            retire(s, Result.VOID, tick, true, new Reason(RetireCause.SHUTDOWN, "", tick));
        }
    }

    /** 이 플레이어의 이 쌍 자리를 모두 거둔다(계획이 이미 접혔는데 추적이 남은 경우). */
    void retirePair(UUID player, long pairId, Reason why, long tick) {
        for (Site s : new ArrayList<>(sitesOf(player))) {
            if (s.pairId == pairId) {
                retire(s, Result.VOID, tick, true, why);
            }
        }
    }

    /** 자리를 거둔다. 판정 전이면 ifUnresolved(null이면 판정 없이)로 확정한다. 사유는 반드시 남긴다. */
    public void retire(Site s, Result ifUnresolved, long tick, boolean restoreBlock, Reason why) {
        if (!s.active) {
            return;
        }
        s.reason = java.util.Objects.requireNonNull(why);
        if (s.result == null && ifUnresolved != null) {
            resolve(s, ifUnresolved, tick);
        }
        s.active = false;
        remove(byPlayer, s.player, s);
        for (Voxel v : s.voxels) {
            remove(byPos, v.pos(), s);
        }
        hooks.retired(s, restoreBlock, tick);
    }

    private void resolve(Site s, Result r, long tick) {
        s.result = r;
        emit(s, r, tick);
    }

    /** 이 클래스가 종류(kind)를 읽는 유일한 곳: 그대로 Hooks로 넘길 뿐이다. */
    private void emit(Site s, Result r, long tick) {
        hooks.outcome(new Outcome(s.player, s.playerName, s.kind, r, tick, s.pos, s.pairId));
    }

    private static <K> void remove(Map<K, List<Site>> m, K key, Site s) {
        List<Site> l = m.get(key);
        if (l != null) {
            l.remove(s);
            if (l.isEmpty()) {
                m.remove(key);
            }
        }
    }
}

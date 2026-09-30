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
        void retired(Site s, boolean restoreBlock);
    }

    private Params params;
    private final Hooks hooks;
    private final Map<UUID, List<Site>> byPlayer = new HashMap<>();
    private final Map<Pos, List<Site>> byPos = new HashMap<>();

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

    /** 플레이어 위치가 바뀌었을 때. */
    public void observePosition(UUID player, String world, double x, double y, double z, long tick) {
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
                retire(s, Result.VOID, tick, true);
            } else if (s.result == null) {
                if (d <= params.reactionRadius()) {
                    resolve(s, Result.HIT, tick);
                } else if (d > params.giveUpDistance()) {
                    // 판정만 하고 화면에서는 거두지 않는다: 진짜 광석은 멀어진다고 사라지지 않는다.
                    resolve(s, Result.MISS, tick);
                }
            }
        }
    }

    /** 플레이어가 블록을 캐려 할 때. 캐는 블록이 뭉치 반경 안이면 반응. 미끼는 이 직후 노출 처리로 거둬진다. */
    public void observeDig(UUID player, Pos block, long tick) {
        List<Site> list = byPlayer.get(player);
        if (list == null) {
            return;
        }
        for (Site s : new ArrayList<>(list)) {
            if (s.active && s.result == null && s.pos.world().equals(block.world())
                    && s.distanceTo(block.x() + 0.5, block.y() + 0.5, block.z() + 0.5) <= params.reactionRadius()) {
                resolve(s, Result.HIT, tick);
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
        touch(p, tick);
        for (int[] f : Pos.FACES) {
            touch(p.offset(f[0], f[1], f[2]), tick);
        }
    }

    private void touch(Pos p, long tick) {
        List<Site> list = byPos.get(p);
        if (list != null) {
            for (Site s : new ArrayList<>(list)) {
                retire(s, Result.VOID, tick, true);
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

    public void dropPlayer(UUID player, long tick, boolean restoreBlock) {
        List<Site> list = byPlayer.get(player);
        if (list != null) {
            for (Site s : new ArrayList<>(list)) {
                retire(s, Result.VOID, tick, restoreBlock);
            }
        }
    }

    /** 서버가 청크를 내렸다: 모든 플레이어의 그 청크에 걸친 뭉치를 거둔다(클라이언트도 청크를 버리므로 되돌릴 필요 없음). */
    public void dropChunk(String world, int cx, int cz, long tick) {
        for (Site s : allActive()) {
            if (s.touchesChunk(world, cx, cz)) {
                retire(s, Result.VOID, tick, false);
            }
        }
    }

    /** 이 플레이어의 클라이언트가 청크를 버렸다. */
    public void dropChunkFor(UUID player, String world, int cx, int cz, long tick) {
        for (Site s : new ArrayList<>(sitesOf(player))) {
            if (s.touchesChunk(world, cx, cz)) {
                retire(s, Result.VOID, tick, false);
            }
        }
    }

    public void dropAll(long tick) {
        for (Site s : allActive()) {
            retire(s, Result.VOID, tick, true);
        }
    }

    /** 자리를 거둔다. 판정 전이면 ifUnresolved(null이면 판정 없이)로 확정한다. */
    public void retire(Site s, Result ifUnresolved, long tick, boolean restoreBlock) {
        if (!s.active) {
            return;
        }
        if (s.result == null && ifUnresolved != null) {
            resolve(s, ifUnresolved, tick);
        }
        s.active = false;
        remove(byPlayer, s.player, s);
        for (Voxel v : s.voxels) {
            remove(byPos, v.pos(), s);
        }
        hooks.retired(s, restoreBlock);
    }

    private void resolve(Site s, Result r, long tick) {
        s.result = r;
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

package dev.anchormc.core;

import java.util.List;
import java.util.UUID;

/** 미끼 또는 위약 한 뭉치(블록 1개 이상). 종류(kind)만 다르고 상태와 수명은 같다. */
public final class Site {
    public final UUID player;
    public final String playerName;
    public final SiteKind kind;
    /** 대표 좌표(첫 블록). */
    public final Pos pos;
    public final List<Voxel> voxels;
    public final long pairId;
    public final long createdTick;
    /** 이 자리가 속한 계획 쌍(테스트용 단독 자리는 null). */
    final PlannedPair plan;
    Result result;
    boolean active = true;

    Site(UUID player, String playerName, SiteKind kind, List<Voxel> voxels, long pairId, long createdTick, PlannedPair plan) {
        this.player = player;
        this.playerName = playerName;
        this.kind = kind;
        this.voxels = List.copyOf(voxels);
        this.pos = voxels.get(0).pos();
        this.pairId = pairId;
        this.createdTick = createdTick;
        this.plan = plan;
    }

    /** 블록 하나짜리(테스트용). */
    Site(UUID player, String playerName, SiteKind kind, Pos pos, Host host, long createdTick) {
        this(player, playerName, kind, List.of(new Voxel(pos, host)), 0, createdTick, null);
    }

    /** 점에서 뭉치의 가장 가까운 블록 중심까지의 거리. */
    public double distanceTo(double px, double py, double pz) {
        double best = Double.POSITIVE_INFINITY;
        for (Voxel v : voxels) {
            best = Math.min(best, v.pos().distanceTo(px, py, pz));
        }
        return best;
    }

    public boolean touchesChunk(String world, int cx, int cz) {
        if (!pos.world().equals(world)) {
            return false;
        }
        for (Voxel v : voxels) {
            if (v.pos().chunkX() == cx && v.pos().chunkZ() == cz) {
                return true;
            }
        }
        return false;
    }

    /** 아직 판정 전이면 null. */
    public Result result() {
        return result;
    }

    public boolean active() {
        return active;
    }
}

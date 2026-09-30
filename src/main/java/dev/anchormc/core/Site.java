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
    /** 이 자리가 추적에 올라온 틱. */
    public final long registeredTick;
    /** 판정 창의 시작 틱: 올라온 틱에서 이 쌍이 앞서 노출된 시간을 뺀 값이라, 청크를 버렸다 다시 받아도 노출 시간이 이어진다. */
    public final long createdTick;
    /** 이 자리가 속한 계획 쌍(테스트용 단독 자리는 null). */
    final PlannedPair plan;
    Result result;
    /** 이 자리에서 반응(HIT)을 이미 알렸나(창 안의 HIT든 창이 끝난 뒤의 LATE_HIT든 한 번만). */
    boolean hitReported;
    boolean active = true;
    /** 거둘 때 남긴 사유(거두기 전에는 null). */
    Reason reason;
    /** 주기 검사가 이웃 청크를 몰라 판단을 보류 중이면 그 설명, 아니면 null. */
    String held;

    Site(UUID player, String playerName, SiteKind kind, List<Voxel> voxels, long pairId, long createdTick, PlannedPair plan) {
        this.player = player;
        this.playerName = playerName;
        this.kind = kind;
        this.voxels = List.copyOf(voxels);
        this.pos = voxels.get(0).pos();
        this.pairId = pairId;
        this.registeredTick = createdTick;
        this.createdTick = createdTick - (plan == null ? 0 : plan.exposureTicks());
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

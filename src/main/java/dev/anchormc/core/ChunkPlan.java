package dev.anchormc.core;

import java.util.List;
import java.util.UUID;

/** 한 (플레이어, 월드, 청크)의 계획과, 그 계획을 청크 패킷에 넣을 때 쓰는 결과물. */
public final class ChunkPlan {
    record Key(UUID player, String world, int cx, int cz, long visit) {
    }

    final List<PlannedPair> pairs;

    ChunkPlan(List<PlannedPair> pairs) {
        this.pairs = List.copyOf(pairs);
    }

    public List<PlannedPair> pairs() {
        return pairs;
    }

    /**
     * 청크 패킷에 넣을 것. decoyVoxels는 패킷 시야에서 DecoyGuard를 통과한 미끼 블록 전부(플러그인이 이것만 패킷에 쓴다),
     * pairs는 추적에 등록할 쌍이다.
     */
    public record Patch(UUID player, String world, int cx, int cz, List<Voxel> decoyVoxels, List<PlannedPair> pairs) {
        public boolean isEmpty() {
            return pairs.isEmpty();
        }
    }
}

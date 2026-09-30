package dev.anchormc.plugin;

import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import dev.anchormc.core.ChunkPlan;
import dev.anchormc.core.DecoyGuard;
import dev.anchormc.core.Voxel;

/**
 * 청크 데이터 패킷에 미끼 블록을 써 넣는다. 이 패킷은 이 플레이어 한 명에게 가는 것이고 서버 월드는 건드리지 않는다(불변식 4).
 * 미끼를 내보내는 유일한 곳이다: 엔진이 패킷 시야에서 DecoyGuard로 검사한 블록만 받아 쓴다.
 */
final class ChunkPatcher {
    private ChunkPatcher() {
    }

    /**
     * 패킷 데이터를 바꿨으면 true. 쓰기 전에 모든 위치가 패킷 안에 있고 돌 위인지 다시 확인하고, 하나라도 어긋나면 아무것도 쓰지 않는다
     * (일부만 써서 추적에 없는 미끼가 나가는 일이 없게).
     */
    static boolean apply(Column column, int minY, PacketStateTable table, ChunkPlan.Patch patch) {
        PacketChunkView view = new PacketChunkView(column, minY, table);
        for (Voxel v : patch.decoyVoxels()) {
            int x = v.pos().x(), y = v.pos().y(), z = v.pos().z();
            int s = view.sectionIndex(x, y, z);
            if (s < 0 || view.section(s) == null || view.hostAt(x, y, z) == null
                    || !DecoyGuard.sealed(view, v.pos())) {
                return false;
            }
        }
        if (patch.decoyVoxels().isEmpty()) {
            return false;
        }
        for (Voxel v : patch.decoyVoxels()) {
            int x = v.pos().x(), y = v.pos().y(), z = v.pos().z();
            view.section(view.sectionIndex(x, y, z)).set(x & 15, (y - minY) & 15, z & 15, table.decoyId(v.host()));
        }
        return true;
    }
}

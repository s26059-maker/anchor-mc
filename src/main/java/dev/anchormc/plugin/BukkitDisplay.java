package dev.anchormc.plugin;

import dev.anchormc.core.Display;
import dev.anchormc.core.Host;
import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;
import io.papermc.paper.math.Position;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 한 플레이어에게만 보낸다. 뭉치 전체를 sendMultiBlockChange 한 번으로 보내면 청크 섹션마다 패킷 하나가 나간다.
 * 서버 월드는 바꾸지 않는다(불변식 4).
 */
final class BukkitDisplay implements Display {
    @Override
    public void show(UUID player, List<Voxel> voxels) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || voxels.isEmpty()) {
            return;
        }
        World w = Bukkit.getWorld(voxels.get(0).pos().world());
        if (w == null || !p.getWorld().equals(w)) {
            return;
        }
        Map<Position, BlockData> changes = new HashMap<>();
        for (Voxel v : voxels) {
            Material ore = v.host() == Host.DEEPSLATE ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
            changes.put(Position.block(v.pos().x(), v.pos().y(), v.pos().z()), ore.createBlockData());
        }
        p.sendMultiBlockChange(changes);
    }

    @Override
    public void hide(UUID player, List<Pos> positions) {
        Player p = Bukkit.getPlayer(player);
        if (p == null || positions.isEmpty()) {
            return;
        }
        World w = Bukkit.getWorld(positions.get(0).world());
        if (w == null || !p.getWorld().equals(w)) {
            return;
        }
        // 그 순간의 진짜 블록을 보낸다. 로드 안 된 청크는 건너뛴다.
        Map<Position, BlockData> changes = new HashMap<>();
        for (Pos pos : positions) {
            if (w.isChunkLoaded(pos.chunkX(), pos.chunkZ())) {
                changes.put(Position.block(pos.x(), pos.y(), pos.z()), w.getBlockData(pos.x(), pos.y(), pos.z()));
            }
        }
        if (!changes.isEmpty()) {
            p.sendMultiBlockChange(changes);
        }
    }
}

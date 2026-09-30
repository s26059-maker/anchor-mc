package dev.anchormc.plugin;

import dev.anchormc.core.Display;
import dev.anchormc.core.Host;
import dev.anchormc.core.Pos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.UUID;

/** sendBlockChange로 한 플레이어에게만 보낸다. 서버 월드는 바꾸지 않는다(불변식 4). */
final class BukkitDisplay implements Display {
    @Override
    public void show(UUID player, Pos pos, Host host) {
        Player p = Bukkit.getPlayer(player);
        World w = Bukkit.getWorld(pos.world());
        if (p == null || w == null || !p.getWorld().equals(w)) {
            return;
        }
        Material ore = host == Host.DEEPSLATE ? Material.DEEPSLATE_DIAMOND_ORE : Material.DIAMOND_ORE;
        p.sendBlockChange(new Location(w, pos.x(), pos.y(), pos.z()), ore.createBlockData());
    }

    @Override
    public void hide(UUID player, Pos pos) {
        Player p = Bukkit.getPlayer(player);
        World w = Bukkit.getWorld(pos.world());
        if (p == null || w == null || !p.getWorld().equals(w) || !w.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
            return;
        }
        // 그 순간의 진짜 블록을 보낸다.
        p.sendBlockChange(new Location(w, pos.x(), pos.y(), pos.z()), w.getBlockData(pos.x(), pos.y(), pos.z()));
    }
}

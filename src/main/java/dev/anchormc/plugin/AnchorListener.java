package dev.anchormc.plugin;

import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Pos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.List;

/**
 * Bukkit 이벤트를 엔진 호출로 옮긴다. 블록이 바뀌는 이벤트는 전부 "변경 전"에 불리므로(MONITOR는 결과 확정 후, 적용 전)
 * 노출되기 전에 미끼를 거둘 수 있다. 취소된 이벤트는 무시한다.
 */
final class AnchorListener implements Listener {
    private final DecoyEngine engine;

    AnchorListener(DecoyEngine engine) {
        this.engine = engine;
    }

    private static long now() {
        return Bukkit.getCurrentTick();
    }

    private static Pos pos(Block b) {
        return new Pos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }

    private void changing(Block b) {
        engine.onBlockChanging(pos(b), now());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent e) {
        Location to = e.getTo();
        Location from = e.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockY() == from.getBlockY() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        engine.onMove(e.getPlayer().getUniqueId(), to.getWorld().getName(), to.getX(), to.getY(), to.getZ(), now());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent e) {
        engine.onPlayerBreak(e.getPlayer().getUniqueId(), pos(e.getBlock()), now());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent e) {
        changing(e.getBlock());
        e.blockList().forEach(this::changing);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent e) {
        e.blockList().forEach(this::changing);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFlow(BlockFromToEvent e) {
        changing(e.getToBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent e) {
        piston(e.getBlock(), e.getDirection(), e.getBlocks());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent e) {
        piston(e.getBlock(), e.getDirection(), e.getBlocks());
    }

    private void piston(Block piston, BlockFace dir, List<Block> moved) {
        changing(piston);
        changing(piston.getRelative(dir)); // 머리가 놓이는 자리
        for (Block b : moved) {
            changing(b);
            changing(b.getRelative(dir)); // 밀려 들어가는 자리
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent e) {
        changing(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBurn(BlockBurnEvent e) {
        changing(e.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFade(BlockFadeEvent e) {
        changing(e.getBlock());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        engine.dropChunk(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ(), now());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        engine.dropPlayer(e.getPlayer().getUniqueId(), now(), false);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        engine.dropPlayer(e.getPlayer().getUniqueId(), now(), false);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        engine.dropPlayer(p.getUniqueId(), now(), true);
    }
}

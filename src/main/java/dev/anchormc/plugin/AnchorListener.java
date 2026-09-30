package dev.anchormc.plugin;

import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Pos;
import io.papermc.paper.event.packet.PlayerChunkLoadEvent;
import io.papermc.paper.event.packet.PlayerChunkUnloadEvent;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
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
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;

import java.util.List;

/**
 * Bukkit 이벤트를 엔진 호출로 옮긴다. 블록이 바뀌는 이벤트는 전부 "변경 전"에 불리므로(MONITOR는 결과 확정 후, 적용 전)
 * 노출되기 전에 미끼를 거둘 수 있다. 취소된 이벤트는 무시한다.
 */
final class AnchorListener implements Listener {
    private final DecoyEngine engine;
    private final PlayerRegistry registry;
    private final Plugin plugin;

    AnchorListener(DecoyEngine engine, PlayerRegistry registry, Plugin plugin) {
        this.engine = engine;
        this.registry = registry;
        this.plugin = plugin;
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

    /**
     * 플레이어에게 청크가 전송될 때. 1.2단계부터 미끼는 여기서 만들지 않는다(청크 데이터 패킷 안에 직접 들어간다, PacketDecoyListener).
     * 여기서는 로드된 청크에서 진짜 광맥 표본을 배우기만 한다(메인 스레드, 표본이 다 모이면 바로 돌아간다).
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkSent(PlayerChunkLoadEvent e) {
        engine.learnChunk(e.getChunk().getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ());
    }

    /** 플레이어의 클라이언트가 청크를 버렸다: 그 청크의 자리는 되돌릴 필요 없이 정리한다(다시 받으면 같은 미끼가 패킷에 들어간다). */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkDropped(PlayerChunkUnloadEvent e) {
        engine.dropChunkFor(e.getPlayer().getUniqueId(), e.getChunk().getWorld().getName(),
                e.getChunk().getX(), e.getChunk().getZ(), now());
    }

    /** 서버가 청크를 로드했다: 그 청크를 몰라 판단을 보류하던 이웃 자리가 실제로 노출됐는지 바로 다시 본다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerChunkLoad(ChunkLoadEvent e) {
        engine.onChunkLoaded(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ(), now());
    }

    @EventHandler
    public void onChunkUnload(ChunkUnloadEvent e) {
        engine.dropChunk(e.getWorld().getName(), e.getChunk().getX(), e.getChunk().getZ(), now());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        registry.update(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        registry.remove(e.getPlayer().getUniqueId());
        engine.forgetPlayer(e.getPlayer().getUniqueId(), now());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onWorldChange(PlayerChangedWorldEvent e) {
        registry.update(e.getPlayer());
        engine.dropPlayer(e.getPlayer().getUniqueId(), now(), false);
    }

    @EventHandler
    public void onGameMode(PlayerGameModeChangeEvent e) {
        Player p = e.getPlayer();
        GameMode gm = e.getNewGameMode();
        boolean eligible = registry.eligibleMode(gm);
        registry.set(p.getUniqueId(), p.getWorld().getName(), eligible);
        engine.setEvidenceExcluded(p.getUniqueId(), gm == GameMode.SPECTATOR);
        if (!eligible) {
            engine.dropPlayer(p.getUniqueId(), now(), true);
        } else {
            recoverSoon(p); // 자격이 있는 모드로 (다시) 바뀌었다: 바뀐 뒤의 상태로 다시 보여 준다
        }
    }

    /** 리스폰: 자격을 되찾는 순간이다. 죽어 있는 동안 거둔 미끼를, 클라이언트가 아직 가진 청크에 다시 보여 준다. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent e) {
        recoverSoon(e.getPlayer());
    }

    /** 이벤트 시점에는 게임모드·생사가 아직 옛 값이라, 한 틱 뒤 메인 스레드에서 표를 고치고 엔진에 알린다(엔진이 복구한다). */
    private void recoverSoon(Player p) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (p.isOnline()) {
                registry.update(p);
                engine.tick(registry.stateOf(p), now());
            }
        });
    }
}

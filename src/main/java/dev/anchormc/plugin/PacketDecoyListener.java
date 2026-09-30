package dev.anchormc.plugin;

import com.github.retrooper.packetevents.event.PacketListenerAbstract;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;
import com.github.retrooper.packetevents.protocol.player.User;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerChunkData;
import dev.anchormc.core.ChunkPlan;
import dev.anchormc.core.DecoyEngine;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * 서버가 플레이어에게 청크 데이터 패킷을 보낼 때 가로채 미끼 광맥을 패킷 안의 블록 데이터로 바꿔 넣는다(패킷을 만드는 스레드에서 실행).
 * 서버 월드는 읽지도 바꾸지도 않는다. 추적 등록은 메인 스레드로 넘긴다.
 * 이 리스너가 실패해도 청크 전송을 막지 않는다(수정하지 않은 패킷이 그대로 나간다).
 */
final class PacketDecoyListener extends PacketListenerAbstract {
    private final Plugin plugin;
    private final DecoyEngine engine;
    private final PacketStateTable table;
    private final PlayerRegistry registry;
    private final AtomicLong patched = new AtomicLong(), failures = new AtomicLong();

    PacketDecoyListener(Plugin plugin, DecoyEngine engine, PacketStateTable table, PlayerRegistry registry) {
        super(PacketListenerPriority.NORMAL);
        this.plugin = plugin;
        this.engine = engine;
        this.table = table;
        this.registry = registry;
    }

    long patchedChunks() {
        return patched.get();
    }

    long failures() {
        return failures.get();
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (event.getPacketType() != PacketType.Play.Server.CHUNK_DATA) {
            return;
        }
        User user = event.getUser();
        UUID id = user == null ? null : user.getUUID();
        PlayerRegistry.Info info = id == null ? null : registry.get(id);
        if (info == null || !info.eligible()) {
            return; // 자격 없는 플레이어(크리에이티브·관전)나 아직 모르는 플레이어에게는 넣지 않는다
        }
        try {
            WrapperPlayServerChunkData wrapper = new WrapperPlayServerChunkData(event);
            Column column = wrapper.getColumn();
            int minY = user.getDimensionType().getMinY();
            PacketChunkView view = new PacketChunkView(column, minY, table);
            ChunkPlan.Patch patch = engine.prepareChunk(id, info.world(), column.getX(), column.getZ(), view);
            if (patch.isEmpty() || !ChunkPatcher.apply(column, minY, table, patch)) {
                return;
            }
            event.markForReEncode(true);
            patched.incrementAndGet();
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player p = Bukkit.getPlayer(id);
                engine.registerChunk(p == null ? PlayerRegistry.gone(id, info) : PlayerRegistry.stateOf(p), patch, Bukkit.getCurrentTick());
            });
        } catch (Throwable t) {
            if (failures.incrementAndGet() <= 3) {
                plugin.getLogger().log(Level.WARNING, "청크 패킷 미끼 삽입 실패(패킷은 수정 없이 나간다): " + t);
            }
        }
    }
}

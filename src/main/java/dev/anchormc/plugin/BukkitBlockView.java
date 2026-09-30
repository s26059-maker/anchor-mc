package dev.anchormc.plugin;

import dev.anchormc.core.BlockView;
import dev.anchormc.core.Host;
import org.bukkit.Material;
import org.bukkit.World;

import java.util.EnumSet;
import java.util.Set;

/** 서버 월드를 읽기만 한다. 로드되지 않은 청크는 절대 로드하지 않고 "고체 아님"으로 본다. */
final class BukkitBlockView implements BlockView {
    /** 불투명이어도 피스톤·폭발 등으로 사라질 수 있는 블록. */
    static final Set<Material> UNSTABLE = EnumSet.of(
            Material.PISTON, Material.STICKY_PISTON, Material.PISTON_HEAD, Material.MOVING_PISTON,
            Material.TNT);

    private final World world;

    BukkitBlockView(World world) {
        this.world = world;
    }

    private Material typeAt(int x, int y, int z) {
        if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return world.getType(x, y, z);
    }

    @Override
    public boolean isStableOpaque(int x, int y, int z) {
        Material m = typeAt(x, y, z);
        return m != null && m.isBlock() && m.isOccluding() && !m.hasGravity() && !UNSTABLE.contains(m);
    }

    @Override
    public boolean isDiamondOre(int x, int y, int z) {
        Material m = typeAt(x, y, z);
        return m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE;
    }

    @Override
    public String describe(int x, int y, int z) {
        Material m = typeAt(x, y, z);
        return m == null ? "블록=(읽을 수 없음: 월드 밖·청크 로드 안 됨)"
                : "블록=" + m.name() + (m.isOccluding() ? "" : "(isOccluding=false)");
    }

    @Override
    public boolean chunkLoaded(int cx, int cz) {
        return world.isChunkLoaded(cx, cz);
    }

    @Override
    public Host hostAt(int x, int y, int z) {
        Material m = typeAt(x, y, z);
        if (m == Material.STONE) {
            return Host.STONE;
        }
        if (m == Material.DEEPSLATE) {
            return Host.DEEPSLATE;
        }
        return null;
    }
}

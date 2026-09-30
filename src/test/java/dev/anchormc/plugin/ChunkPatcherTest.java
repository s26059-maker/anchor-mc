package dev.anchormc.plugin;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.stream.NetStreamOutput;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import dev.anchormc.core.ChunkPlan;
import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Display;
import dev.anchormc.core.Host;
import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 실제 PacketEvents 청크 객체(Column·Chunk_v1_18)로 청크 패킷 수정 경로를 검증한다. */
class ChunkPatcherTest {
    static final ClientVersion V;

    static {
        PacketEventsTestSupport.init();
        V = ClientVersion.V_26_2;
    }

    static final int MIN_Y = -64, SECTIONS = 24;
    static final String W = "world";
    static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");

    static int id(StateType t) {
        return WrappedBlockState.getDefaultState(V, t).getGlobalId();
    }

    static PacketStateTable table() {
        return new PacketStateTable(V, name -> switch (name) {
            case "stone" -> PacketStateTable.OPAQUE | PacketStateTable.STONE;
            case "deepslate" -> PacketStateTable.OPAQUE | PacketStateTable.DEEPSLATE;
            case "diamond_ore", "deepslate_diamond_ore" -> PacketStateTable.OPAQUE | PacketStateTable.DIAMOND;
            case "dirt", "granite", "andesite", "coal_ore", "iron_ore" -> PacketStateTable.OPAQUE;
            default -> 0; // 공기, 자갈(중력 블록) 등
        });
    }

    static RandomGenerator rng(long seed) {
        return RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(seed);
    }

    /** 돌·심층암 바탕에 공기 동굴, 자갈, 잡광석, 진짜 다이아 광석을 섞은 현실적인 지형. */
    static Column terrain(int cx, int cz, long seed) {
        RandomGenerator r = rng(seed);
        int stone = id(StateTypes.STONE), deep = id(StateTypes.DEEPSLATE), air = id(StateTypes.AIR);
        int[] filler = {id(StateTypes.DIRT), id(StateTypes.GRAVEL), id(StateTypes.GRANITE), id(StateTypes.ANDESITE),
                id(StateTypes.COAL_ORE), id(StateTypes.IRON_ORE)};
        int diamond = id(StateTypes.DIAMOND_ORE);
        BaseChunk[] sections = new BaseChunk[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) {
            Chunk_v1_18 c = new Chunk_v1_18();
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int wy = MIN_Y + s * 16 + y;
                        int v;
                        double roll = r.nextDouble();
                        if (wy > 16) {
                            v = air;
                        } else if (roll < 0.04) {
                            v = air;
                        } else if (roll < 0.09) {
                            v = filler[r.nextInt(filler.length)];
                        } else if (roll < 0.0905) {
                            v = diamond;
                        } else {
                            v = wy < 0 ? deep : stone;
                        }
                        c.set(x, y, z, v);
                    }
                }
            }
            sections[s] = c;
        }
        return new Column(cx, cz, true, sections, new TileEntity[0]);
    }

    static int[] snapshot(Column col) {
        int[] out = new int[SECTIONS * 4096];
        int i = 0;
        for (BaseChunk s : col.getChunks()) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        out[i++] = s.getBlockId(x, y, z);
                    }
                }
            }
        }
        return out;
    }

    static int index(int lx, int wy, int lz) {
        int s = (wy - MIN_Y) >> 4, ly = (wy - MIN_Y) & 15;
        return s * 4096 + (ly * 16 + lz) * 16 + lx;
    }

    static byte[] encode(Column col) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        NetStreamOutput out = new NetStreamOutput(bos);
        for (BaseChunk s : col.getChunks()) {
            Chunk_v1_18.write(out, (Chunk_v1_18) s);
        }
        return bos.toByteArray();
    }

    static DecoyEngine engine(long seed, double pairsPerChunk) {
        var p = new dev.anchormc.core.Params(3.0, 100_000, 160.0, 2.0, -64, 16, 60, pairsPerChunk, 0);
        return new DecoyEngine(p, w -> null, new Display() {
            public void show(UUID pl, List<Voxel> v) { }
            public void hide(UUID pl, List<Pos> ps) { }
        }, o -> { }, rng(seed));
    }

    @Test
    void patchWritesExactlyThePlannedDecoyBlocksOnlyOverSealedStoneAndTouchesNothingElse() {
        PacketStateTable table = table();
        int totalDecoys = 0, multiBlock = 0;
        for (long seed = 1; seed <= 25; seed++) {
            Column col = terrain(3, -2, seed);
            int[] before = snapshot(col);
            DecoyEngine e = engine(seed, 2.0);
            ChunkPlan.Patch patch = e.prepareChunk(PLAYER, W, 3, -2, new PacketChunkView(col, MIN_Y, table));
            if (patch.decoyVoxels().isEmpty()) {
                continue;
            }
            assertTrue(ChunkPatcher.apply(col, MIN_Y, table, patch));
            int[] after = snapshot(col);
            Set<Integer> planned = new HashSet<>();
            for (Voxel v : patch.decoyVoxels()) {
                int i = index(v.pos().x() & 15, v.pos().y(), v.pos().z() & 15);
                planned.add(i);
                assertEquals(3, v.pos().chunkX());
                assertEquals(-2, v.pos().chunkZ());
                int flags = table.flags(before[i]);
                assertTrue((flags & (PacketStateTable.STONE | PacketStateTable.DEEPSLATE)) != 0, "돌이 아닌 곳을 바꿨다");
                assertEquals(table.decoyId(v.host()), after[i]);
                // 바꾸기 전 데이터에서 여섯 면이 전부 안정적인 불투명 블록이었다(불변식 1, 패킷 시야)
                for (int[] f : dev.anchormc.core.Pos.FACES) {
                    int nx = (v.pos().x() & 15) + f[0], nz = (v.pos().z() & 15) + f[2], ny = v.pos().y() + f[1];
                    assertTrue(nx >= 0 && nx < 16 && nz >= 0 && nz < 16, "청크 경계 밖 이웃에 기댄 미끼");
                    assertTrue((table.flags(before[index(nx, ny, nz)]) & PacketStateTable.OPAQUE) != 0, "노출 가능한 미끼");
                }
            }
            for (int i = 0; i < before.length; i++) {
                if (!planned.contains(i)) {
                    assertEquals(before[i], after[i], "계획에 없는 블록이 바뀌었다");
                }
            }
            // 위약은 패킷에 없다
            for (var pair : patch.pairs()) {
                for (Voxel v : pair.placebo()) {
                    int i = index(v.pos().x() & 15, v.pos().y(), v.pos().z() & 15);
                    assertEquals(before[i], after[i], "위약 자리가 패킷에서 바뀌었다");
                }
            }
            totalDecoys += patch.decoyVoxels().size();
            if (patch.decoyVoxels().size() > patch.pairs().size()) {
                multiBlock++;
            }
        }
        assertTrue(totalDecoys > 100, "" + totalDecoys);
        assertTrue(multiBlock > 5, "광맥 뭉치(2블록 이상)가 나와야 한다: " + multiBlock);
    }

    @Test
    void receivingTheSameChunkAgainGivesAByteIdenticalPatchedChunk() {
        PacketStateTable table = table();
        DecoyEngine e = engine(5, 3.0);
        byte[] first = null;
        for (int visit = 0; visit < 3; visit++) {
            Column col = terrain(0, 0, 77);
            ChunkPlan.Patch patch = e.prepareChunk(PLAYER, W, 0, 0, new PacketChunkView(col, MIN_Y, table));
            assertTrue(ChunkPatcher.apply(col, MIN_Y, table, patch));
            byte[] now = encode(col);
            if (first == null) {
                first = now;
            } else {
                org.junit.jupiter.api.Assertions.assertArrayEquals(first, now, "다시 받은 청크의 패킷 데이터가 달라졌다");
            }
        }
        // 다른 플레이어에게는 다른 자리
        Column col = terrain(0, 0, 77);
        ChunkPlan.Patch other = e.prepareChunk(UUID.randomUUID(), W, 0, 0, new PacketChunkView(col, MIN_Y, table));
        ChunkPatcher.apply(col, MIN_Y, table, other);
        assertFalse(java.util.Arrays.equals(first, encode(col)), "다른 플레이어가 같은 자리를 받았다");
    }

    @Test
    void writerRefusesAPatchThatTheGuardWouldRejectAndChangesNothing() {
        PacketStateTable table = table();
        Column col = terrain(0, 0, 9);
        int[] before = snapshot(col);
        // 공기와 붙은 자리(봉인 아님)를 미끼로 우기는 조작된 패치
        List<Voxel> bad = new ArrayList<>();
        int air = id(StateTypes.AIR);
        for (int y = 17; y < 30; y++) { // 지표 위 공기
            bad.add(new Voxel(new Pos(W, 5, y, 5), Host.STONE));
        }
        ChunkPlan.Patch forged = new ChunkPlan.Patch(PLAYER, W, 0, 0, bad, List.of());
        assertFalse(ChunkPatcher.apply(col, MIN_Y, table, forged));
        // 한 곳이라도 나쁘면 좋은 곳도 쓰지 않는다
        PacketChunkView view = new PacketChunkView(col, MIN_Y, table);
        Voxel good = null;
        outer:
        for (int x = 2; x < 14; x++) {
            for (int z = 2; z < 14; z++) {
                for (int y = -30; y < 10; y++) {
                    if (dev.anchormc.core.DecoyGuard.sealed(view, new Pos(W, x, y, z)) && view.hostAt(x, y, z) != null) {
                        good = new Voxel(new Pos(W, x, y, z), view.hostAt(x, y, z));
                        break outer;
                    }
                }
            }
        }
        assertTrue(good != null);
        List<Voxel> mixed = new ArrayList<>(List.of(good));
        mixed.add(bad.get(0));
        assertFalse(ChunkPatcher.apply(col, MIN_Y, table, new ChunkPlan.Patch(PLAYER, W, 0, 0, mixed, List.of())));
        org.junit.jupiter.api.Assertions.assertArrayEquals(before, snapshot(col), "거부했는데 데이터가 바뀌었다");
        assertEquals(id(StateTypes.AIR), air);
    }

    @Test
    void patchedChunkGrowsByOnlyAFewBytesAndStaysDecodable() {
        PacketStateTable table = table();
        Column col = terrain(1, 1, 3);
        int before = encode(col).length;
        DecoyEngine e = engine(3, 4.0);
        ChunkPlan.Patch patch = e.prepareChunk(PLAYER, W, 1, 1, new PacketChunkView(col, MIN_Y, table));
        assertTrue(ChunkPatcher.apply(col, MIN_Y, table, patch));
        int after = encode(col).length;
        assertTrue(after >= before);
        // 패킷을 그대로 다시 읽으면 미끼가 있는 상태 그대로 나온다
        for (Voxel v : patch.decoyVoxels()) {
            int s = (v.pos().y() - MIN_Y) >> 4;
            assertEquals(table.decoyId(v.host()), col.getChunks()[s].getBlockId(v.pos().x() & 15, (v.pos().y() - MIN_Y) & 15, v.pos().z() & 15));
        }
        assertTrue(after - before < 20_000, "청크 하나가 너무 커졌다: " + (after - before));
    }
}

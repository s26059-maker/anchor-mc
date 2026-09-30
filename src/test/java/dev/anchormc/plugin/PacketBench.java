package dev.anchormc.plugin;

import com.github.retrooper.packetevents.protocol.stream.NetStreamInput;
import com.github.retrooper.packetevents.protocol.stream.NetStreamOutput;
import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import com.github.retrooper.packetevents.protocol.world.chunk.TileEntity;
import com.github.retrooper.packetevents.protocol.world.chunk.impl.v_1_18.Chunk_v1_18;
import com.github.retrooper.packetevents.protocol.world.states.type.StateType;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import dev.anchormc.core.ChunkPlan;
import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Params;
import dev.anchormc.core.PlannedPair;
import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.zip.Deflater;

import static dev.anchormc.plugin.ChunkPatcherTest.MIN_Y;
import static dev.anchormc.plugin.ChunkPatcherTest.SECTIONS;
import static dev.anchormc.plugin.ChunkPatcherTest.id;
import static dev.anchormc.plugin.ChunkPatcherTest.rng;

/**
 * 청크 데이터 패킷 수정의 CPU와 추가 바이트를 실제 PacketEvents 청크 객체(Chunk_v1_18)로 잰다. 서버·netty 없이 도는 값이다:
 * 디코드(청크 섹션 읽기) → 미끼 계획·패킷 시야 검사 → 패킷에 쓰기 → 인코드(다시 쓰기)를 한 청크에 대해 순서대로 돈다.
 * 실행: gradlew runBench
 */
public final class PacketBench {
    private PacketBench() {
    }

    static StateType[] fillers() {
        PacketEventsTestSupport.init();
        return new StateType[] {
            StateTypes.DIRT, StateTypes.GRANITE, StateTypes.ANDESITE, StateTypes.DIORITE, StateTypes.COAL_ORE, StateTypes.IRON_ORE,
            StateTypes.COPPER_ORE, StateTypes.GOLD_ORE, StateTypes.REDSTONE_ORE, StateTypes.LAPIS_ORE, StateTypes.TUFF,
            StateTypes.CALCITE, StateTypes.DRIPSTONE_BLOCK, StateTypes.SMOOTH_BASALT, StateTypes.COBBLESTONE, StateTypes.CLAY,
            StateTypes.MUD, StateTypes.BASALT, StateTypes.BLACKSTONE, StateTypes.OBSIDIAN, StateTypes.SANDSTONE};
    }

    static PacketStateTable table() {
        Set<String> opaque = new HashSet<>();
        for (StateType t : fillers()) {
            String n = t.getName();
            opaque.add(n.substring(n.indexOf(':') + 1));
        }
        return new PacketStateTable(ChunkPatcherTest.V, name -> {
            switch (name) {
                case "stone":
                    return PacketStateTable.OPAQUE | PacketStateTable.STONE;
                case "deepslate":
                    return PacketStateTable.OPAQUE | PacketStateTable.DEEPSLATE;
                case "diamond_ore":
                case "deepslate_diamond_ore":
                    return PacketStateTable.OPAQUE | PacketStateTable.DIAMOND;
                default:
                    return opaque.contains(name) ? PacketStateTable.OPAQUE : 0;
            }
        });
    }

    /**
     * 섹션마다 (공기 포함) 정확히 distinct종류의 블록 상태가 들어 있는 지형. y ≤ 16만 채우고 그 위는 공기.
     * withDiamond면 진짜 다이아 광석이 이미 섹션 팔레트에 있다.
     */
    static Column terrain(int cx, int cz, long seed, int distinct, boolean withDiamond) {
        RandomGenerator r = rng(seed);
        int stone = id(StateTypes.STONE), deep = id(StateTypes.DEEPSLATE), air = id(StateTypes.AIR);
        List<Integer> extras = new ArrayList<>();
        if (withDiamond) {
            extras.add(id(StateTypes.DIAMOND_ORE));
        }
        for (StateType t : fillers()) {
            if (extras.size() >= distinct - 2) {
                break;
            }
            extras.add(id(t));
        }
        BaseChunk[] sections = new BaseChunk[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) {
            Chunk_v1_18 c = new Chunk_v1_18();
            int base = MIN_Y + s * 16 < 0 ? deep : stone;
            boolean solidSection = MIN_Y + s * 16 <= 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        int v = air;
                        if (solidSection) {
                            double roll = r.nextDouble();
                            v = roll < 0.03 ? air : roll < 0.03 + 0.05 * extras.size() / 20.0 ? extras.get(r.nextInt(extras.size())) : base;
                        }
                        c.set(x, y, z, v);
                    }
                }
            }
            if (solidSection) { // 섹션마다 종류가 정확히 distinct개가 되게 빠진 종류를 하나씩 심는다
                c.set(1, 1, 1, air);
                for (int i = 0; i < extras.size(); i++) {
                    c.set(2 + i % 12, 2 + i / 12, 2, extras.get(i));
                }
            }
            sections[s] = c;
        }
        return new Column(cx, cz, true, sections, new TileEntity[0]);
    }

    static byte[] encode(Column col) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(64 * 1024);
        NetStreamOutput out = new NetStreamOutput(bos);
        for (BaseChunk s : col.getChunks()) {
            Chunk_v1_18.write(out, (Chunk_v1_18) s);
        }
        return bos.toByteArray();
    }

    static Column decode(byte[] bytes, int cx, int cz) {
        NetStreamInput in = new NetStreamInput(new ByteArrayInputStream(bytes));
        BaseChunk[] sections = new BaseChunk[SECTIONS];
        for (int s = 0; s < SECTIONS; s++) {
            sections[s] = Chunk_v1_18.read(in);
        }
        return new Column(cx, cz, true, sections, new TileEntity[0]);
    }

    static int deflated(byte[] data) {
        Deflater d = new Deflater(Deflater.DEFAULT_COMPRESSION);
        d.setInput(data);
        d.finish();
        byte[] buf = new byte[data.length + 1024];
        int n = d.deflate(buf);
        d.end();
        return n;
    }

    /** 1.1단계 방식: 같은 미끼를 청크 섹션별 블록 갱신 패킷으로 보낼 때의 바이트(길이 접두 포함, 압축 전). 패킷 수도 돌려준다. */
    static int[] blockUpdateBytes(List<PlannedPair> pairs, PacketStateTable table) {
        int bytes = 0, packets = 0;
        for (PlannedPair pp : pairs) {
            java.util.Map<Long, List<Voxel>> bySection = new java.util.LinkedHashMap<>();
            for (Voxel v : pp.decoy()) {
                long key = ((long) (v.pos().x() >> 4) << 42) ^ ((long) (v.pos().z() >> 4) << 20) ^ ((v.pos().y() >> 4) + 64);
                bySection.computeIfAbsent(key, k -> new ArrayList<>()).add(v);
            }
            for (List<Voxel> group : bySection.values()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                NetStreamOutput out = new NetStreamOutput(bos);
                out.writeVarInt(0x4B); // 패킷 번호(1바이트로 취급)
                out.writeLong(0); // 섹션 위치
                out.writeVarInt(group.size());
                for (Voxel v : group) {
                    long entry = ((long) table.decoyId(v.host()) << 12) | ((v.pos().x() & 15) << 8) | ((v.pos().z() & 15) << 4) | (v.pos().y() & 15);
                    out.writeVarLong(entry);
                }
                int len = bos.size();
                ByteArrayOutputStream pre = new ByteArrayOutputStream();
                new NetStreamOutput(pre).writeVarInt(len);
                bytes += len + pre.size();
                packets++;
            }
        }
        return new int[] {bytes, packets};
    }

    static double mean(long[] a) {
        return Arrays.stream(a).average().orElse(0);
    }

    static long pct(long[] a, double q) {
        long[] s = a.clone();
        Arrays.sort(s);
        return s[(int) Math.min(s.length - 1, Math.floor(q * s.length))];
    }

    static DecoyEngine engine(long seed, double ppc) {
        var p = new Params(3.0, 100_000, 160.0, 2.0, -64, 16, 60, ppc, 0);
        return new DecoyEngine(p, w -> null, new dev.anchormc.core.Display() {
            public void show(UUID pl, List<Voxel> v) { }

            public void hide(UUID pl, List<Pos> ps) { }
        }, o -> { }, rng(seed));
    }

    public static void main(String[] args) {
        PacketEventsTestSupport.init();
        PacketStateTable table = table();
        System.out.println("## 청크 패킷 수정 비용(실제 PacketEvents Chunk_v1_18, 서버·netty 없음, JVM 워밍업 후)");
        // ---- CPU ----
        int pool = 24;
        byte[][] templates = new byte[pool][];
        for (int i = 0; i < pool; i++) {
            templates[i] = encode(terrain(i, 0, 100 + i, 10, true));
        }
        System.out.printf(Locale.ROOT, "지형: 섹션 24개(y −64..320, y ≤ 16만 채움), 섹션당 블록 상태 10종, 인코드 크기 평균 %.0f바이트(압축 전) / %.0f(deflate).%n",
                Arrays.stream(templates).mapToInt(b -> b.length).average().orElse(0), Arrays.stream(templates).mapToInt(PacketBench::deflated).average().orElse(0));
        for (double ppc : new double[] {1, 2, 4, 8}) {
            int warm = 1500, n = 4000;
            long[] dec = new long[n], plan = new long[n], rev = new long[n], app = new long[n], enc = new long[n];
            int patchedChunks = 0;
            for (int it = 0; it < warm + n; it++) {
                int t = it % pool;
                DecoyEngine e = engine(it, ppc);
                UUID pl = new UUID(1, it);
                long t0 = System.nanoTime();
                Column col = decode(templates[t], t, 0);
                long t1 = System.nanoTime();
                ChunkPlan.Patch patch = e.prepareChunk(pl, "world", t, 0, new PacketChunkView(col, MIN_Y, table));
                long t2 = System.nanoTime();
                ChunkPlan.Patch again = e.prepareChunk(pl, "world", t, 0, new PacketChunkView(col, MIN_Y, table));
                long t3 = System.nanoTime();
                boolean did = !patch.isEmpty() && ChunkPatcher.apply(col, MIN_Y, table, patch);
                long t4 = System.nanoTime();
                byte[] out = encode(col);
                long t5 = System.nanoTime();
                if (again.pairs().size() != patch.pairs().size() || out.length == 0) {
                    throw new IllegalStateException("재방문 계획이 다르다");
                }
                if (it >= warm) {
                    int k = it - warm;
                    dec[k] = t1 - t0;
                    plan[k] = t2 - t1;
                    rev[k] = t3 - t2;
                    app[k] = t4 - t3;
                    enc[k] = t5 - t4;
                    if (did) {
                        patchedChunks++;
                    }
                }
            }
            long[] total = new long[n];
            for (int k = 0; k < n; k++) {
                total[k] = dec[k] + plan[k] + app[k] + enc[k];
            }
            System.out.printf(Locale.ROOT, "청크당 쌍 %.0f: 디코드 %.0f µs, 계획+패킷 시야 검사(처음) %.0f µs, 같은 청크 다시 받음(계획 기억) %.0f µs, 패킷에 쓰기 %.0f µs, 인코드 %.0f µs → 처음 받는 청크 합계 평균 %.0f µs(p95 %d µs, p99 %d µs). 미끼가 들어간 청크 %.1f%%%n",
                    ppc, mean(dec) / 1000, mean(plan) / 1000, mean(rev) / 1000, mean(app) / 1000, mean(enc) / 1000, mean(total) / 1000,
                    pct(total, 0.95) / 1000, pct(total, 0.99) / 1000, 100.0 * patchedChunks / n);
            System.out.printf(Locale.ROOT, "  (디코드+인코드는 수정하지 않는 청크에는 안 든다: 자격 없는 플레이어와 미끼가 없는 청크는 그대로 통과. 재방문은 디코드·인코드가 다시 든다.)%n");
        }
        // ---- 바이트 ----
        System.out.println();
        System.out.println("## 추가 바이트(같은 청크의 패킷 데이터, 섹션 24개 인코드 크기 차이)");
        System.out.println("| 팔레트 종류/섹션 | 다이아 이미 있음 | 청크당 쌍 | 미끼 뭉치/청크 | 추가 바이트 평균 | p95 | 최대 | deflate 뒤 추가 평균 | 같은 미끼를 1.1 블록 갱신으로 보낼 때: 바이트 / 패킷 |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        int[][] cases = {{6, 1}, {10, 1}, {10, 0}, {15, 0}, {16, 0}, {16, 1}, {20, 1}};
        for (int[] cs : cases) {
            for (double ppc : new double[] {1, 4}) {
                int n = 150;
                long[] add = new long[n], addDef = new long[n], upd = new long[n], updPk = new long[n], clusters = new long[n];
                for (int i = 0; i < n; i++) {
                    Column col = terrain(i, 0, 900 + i, cs[0], cs[1] == 1);
                    byte[] before = encode(col);
                    DecoyEngine e = engine(i, ppc);
                    ChunkPlan.Patch patch = e.prepareChunk(new UUID(2, i), "world", i, 0, new PacketChunkView(col, MIN_Y, table));
                    if (patch.isEmpty() || !ChunkPatcher.apply(col, MIN_Y, table, patch)) {
                        continue;
                    }
                    byte[] after = encode(col);
                    add[i] = after.length - before.length;
                    addDef[i] = deflated(after) - deflated(before);
                    int[] u = blockUpdateBytes(patch.pairs(), table);
                    upd[i] = u[0];
                    updPk[i] = u[1];
                    clusters[i] = patch.pairs().size();
                }
                System.out.printf(Locale.ROOT, "| %d | %s | %.0f | %.1f | %.0f | %d | %d | %.0f | %.0f B / %.1f개 |%n",
                        cs[0], cs[1] == 1 ? "예" : "아니오", ppc, mean(clusters), mean(add), pct(add, 0.95), pct(add, 1.0), mean(addDef), mean(upd), mean(updPk));
            }
        }
    }
}

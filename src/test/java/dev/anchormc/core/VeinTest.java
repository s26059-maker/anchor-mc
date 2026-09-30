package dev.anchormc.core;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 광맥 모사: 표본 추출, 모양·크기·높이 재현, 진짜 광석과 붙지 않기. */
class VeinTest {
    private static int[] extent(int[][] cells) {
        int[] e = new int[3];
        for (int[] c : cells) {
            for (int k = 0; k < 3; k++) {
                e[k] = Math.max(e[k], c[k] + 1);
            }
        }
        return e;
    }

    @Test
    void blobsAreSmallConnectedAndNormalized() {
        RandomGenerator r = rng(1);
        for (int size : new int[] {4, 8, 12}) {
            int max = 0, empty = 0;
            for (int i = 0; i < 300; i++) {
                int[][] cells = VeinShapes.blob(r, size);
                assertTrue(cells.length <= 3 * size, size + ": " + cells.length);
                if (cells.length == 0) {
                    empty++; // 바닐라에서도 작은 광맥은 한 블록도 못 놓을 수 있다
                    continue;
                }
                max = Math.max(max, cells.length);
                int[] mins = {99, 99, 99};
                for (int[] c : cells) {
                    for (int k = 0; k < 3; k++) {
                        mins[k] = Math.min(mins[k], c[k]);
                    }
                }
                assertEquals(0, mins[0]);
                assertEquals(0, mins[1]);
                assertEquals(0, mins[2]);
            }
            assertTrue(max >= 2, "size " + size + "에서 뭉치가 안 나온다");
            assertTrue(empty < 250, "size " + size + "에서 거의 다 비었다: " + empty);
        }
    }

    @Test
    void horizontalSymmetryKeepsSizeAndThickness() {
        RandomGenerator r = rng(2);
        for (int i = 0; i < 100; i++) {
            int[][] cells = VeinShapes.blob(r, 12);
            int[] e0 = extent(cells);
            for (int sym = 0; sym < 8; sym++) {
                int[][] t = VeinShapes.horizontalSymmetry(cells, sym);
                assertEquals(cells.length, t.length);
                int[] e1 = extent(t);
                assertEquals(e0[1], e1[1], "두께(y)가 바뀌었다");
                assertEquals(Math.max(e0[0], e0[2]), Math.max(e1[0], e1[2]));
            }
        }
    }

    /** size 다른 뭉치를 격자 월드에 심는다. 서로 2칸 이상 떨어뜨리고 가장자리에서도 떨어뜨린다. */
    private static List<int[][]> plant(GridWorld w, RandomGenerator r, int count, int[] sizes) {
        List<int[][]> planted = new ArrayList<>();
        int tries = 0;
        while (planted.size() < count && tries++ < 20000) {
            int[][] cells = VeinShapes.blob(r, sizes[r.nextInt(sizes.length)]);
            if (cells.length == 0) {
                continue;
            }
            int[] e = extent(cells);
            int ox = 3 + r.nextInt(w.size - 6 - e[0]), oy = 3 + r.nextInt(w.size - 6 - e[1]), oz = 3 + r.nextInt(w.size - 6 - e[2]);
            boolean clear = true;
            for (int[] c : cells) {
                for (int dx = -2; dx <= 2 && clear; dx++) {
                    for (int dy = -2; dy <= 2 && clear; dy++) {
                        for (int dz = -2; dz <= 2 && clear; dz++) {
                            clear = w.get(ox + c[0] + dx, oy + c[1] + dy, oz + c[2] + dz) != GridWorld.ORE;
                        }
                    }
                }
            }
            if (!clear) {
                continue;
            }
            for (int[] c : cells) {
                w.set(ox + c[0], oy + c[1], oz + c[2], GridWorld.ORE);
            }
            planted.add(cells);
        }
        return planted;
    }

    @Test
    void scannerCollectsExactlyThePlantedClusters() {
        RandomGenerator r = rng(3);
        GridWorld w = GridWorld.solid(48);
        List<int[][]> planted = plant(w, r, 60, new int[] {4, 8, 12});
        VeinProfile prof = new VeinProfile(10_000);
        int total = 0;
        for (int cx = 0; cx < 3; cx++) {
            for (int cz = 0; cz < 3; cz++) {
                total += VeinScanner.scanChunk(w, W, cx, cz, 0, 47, prof);
            }
        }
        assertEquals(planted.size(), total, "청크 경계에 걸린 뭉치도 한 번씩만 세야 한다");
        int[] h = prof.sizeHistogram(40);
        int[] expect = new int[40];
        planted.forEach(c -> expect[Math.min(40, c.length) - 1]++);
        assertEquals(java.util.Arrays.toString(expect), java.util.Arrays.toString(h));
        // 같은 청크를 다시 스캔해도 늘지 않는다
        assertEquals(0, VeinScanner.scanChunk(w, W, 1, 1, 0, 47, prof));
    }

    /** 배치한 미끼 뭉치를 기록하는 표시 장치. */
    static final class Recorder implements Display {
        final List<List<Voxel>> clusters = new ArrayList<>();

        @Override
        public void show(UUID player, List<Voxel> voxels) {
            clusters.add(new ArrayList<>(voxels));
        }

        @Override
        public void hide(UUID player, List<Pos> positions) {
        }
    }

    private static Params veinParams() {
        return new Params(3.0, 100_000, 100.0, 2.0, 0, 63, 300, 1.0, 5000);
    }

    @Test
    void decoySizesAndHeightsFollowTheSampledBank() {
        RandomGenerator r = rng(4);
        GridWorld w = GridWorld.solid(64);
        // 크기가 뚜렷이 다른 두 종류: 작은 것(size 4 blob)이 더 흔하다. 높이는 아래쪽으로 몰아서 심는다.
        List<int[][]> planted = new ArrayList<>();
        int tries = 0;
        while (planted.size() < 100 && tries++ < 50000) {
            int[][] cells = VeinShapes.blob(r, r.nextInt(3) == 0 ? 12 : 4);
            if (cells.length == 0) {
                continue;
            }
            int[] e = extent(cells);
            int ox = 3 + r.nextInt(64 - 6 - e[0]), oy = 3 + r.nextInt(30), oz = 3 + r.nextInt(64 - 6 - e[2]);
            if (oy + e[1] > 61) {
                continue;
            }
            boolean clear = true;
            for (int[] c : cells) {
                for (int dx = -2; dx <= 2 && clear; dx++) {
                    for (int dy = -2; dy <= 2 && clear; dy++) {
                        for (int dz = -2; dz <= 2 && clear; dz++) {
                            clear = w.get(ox + c[0] + dx, oy + c[1] + dy, oz + c[2] + dz) != GridWorld.ORE;
                        }
                    }
                }
            }
            if (clear) {
                for (int[] c : cells) {
                    w.set(ox + c[0], oy + c[1], oz + c[2], GridWorld.ORE);
                }
                planted.add(cells);
            }
        }
        Recorder rec = new Recorder();
        DecoyEngine e = new DecoyEngine(veinParams(), name -> w, rec, o -> { }, r);
        // 플레이어를 많이 바꿔 가며 같은 청크들을 보낸다(플레이어마다 쿨다운·상한이 따로다).
        for (int i = 0; i < 1500; i++) {
            PlayerState p = new PlayerState(new UUID(0, i), "p" + i, W, 32, 32, 32, true);
            for (int cx = 0; cx < 4; cx++) {
                for (int cz = 0; cz < 4; cz++) {
                    e.onChunkSent(p, cx, cz, 0);
                }
            }
        }
        assertTrue(e.profile().usingBank(), "표본이 모여 뱅크를 쓰고 있어야 한다: " + e.profile().bankSize());
        assertTrue(rec.clusters.size() > 1000, "" + rec.clusters.size());
        int bins = 13;
        double[] pd = new double[bins], pr = new double[bins];
        for (List<Voxel> c : rec.clusters) {
            pd[Math.min(bins, c.size()) - 1]++;
        }
        int[] bank = e.profile().sizeHistogram(bins);
        double bankN = 0;
        for (int v : bank) {
            bankN += v;
        }
        double tv = 0;
        for (int i = 0; i < bins; i++) {
            pr[i] = bank[i] / bankN;
            tv += Math.abs(pd[i] / rec.clusters.size() - pr[i]) / 2;
        }
        assertTrue(tv < 0.10, "미끼 크기 분포가 표본과 다르다: TV=" + tv);
        double meanY = 0;
        for (List<Voxel> c : rec.clusters) {
            meanY += c.stream().mapToInt(v -> v.pos().y()).min().orElse(0);
        }
        meanY /= rec.clusters.size();
        double planted0 = 0;
        // 심은 광맥의 최소 y 평균은 심은 좌표를 다시 계산해야 하므로 월드의 광석 y 평균과 비교한다(±3).
        int n = 0;
        for (int x = 0; x < 64; x++) {
            for (int y = 0; y < 64; y++) {
                for (int z = 0; z < 64; z++) {
                    if (w.get(x, y, z) == GridWorld.ORE) {
                        planted0 += y;
                        n++;
                    }
                }
            }
        }
        planted0 /= n;
        assertTrue(Math.abs(meanY - planted0) < 6, "미끼 높이가 진짜와 다르다: " + meanY + " vs " + planted0);
    }

    @Test
    void decoysNeverTouchRealOreAndStayInsideTheirChunk() {
        RandomGenerator r = rng(5);
        GridWorld w = GridWorld.solid(64);
        plant(w, r, 120, new int[] {4, 8});
        Recorder rec = new Recorder();
        DecoyEngine e = new DecoyEngine(veinParams(), name -> w, rec, o -> { }, r);
        for (int i = 0; i < 400; i++) {
            PlayerState p = new PlayerState(new UUID(1, i), "p" + i, W, 32, 32, 32, true);
            for (int cx = 0; cx < 4; cx++) {
                for (int cz = 0; cz < 4; cz++) {
                    int before = rec.clusters.size();
                    e.onChunkSent(p, cx, cz, 0);
                    for (int k = before; k < rec.clusters.size(); k++) {
                        for (Voxel v : rec.clusters.get(k)) {
                            assertEquals(cx, v.pos().chunkX());
                            assertEquals(cz, v.pos().chunkZ());
                            for (int dx = -1; dx <= 1; dx++) {
                                for (int dy = -1; dy <= 1; dy++) {
                                    for (int dz = -1; dz <= 1; dz++) {
                                        assertFalse(w.isDiamondOre(v.pos().x() + dx, v.pos().y() + dy, v.pos().z() + dz),
                                                "미끼가 진짜 광석에 붙어 있다: " + v.pos());
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertTrue(rec.clusters.size() > 200, "" + rec.clusters.size());
    }

    @Test
    void withoutSamplesFallsBackToVanillaShapesAndStillPlacesClusters() {
        RandomGenerator r = rng(6);
        GridWorld w = GridWorld.solid(64); // 광석이 없다
        Recorder rec = new Recorder();
        Params p = new Params(3.0, 100_000, 100.0, 2.0, 0, 63, 300, 1.0, 300);
        DecoyEngine e = new DecoyEngine(p, name -> w, rec, o -> { }, r);
        for (int i = 0; i < 300; i++) {
            PlayerState pl = new PlayerState(new UUID(2, i), "p" + i, W, 32, 32, 32, true);
            for (int cx = 0; cx < 4; cx++) {
                for (int cz = 0; cz < 4; cz++) {
                    e.onChunkSent(pl, cx, cz, 0);
                }
            }
        }
        assertFalse(e.profile().usingBank());
        assertTrue(rec.clusters.size() > 100);
        assertTrue(rec.clusters.stream().anyMatch(c -> c.size() >= 2), "기본 모양도 뭉치가 나와야 한다");
    }
}

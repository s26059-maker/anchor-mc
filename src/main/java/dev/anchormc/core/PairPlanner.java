package dev.anchormc.core;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

/**
 * 한 청크의 (미끼, 위약) 쌍을 (플레이어, 월드, 청크, 슬롯, 비밀 시드)만으로 정한다. 플레이어 위치·상태에 기대지 않으므로
 * 청크 패킷을 만드는 스레드에서 돌릴 수 있고, 같은 입력이면 같은 결과다. 읽는 것은 패킷 시야(BlockView)뿐이라 서버 월드는 건드리지 않는다.
 * 미끼와 위약은 같은 pick()을 쓰고, 어느 쪽이 미끼인지는 뭉치를 다 뽑은 뒤 슬롯 난수열의 동전으로 정한다.
 */
final class PairPlanner {
    private final Seeds seeds;
    private final VeinProfile profile;
    private final YBalance balance;
    private final Params params;

    PairPlanner(Seeds seeds, VeinProfile profile, YBalance balance, Params params) {
        this.seeds = seeds;
        this.profile = profile;
        this.balance = balance;
        this.params = params;
    }

    ChunkPlan plan(UUID player, String world, int cx, int cz, long visit, BlockView view) {
        String base = player + "|" + world + "|" + cx + "|" + cz + (visit == 0 ? "" : "|v" + visit);
        double lambda = params.pairsPerChunk();
        RandomGenerator countRng = seeds.rng("count|" + base);
        int tries = (int) Math.floor(lambda) + (countRng.nextDouble() < lambda - Math.floor(lambda) ? 1 : 0);
        List<PlannedPair> pairs = new ArrayList<>();
        List<List<Voxel>> taken = new ArrayList<>();
        for (int slot = 0; slot < tries; slot++) {
            RandomGenerator rng = seeds.rng("slot|" + base + "|" + slot);
            List<Voxel> a = pick(view, world, cx, cz, rng, taken);
            if (a == null) {
                continue;
            }
            taken.add(a);
            List<Voxel> b = pick(view, world, cx, cz, rng, taken);
            if (b == null) {
                taken.remove(a);
                continue;
            }
            taken.add(b);
            // 안전 검사는 둘 다 통과해야 한다(한쪽만 통과해 쌍이 깨지면 종류 간 분포가 달라진다).
            if (!DecoyEngine.sealedAll(view, a) || !DecoyEngine.sealedAll(view, b)) {
                taken.remove(a);
                taken.remove(b);
                continue;
            }
            boolean aDecoy = rng.nextBoolean(); // 동전 던지기
            long id = seeds.derive("pair|" + base + "|" + slot) & Long.MAX_VALUE;
            pairs.add(new PlannedPair(id, slot, a, b, aDecoy));
        }
        return new ChunkPlan(pairs);
    }

    /**
     * 같은 규칙으로 뭉치 하나를 고른다. 모양·크기·높이는 진짜 광맥 표본에서, 위치는 그 청크 안에서 균등하게 뽑고,
     * 모든 블록이 돌 속·봉인 상태이며 진짜 다이아 광석과 붙어 있지 않아야 한다. 통과율은 높이 띠별로 재서 높이 분포를 보정한다.
     */
    private List<Voxel> pick(BlockView view, String world, int cx, int cz, RandomGenerator rng, List<List<Voxel>> taken) {
        double sep = params.minSeparation();
        for (int i = 0; i < params.maxAttempts(); i++) {
            VeinProfile.Placed pl = profile.draw(rng, params.yMin(), params.yMax());
            List<Voxel> voxels = tryPlace(view, world, cx, cz, rng, pl, taken, sep);
            balance.record(pl.srcY(), voxels != null);
            if (voxels != null && balance.accept(pl.srcY(), rng)) {
                return voxels;
            }
        }
        return null;
    }

    private List<Voxel> tryPlace(BlockView view, String world, int cx, int cz, RandomGenerator rng,
                                 VeinProfile.Placed pl, List<List<Voxel>> taken, double sep) {
        int w = 0, h = 0, d = 0;
        for (int[] c : pl.cells()) {
            w = Math.max(w, c[0] + 1);
            h = Math.max(h, c[1] + 1);
            d = Math.max(d, c[2] + 1);
        }
        if (w > 16 || d > 16 || pl.y() < params.yMin() || pl.y() + h - 1 > params.yMax()) {
            return null;
        }
        int ox = cx * 16 + rng.nextInt(17 - w), oz = cz * 16 + rng.nextInt(17 - d);
        List<Voxel> voxels = new ArrayList<>(pl.cells().length);
        for (int[] c : pl.cells()) {
            Pos pos = new Pos(world, ox + c[0], pl.y() + c[1], oz + c[2]);
            Host host = view.hostAt(pos.x(), pos.y(), pos.z());
            if (host == null || !DecoyGuard.sealed(view, pos)) {
                return null;
            }
            voxels.add(new Voxel(pos, host));
        }
        if (touchesOre(view, voxels) || !separated(voxels, taken, sep)) {
            return null;
        }
        return voxels;
    }

    private static boolean touchesOre(BlockView view, List<Voxel> voxels) {
        for (Voxel v : voxels) {
            Pos q = v.pos();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if ((dx != 0 || dy != 0 || dz != 0) && view.isDiamondOre(q.x() + dx, q.y() + dy, q.z() + dz)) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private static boolean separated(List<Voxel> voxels, List<List<Voxel>> taken, double sep) {
        for (List<Voxel> t : taken) {
            for (Voxel a : voxels) {
                for (Voxel b : t) {
                    if (b.pos().distanceTo(a.pos().x() + 0.5, a.pos().y() + 0.5, a.pos().z() + 0.5) < sep) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}

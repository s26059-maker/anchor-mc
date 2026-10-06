package dev.anchormc.core;

import dev.anchormc.AnchorCore;
import dev.anchormc.evidence.AccountRecord;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.EvidenceStore;
import dev.anchormc.evidence.MemoryStore;
import dev.anchormc.evidence.Mixture;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.random.RandomGenerator;

import static dev.anchormc.core.InvariantTest.PLAYER;
import static dev.anchormc.core.InvariantTest.W;
import static dev.anchormc.core.InvariantTest.at;
import static dev.anchormc.core.InvariantTest.rng;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 3단계: 증거를 판정이 나온 순서가 아니라 창 끝 순서로 쌓는다. 반응은 일찍, 무반응은 창이 끝나야 나오므로 나온 순서는 결과에 의존하고,
 * 그 순서로 쌓은 혼합 e-value는 귀무에서도 한 번이라도 문턱을 넘는 확률이 1/x를 크게 넘는다.
 */
class EvidenceOrderTest {
    private static final class Quiet implements Display {
        public void show(UUID p, List<Voxel> v) { }

        public void hide(UUID p, List<Pos> ps) { }
    }

    private static Params oneShotParams() {
        return new Params(3.0, 1000, 60.0, 2.0, 0, 63, 300, 1.0, 0);
    }

    private static double[] approach(Site s) {
        Pos mn = s.voxels.stream().map(Voxel::pos).min(Comparator.comparingInt(Pos::x)).orElseThrow();
        return new double[] {mn.x() + 0.5 - 2.7, mn.y() + 0.5, mn.z() + 0.5};
    }

    // ---- 통계: 귀무에서 두 순서의 "한 번이라도 문턱 넘음" 확률 ----

    /** 귀무: 자리마다 독립으로 확률 p(= p0)로 반응. 자리는 한 시간에 걸쳐 무작위로 생기고, 반응은 창 안 임의 시각에, 무반응은 창 끝에 나온다. */
    private static double[] exceedance(int accounts, int sites, double p, double windowSec, double thresholdLog10, long seed) {
        RandomGenerator r = rng(seed);
        int byEmission = 0, byDue = 0;
        record Ob(double created, double emitted, boolean hit) {
        }
        for (int a = 0; a < accounts; a++) {
            List<Ob> obs = new ArrayList<>(sites);
            for (int i = 0; i < sites; i++) {
                double created = r.nextDouble() * 3600;
                boolean hit = r.nextDouble() < p;
                obs.add(new Ob(created, hit ? created + r.nextDouble() * windowSec : created + windowSec, hit));
            }
            if (maxLog10(obs.stream().sorted(Comparator.comparingDouble(Ob::emitted)).map(Ob::hit).toList(), p) >= thresholdLog10) {
                byEmission++;
            }
            if (maxLog10(obs.stream().sorted(Comparator.comparingDouble(o -> o.created() + windowSec)).map(Ob::hit).toList(), p) >= thresholdLog10) {
                byDue++;
            }
        }
        return new double[] {(double) byEmission / accounts, (double) byDue / accounts};
    }

    private static double maxLog10(List<Boolean> hits, double p0) {
        double[] logs = Mixture.newLogs();
        double mx = 0;
        for (boolean h : hits) {
            Mixture.observe(logs, h, p0);
            mx = Math.max(mx, Mixture.log10E(logs));
        }
        return mx;
    }

    @Test
    void ordersByWindowEndKeepTheNullExceedanceUnderOneOverX() {
        // 반응 확률이 p0와 정확히 같은 최악의 귀무. 문턱 E ≥ 100은 이론상 1% 이하여야 한다.
        double[] f = exceedance(1500, 400, 0.02, 240, 2.0, 7);
        assertTrue(f[1] <= 0.015, "창 끝 순서가 이론 한계(1%)를 넘었다: " + f[1]);
        // 같은 자료를 판정이 나온 순서로 쌓으면 훨씬 자주 넘는다(이 테스트가 문제를 실제로 잡는다는 증거).
        assertTrue(f[0] > 0.02 && f[0] >= 5 * f[1], "나온 순서의 부풀림이 안 보인다: 나온 순서 " + f[0] + " vs 창 끝 순서 " + f[1]);
    }

    // ---- 배선 ----

    @Test
    void holdsAJudgmentUntilItsWindowEndsThenCountsIt() {
        GridWorld w = GridWorld.solid(48);
        AnchorCore core = new AnchorCore(oneShotParams(), EvidenceParams.defaults(), name -> w, new Quiet(), new MemoryStore(), rng(71), () -> 1L, c -> { });
        core.decoys.onChunkSent(at(24, 24, 24), 1, 1, 0);
        Site decoy = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(decoy);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 10);
        assertEquals(Result.HIT, decoy.result());
        core.expire(500);
        assertNull(core.evidence.viewOf(PLAYER), "창이 끝나기 전에 반응이 증거에 들어갔다(결과가 순서를 드러낸다)");
        assertEquals(1, core.heldCount());
        core.expire(1000);
        var v = core.evidence.viewOf(PLAYER);
        assertEquals(1, v.decoyHits());
        assertEquals(1, v.decoyN());
        assertEquals(1, v.placeboN());
        assertEquals(0, core.heldCount());
    }

    @Test
    void controlSwitchRestoresImmediateCounting() {
        GridWorld w = GridWorld.solid(48);
        AnchorCore core = new AnchorCore(oneShotParams(), EvidenceParams.defaults(), name -> w, new Quiet(), new MemoryStore(), rng(71), () -> 1L, c -> { });
        core.holdUntilWindowEnd = false;
        core.decoys.onChunkSent(at(24, 24, 24), 1, 1, 0);
        Site decoy = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(decoy);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 10);
        assertEquals(1, core.evidence.viewOf(PLAYER).decoyHits());
        assertEquals(0, core.heldCount());
    }

    /** 저장이 일어난 순서를 기록한다. */
    private static final class Recording implements EvidenceStore {
        final MemoryStore m = new MemoryStore();
        final List<int[]> saves = new ArrayList<>();

        public AccountRecord load(UUID id) {
            return m.load(id);
        }

        public AccountRecord findByName(String n) {
            return m.findByName(n);
        }

        public void save(AccountRecord r) {
            saves.add(new int[] {r.decoyN, r.decoyHits});
            m.save(r);
        }

        public void delete(UUID id) {
            m.delete(id);
        }

        public long[] placeboTotals() {
            return m.placeboTotals();
        }

        public int confirmedCount() {
            return m.confirmedCount();
        }

        public void close() {
        }
    }

    @Test
    void laterCreatedSitesAreCountedAfterEarlierOnesEvenWhenTheyReactFirst() {
        GridWorld w = GridWorld.solid(48);
        Recording store = new Recording();
        AnchorCore core = new AnchorCore(oneShotParams(), EvidenceParams.defaults(), name -> w, new Quiet(), store, rng(72), () -> 1L, c -> { });
        PlayerState pl = at(24, 24, 24);
        core.decoys.onChunkSent(pl, 0, 0, 0);      // 먼저 생긴 쌍 A(창 끝 1000)
        core.decoys.onChunkSent(pl, 2, 2, 100);    // 나중에 생긴 쌍 B(창 끝 1100)
        Site a = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY && s.createdTick == 0).findFirst().orElseThrow();
        Site b = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY && s.createdTick == 100).findFirst().orElseThrow();
        double[] p = approach(b);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 150); // B의 미끼가 먼저 반응한다
        assertEquals(Result.HIT, b.result());
        assertTrue(a.result() == null, "시나리오 오류: A도 같이 판정됐다");
        core.expire(1200); // A는 MISS로 판정, 이어서 둘 다 창 끝 순서로
        // 미끼 관측이 쌓인 순서: A(무반응) 다음 B(반응). 나온 순서였다면 B(반응)가 먼저다.
        List<int[]> decoySteps = new ArrayList<>();
        int[] last = {0, 0};
        for (int[] s : store.saves) {
            if (s[0] != last[0]) {
                decoySteps.add(s);
            }
            last = s;
        }
        assertEquals(2, decoySteps.size());
        assertEquals(0, decoySteps.get(0)[1], "먼저 생긴 A(무반응)가 먼저 들어가야 한다: " + decoySteps.get(0)[0] + "/" + decoySteps.get(0)[1]);
        assertEquals(1, decoySteps.get(1)[1]);
    }

    @Test
    void resetDropsJudgmentsStillWaitingForTheirWindowEnd() {
        GridWorld w = GridWorld.solid(48);
        AnchorCore core = new AnchorCore(oneShotParams(), EvidenceParams.defaults(), name -> w, new Quiet(), new MemoryStore(), rng(71), () -> 1L, c -> { });
        core.decoys.onChunkSent(at(24, 24, 24), 1, 1, 0);
        Site decoy = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(decoy);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 10);
        assertEquals(1, core.heldCount());
        core.resetPlayer(PLAYER, 20);
        assertEquals(0, core.heldCount());
        core.expire(5000);
        assertNull(core.evidence.viewOf(PLAYER), "초기화 전의 대기 판정이 되살아났다");
    }

    @Test
    void releaseAllFlushesWhatIsStillWaiting() {
        GridWorld w = GridWorld.solid(48);
        AnchorCore core = new AnchorCore(oneShotParams(), EvidenceParams.defaults(), name -> w, new Quiet(), new MemoryStore(), rng(71), () -> 1L, c -> { });
        core.decoys.onChunkSent(at(24, 24, 24), 1, 1, 0);
        Site decoy = core.decoys.activeSites().stream().filter(s -> s.kind == SiteKind.DECOY).findFirst().orElseThrow();
        double[] p = approach(decoy);
        core.decoys.onMove(PLAYER, W, p[0], p[1], p[2], 10);
        core.releaseAll();
        assertEquals(1, core.evidence.viewOf(PLAYER).decoyHits());
    }
}

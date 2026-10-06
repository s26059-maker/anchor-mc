package dev.anchormc.sim;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.Result;
import dev.anchormc.core.SiteKind;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.Mixture;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 정직 코호트의 판정 관측열을 그대로 모아 두고, 같은 관측열을 조건만 바꿔 다시 재생해서 혼합 e-value가 왜 커지는지 가른다.
 * 엔진에는 영향이 없다(AnchorCore.tap으로 보기만 한다). 재생은 Mixture.observe를 그대로 쓴다.
 */
final class Diag {
    record Obs(long pairId, boolean decoy, boolean hit, double p0, long tick, long created) {
    }

    private final Map<UUID, List<Obs>> obs = new HashMap<>();
    final Map<UUID, String> label = new LinkedHashMap<>();

    void attach(AnchorCore core) {
        core.tap = o -> {
            if (o.result() == Result.HIT || o.result() == Result.MISS) {
                long created = o.tick();
                for (var s : core.decoys.tracker().sitesOf(o.player())) {
                    if (s.pairId == o.pairId() && s.kind == o.kind()) {
                        created = s.createdTick; // 자리가 생긴 순서: 결과와 무관하게 정해진다
                        break;
                    }
                }
                obs.computeIfAbsent(o.player(), k -> new ArrayList<>())
                        .add(new Obs(o.pairId(), o.kind() == SiteKind.DECOY, o.result() == Result.HIT, core.evidence.currentP0(), o.tick(), created));
            }
        };
    }

    /** 미끼 관측만 순서대로 재생해 max log10E를 낸다. p0 수열은 호출자가 정한다. */
    static double maxLog10E(List<Obs> decoyObs, double[] p0s) {
        double[] logs = Mixture.newLogs();
        double mx = 0;
        for (int i = 0; i < decoyObs.size(); i++) {
            Mixture.observe(logs, decoyObs.get(i).hit(), p0s[i]);
            mx = Math.max(mx, Mixture.log10E(logs));
        }
        return mx;
    }

    private static double[] actualP0(List<Obs> d) {
        return d.stream().mapToDouble(Obs::p0).toArray();
    }

    private static double[] constant(int n, double p) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, p);
        return a;
    }

    private static List<Obs> decoys(List<Obs> all) {
        return all.stream().filter(Obs::decoy).toList();
    }

    private static String frac(int c, int n, int k) {
        return String.format(Locale.ROOT, "%.1f%%", 100.0 * c / Math.max(1, n));
    }

    void report(EvidenceParams ep) {
        Map<String, List<List<Obs>>> by = new LinkedHashMap<>();
        for (var e : label.entrySet()) {
            List<Obs> l = obs.get(e.getKey());
            if (l != null) {
                by.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(l);
            }
        }
        System.out.println("## 진단: 정직 계정의 혼합 e-value가 왜 커지나(같은 관측열을 조건만 바꿔 재생)");
        System.out.println("재생 A = 판정이 나온 순서·그 시각의 p0(수정 전 엔진과 같다. 수정 후 실행에서도 이 열은 "나온 순서"를 재생한 진단값이다). B = 미끼 관측 순서만 섞음(뭉침 제거, 계정별 반응률 유지). C = p0를 그 계정 자기 위약 반응률×배수로 바꿈(계정별 이질성 제거, 사후 오라클). D = 섞고 C까지. E = 미끼 관측을 판정된 순서가 아니라 자리가 생긴 순서로 재생(순서가 결과와 무관해진다). 값은 max log10E ≥ 2인 계정 비율(이론 한계 1%).");
        System.out.println("| 전략 | 계정 | 미끼 관측당 반응률 | 위약 반응률 | 쓰인 p0 평균 | 반응률>p0 계정 | P(반응\\|직전 미끼 관측도 반응) | A 실제 | B 순서 섞음 | C 계정별 p0 | D 섞음+계정별 p0 | E 생성 순서 |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        RandomGenerator rng = RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(4242);
        for (var e : by.entrySet()) {
            List<List<Obs>> accts = e.getValue();
            int n = accts.size(), a = 0, b = 0, c = 0, dd = 0, ee = 0, shuffles = 20, above = 0;
            long dn = 0, dh = 0, pn = 0, ph = 0, afterHit = 0, afterHitHit = 0;
            double p0sum = 0;
            for (List<Obs> all : accts) {
                List<Obs> d = decoys(all);
                if (d.isEmpty()) {
                    continue;
                }
                long myDn = d.size(), myDh = d.stream().filter(Obs::hit).count();
                long myPn = all.stream().filter(o -> !o.decoy()).count(), myPh = all.stream().filter(o -> !o.decoy() && o.hit()).count();
                dn += myDn;
                dh += myDh;
                pn += myPn;
                ph += myPh;
                double myP0 = d.stream().mapToDouble(Obs::p0).average().orElse(0);
                p0sum += myP0 * myDn;
                if ((double) myDh / myDn > myP0) {
                    above++;
                }
                for (int i = 1; i < d.size(); i++) {
                    if (d.get(i - 1).hit()) {
                        afterHit++;
                        if (d.get(i).hit()) {
                            afterHitHit++;
                        }
                    }
                }
                double own = myPn == 0 ? myP0 : Math.min(EvidenceParams.P0_CAP, Math.max(EvidenceParams.P0_FLOOR, ep.p0Multiplier() * myPh / myPn));
                if (maxLog10E(d, actualP0(d)) >= 2) {
                    a++;
                }
                if (maxLog10E(d, constant(d.size(), own)) >= 2) {
                    c++;
                }
                List<Obs> byCreation = new ArrayList<>(d);
                byCreation.sort(java.util.Comparator.comparingLong(Obs::created).thenComparingLong(Obs::pairId));
                if (maxLog10E(byCreation, actualP0(byCreation)) >= 2) {
                    ee++;
                }
                int sb = 0, sd = 0;
                for (int s = 0; s < shuffles; s++) {
                    List<Obs> sh = new ArrayList<>(d);
                    java.util.Collections.shuffle(sh, new java.util.Random(rng.nextLong()));
                    // 섞어도 p0는 원래 시각의 값을 그 자리에 둔다(수열은 그대로, 반응만 섞인다).
                    double[] p = actualP0(d);
                    List<Obs> shHit = new ArrayList<>(d.size());
                    for (int i = 0; i < d.size(); i++) {
                        shHit.add(new Obs(0, true, sh.get(i).hit(), p[i], 0, 0));
                    }
                    if (maxLog10E(shHit, p) >= 2) {
                        sb++;
                    }
                    if (maxLog10E(shHit, constant(d.size(), own)) >= 2) {
                        sd++;
                    }
                }
                b += sb;
                dd += sd;
            }
            double marg = (double) dh / Math.max(1, dn);
            System.out.printf(Locale.ROOT, "| %s | %d | %.2f%% | %.2f%% | %.2f%% | %s | %.1f%% (전체 %.1f%%) | %s | %.1f%% | %s | %.1f%% | %s |%n",
                    e.getKey(), n, 100 * marg, 100.0 * ph / Math.max(1, pn), 100 * p0sum / Math.max(1, dn),
                    frac(above, n, 0), 100.0 * afterHitHit / Math.max(1, afterHit), 100 * marg,
                    frac(a, n, 0), 100.0 * b / (n * shuffles), frac(c, n, 0), 100.0 * dd / (n * shuffles), frac(ee, n, 0));
        }
        System.out.println();
    }
}

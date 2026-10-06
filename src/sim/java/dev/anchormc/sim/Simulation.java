package dev.anchormc.sim;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.BlockView;
import dev.anchormc.core.Display;
import dev.anchormc.core.Params;
import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.MemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 서버 없이 도는 채굴 시뮬레이션. 실제 DecoyEngine·ResponseTracker·EvidenceEngine을 그대로 쓰고
 * 월드·에이전트·클라이언트 표시만 가짜다. 1.2단계: 미끼는 청크 데이터에 들어 있다(클라이언트가 청크와 같은 시각·경로로 받는다).
 * 1.1단계 방식(별도 블록 갱신 패킷)은 Agent.legacy로 대조군을 돌린다.
 *
 * 사용: gradlew runSim --args="--runs 200 --honest 1000 --minutes 120 --seed 1 --ppc 2 [--rows full|reroll|basic --reroll --no-ybalance]"
 */
public final class Simulation {
    private final Map<UUID, Agent> agents = new HashMap<>();
    private final AnchorCore core;
    private final List<SimWorld> worlds = new ArrayList<>();
    private final long seed;
    private SimWorld current;
    private Diag diag;

    record Run(String strategy, long[] cross, double simMinutes, int decoyN, int decoyHits, int placeboN, int placeboHits,
               double maxMix, double maxPair, double maxFirst, int pD, int pP, int pB, int pN, int oresMined, int revisits) {
        double crossMin(int rule) {
            return cross[rule] < 0 ? Double.NaN : cross[rule] / 1200.0;
        }

        boolean crossed(int rule) {
            return cross[rule] >= 0;
        }
    }

    private Simulation(long seed, Params params, EvidenceParams ep) {
        this.seed = seed;
        Display display = new Display() {
            @Override
            public void show(UUID player, List<Voxel> voxels) {
                Agent a = agents.get(player);
                if (a != null && a.legacy) {
                    Metrics.show(voxels); // 별도 블록 갱신 패킷
                } else {
                    Metrics.embedded(voxels, a != null && a.currentRevisit); // 청크 데이터 안에 들어감: 패킷이 늘지 않는다
                }
                if (a != null) {
                    a.receiveDecoys(voxels);
                }
            }

            @Override
            public void hide(UUID player, List<Pos> positions) {
                Metrics.hide(positions);
                Agent a = agents.get(player);
                if (a != null) {
                    a.retract(positions);
                }
            }
        };
        Function<String, BlockView> views = name -> current;
        this.core = new AnchorCore(params, ep, views, display, new MemoryStore(), rng(seed, 999, 0), () -> 1L, c -> { });
        RandomGenerator wr = rng(seed, 777, 0);
        if (diagOn) {
            diag = new Diag();
            diag.attach(core);
        }
        for (int i = 0; i < 6; i++) {
            worlds.add(SimWorld.generate(wr));
        }
    }

    static RandomGenerator rng(long seed, int a, int b) {
        long s = seed * 0x9E3779B97F4A7C15L + a * 1_000_003L + b * 7919L + 12345;
        return RandomGeneratorFactory.of("Xoshiro256PlusPlus").create(s);
    }

    interface Factory {
        Agent make(SimWorld w, AnchorCore c, UUID id, String name, RandomGenerator r, int[] start);
    }

    private int[] randomAir(SimWorld w, RandomGenerator r) {
        for (int i = 0; i < 100000; i++) {
            int x = 8 + r.nextInt(SimWorld.SX - 16), y = 8 + r.nextInt(SimWorld.SY - 16), z = 8 + r.nextInt(SimWorld.SZ - 16);
            if (!w.solid(x, y, z)) {
                return new int[] {x, y, z};
            }
        }
        return new int[] {SimWorld.SX / 2, SimWorld.SY / 2, SimWorld.SZ / 2};
    }

    private double agentMinutes;
    private static boolean diagOn, noHold;
    /** 행 나누기: 여러 프로세스가 같은 행 목록에서 (행 번호 % groups == group)인 행만 돌린다. 행마다 난수열이 행 번호로 정해져 나눠 돌려도 결과가 같다. */
    private static int groups = 1, group = 0;

    private Run run(String label, int strategyIdx, int runIdx, long capTicks, Factory f) {
        RandomGenerator r = rng(seed, strategyIdx, runIdx);
        current = worlds.get(runIdx % worlds.size()).copy();
        UUID id = new UUID(strategyIdx, runIdx);
        if (diag != null && strategyIdx < 10) {
            diag.label.put(id, label);
        }
        Agent a = f.make(current, core, id, label + "-" + runIdx, r, randomAir(current, r));
        agents.put(id, a);
        a.run(capTicks);
        agents.remove(id);
        agentMinutes += a.tick / 1200.0;
        var v = core.evidence.viewOf(id);
        int dn = 0, dh = 0, pn = 0, ph = 0, pD = 0, pP = 0, pB = 0, pN = 0;
        if (v != null) {
            dn = v.decoyN();
            dh = v.decoyHits();
            pn = v.placeboN();
            ph = v.placeboHits();
            pD = v.pairDecoyOnly();
            pP = v.pairPlaceboOnly();
            pB = v.pairBoth();
            pN = v.pairNeither();
        }
        return new Run(label, a.crossTick.clone(), a.tick / 1200.0, dn, dh, pn, ph, a.maxMix, a.maxPair, a.maxFirst,
                pD, pP, pB, pN, a.oresMined, a.revisits);
    }

    static double pct(double x) {
        return Double.isNaN(x) ? Double.NaN : 100 * x;
    }

    static double quantile(List<Double> xs, double q) {
        if (xs.isEmpty()) {
            return Double.NaN;
        }
        List<Double> s = new ArrayList<>(xs);
        s.sort(Comparator.naturalOrder());
        return s.get((int) Math.min(s.size() - 1, Math.floor(q * s.size())));
    }

    public static void main(String[] args) {
        int runs = 100, honest = 300;
        double minutes = 120, honestMinutes = 60;
        long seed = 1;
        double ppc = Params.defaults().pairsPerChunk();
        String sections = "honest,shape,net,xray";
        String rows = "full";
        boolean reroll = false, ybalance = true;
        long windowSec = Params.defaults().windowTicks() / 20;
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < args.length; i++) {
            String v = i + 1 < args.length ? args[i + 1] : "";
            switch (args[i]) {
                case "--runs" -> runs = Integer.parseInt(v);
                case "--honest" -> honest = Integer.parseInt(v);
                case "--minutes" -> minutes = Double.parseDouble(v);
                case "--honest-minutes" -> honestMinutes = Double.parseDouble(v);
                case "--seed" -> seed = Long.parseLong(v);
                case "--ppc" -> ppc = Double.parseDouble(v);
                case "--sections" -> sections = v;
                case "--rows" -> rows = v;
                case "--reroll" -> reroll = true;
                case "--no-ybalance" -> ybalance = false;
                case "--no-hold" -> noHold = true;
                case "--window" -> windowSec = Long.parseLong(v);
                case "--groups" -> groups = Integer.parseInt(v);
                case "--group" -> group = Integer.parseInt(v);
                default -> { }
            }
        }
        Params d = Params.defaults();
        Params params = new Params(d.reactionRadius(), windowSec * 20, d.giveUpDistance(), d.retractDistance(),
                d.yMin(), d.yMax(), d.maxAttempts(), ppc, d.profileSamples());
        EvidenceParams ep = EvidenceParams.defaults();
        diagOn = sections.contains("diag");
        Simulation sim = new Simulation(seed, params, ep);
        sim.core.decoys.setConsistentRevisit(!reroll);
        sim.core.decoys.setHeightBalance(ybalance);
        sim.core.holdUntilWindowEnd = !noHold;
        long t0 = System.currentTimeMillis();

        System.out.printf(Locale.ROOT, "설정: 반경 r=%.1f, 창 %ds, 청크당 쌍 %.2f, α=%.0e, p0 배수 %.1f, seed=%d, 재방문 %s, 높이 보정 %s, 증거 순서 %s%n",
                params.reactionRadius(), params.windowTicks() / 20, params.pairsPerChunk(), ep.alpha(), ep.p0Multiplier(), seed,
                reroll ? "매번 새로 뽑기(1.1 방식 대조군)" : "결정적(같은 자리)", ybalance ? "켬" : "끔", noHold ? "판정이 나온 순서(수정 전)" : "창 끝 순서(수정 후)");
        System.out.printf(Locale.ROOT, "월드 %dx%dx%d(y -64..15, %dx%d청크) x6종, 청크 반경 %d(%d청크), 채굴 비용 이동 %d틱/블록 + 굴착 %d틱/블록. 미끼는 청크 데이터에 삽입%n%n",
                SimWorld.SX, SimWorld.SZ, SimWorld.SY, SimWorld.CX, SimWorld.CZ, Agent.R, (2 * Agent.R + 1) * (2 * Agent.R + 1),
                Agent.MOVE_TICKS, Agent.DIG_TICKS);
        Metrics.recordClusters = true;

        // ---- 정직 코호트: 서버 시작 직후부터 p0를 실측하며 진행한다 ----
        long hCap = (long) (honestMinutes * 1200);
        List<Run> honestRuns = new ArrayList<>();
        int half = honest / 2;
        for (int i = 0; i < half; i++) {
            honestRuns.add(sim.run("정직-동굴탐색", 1, i, hCap, Agents.Wander::new));
            honestRuns.add(sim.run("정직-브랜치마이닝", 2, i, hCap, (w, c, id, n, r, s) -> new Agents.Branch(w, c, id, n, r)));
        }
        if (honest > 0) {
            var st = sim.core.evidence.stats();
            System.out.println("## 정직 코호트");
            System.out.printf(Locale.ROOT, "정직 계정 %d개(각 최대 %.0f분). 위약 %d/%d 반응(%.2f%%) → p0 = %.4f(배수 적용 후), 광맥 표본 %d개(뱅크 사용 %s)%n",
                    honestRuns.size(), honestMinutes, st.placeboHits(), st.placeboN(), pct(st.placeboRate()), st.p0(),
                    sim.core.decoys.profile().bankSize(), sim.core.decoys.profile().usingBank());
            printHonest(honestRuns);
            System.out.println();
            if (sim.diag != null) {
                sim.diag.report(ep);
            }
            if (sections.contains("net")) {
                printNet(honestRuns.size(), sim.agentMinutes);
            }
            if (sections.contains("shape")) {
                printShape(sim);
            }
        }

        Metrics.recordClusters = false; // 모양 통계는 정직 코호트에서만 모은다(엑스레이 구간에서 계속 쌓으면 메모리가 샌다)

        // ---- 엑스레이 ----
        if (sections.contains("xray") && runs > 0) {
            long cap = (long) (minutes * 1200);
            System.out.printf(Locale.ROOT, "%n## 엑스레이(각 최대 %.0f분, p0는 위약 반응률로 계속 갱신, 정직 코호트 직후 p0=%.4f)%n", minutes, sim.core.evidence.currentP0());
            System.out.println("확정 규칙: 혼합 = 대안 혼합 E ≥ 10^9. 쌍(한쪽만) = 1.1 정의 쌍 정확 E ≥ 10^9. 이중(BOTH, 기본 규칙) = 혼합 ≥ 10^9 그리고 먼저 반응한 쪽 E ≥ 10^3. 먼저 = 먼저 반응한 쪽 E ≥ 10^9. 시간은 채굴 시간(분): 중앙값(p90).");
            System.out.println("| 전략 | 필터 | 경로 | 계정 | 혼합 확정 | 쌍(한쪽만) 확정 | 이중(BOTH) 확정 | 먼저 반응 확정 | 판정된 쌍 수(평균) | 재방문 청크(평균) | 미끼 반응률 | 위약 반응률 | 캔 진짜 광석 |");
            System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
            record Strat(String name, double trust, int k) { }
            List<Strat> strats = List.of(new Strat("신뢰100%", 1.0, 0), new Strat("신뢰50%", 0.5, 0), new Strat("신뢰20%", 0.2, 0),
                    new Strat("검증형(3번)", 1.0, 3), new Strat("검증형(10번)", 1.0, 10));
            int[] idx = {10};
            if (rows.equals("full") || rows.equals("basic")) {
                for (Strat s : strats) {
                    xrow(sim, s.name(), "끔", "청크", idx[0]++, runs, cap, s.trust(), s.k(), Agents.Filters.NONE, false);
                    xrow(sim, s.name(), "시점+모양+재방문", "청크", idx[0]++, runs, cap, s.trust(), s.k(), Agents.Filters.allData(), false);
                    if (rows.equals("basic")) {
                        break;
                    }
                }
            }
            if (rows.equals("full")) {
                xrow(sim, "신뢰100%", "시점(허용 0틱)", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyTiming(), false);
                xrow(sim, "신뢰100%", "모양", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyShape(), false);
                xrow(sim, "신뢰100%", "재방문 차이", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyRevisit(), false);
                xrow(sim, "신뢰100%", "패킷 경로", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyPacket(), false);
                xrow(sim, "신뢰100%", "전부", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.everything(), false);
                // 대조군: 1.1단계 방식(미끼를 별도 블록 갱신 패킷으로 보낸다)
                xrow(sim, "신뢰100%", "끔", "블록갱신(1.1)", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.NONE, true);
                xrow(sim, "신뢰100%", "패킷 경로", "블록갱신(1.1)", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyPacket(), true);
                xrow(sim, "신뢰100%", "시점(전송 1틱 지연)", "블록갱신(1.1)", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyTiming(), true, 1);
            }
            if (rows.equals("reroll")) {
                xrow(sim, "신뢰100%", "끔", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.NONE, false);
                xrow(sim, "신뢰100%", "재방문 차이", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.onlyRevisit(), false);
                xrow(sim, "신뢰100%", "전부", "청크", idx[0]++, runs, cap, 1.0, 0, Agents.Filters.allData(), false);
                xrow(sim, "검증형(3번)", "재방문 차이", "청크", idx[0]++, runs, cap, 1.0, 3, Agents.Filters.onlyRevisit(), false);
            }
        }
        var end = sim.core.evidence.stats();
        System.out.printf(Locale.ROOT, "%n마지막 p0 = %.4f (위약 %d/%d = %.2f%%), 판정 전 회수(증거 제외) %d건, 짝 못 찾은 쌍 %d, 경과 %.1f초%n",
                end.p0(), end.placeboHits(), end.placeboN(), pct(end.placeboRate()), sim.core.voided(),
                sim.core.evidence.pendingPairs(), (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static void xrow(Simulation sim, String strat, String filter, String path, int idx, int runs, long cap,
                             double trust, int k, Agents.Filters f, boolean legacy) {
        xrow(sim, strat, filter, path, idx, runs, cap, trust, k, f, legacy, 0);
    }

    private static void xrow(Simulation sim, String strat, String filter, String path, int idx, int runs, long cap,
                             double trust, int k, Agents.Filters f, boolean legacy, long delay) {
        row(sim, strat, filter, path, idx, runs, cap, (w, c, id, n, r, st0) -> {
            Agent a = new Agents.Xray(w, c, id, n, r, st0, trust, k, f);
            a.legacy = legacy;
            a.showDelay = delay;
            return a;
        });
    }

    private static String cell(List<Run> rs, int rule) {
        List<Double> t = rs.stream().filter(r -> r.crossed(rule)).map(r -> r.crossMin(rule)).toList();
        return String.format(Locale.ROOT, "%d (%.0f%%) %s(%s)", t.size(), 100.0 * t.size() / rs.size(), fmt(quantile(t, 0.5)), fmt(quantile(t, 0.9)));
    }

    private static void row(Simulation sim, String strat, String filter, String path, int idx, int runs, long cap, Factory f) {
        if (idx % groups != group) {
            return;
        }
        List<Run> rs = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            rs.add(sim.run("엑스레이 " + strat, idx, i, cap, f));
        }
        int dn = rs.stream().mapToInt(r -> r.decoyN).sum(), dh = rs.stream().mapToInt(r -> r.decoyHits).sum();
        int pn = rs.stream().mapToInt(r -> r.placeboN).sum(), ph = rs.stream().mapToInt(r -> r.placeboHits).sum();
        double ores = rs.stream().mapToInt(r -> r.oresMined).average().orElse(0);
        double npairs = rs.stream().mapToInt(r -> r.pD + r.pP + r.pB + r.pN).average().orElse(0);
        double rev = rs.stream().mapToInt(r -> r.revisits).average().orElse(0);
        System.out.printf(Locale.ROOT, "| %s | %s | %s | %d | %s | %s | %s | %s | %.1f | %.1f | %d/%d (%.1f%%) | %d/%d (%.2f%%) | %.1f |%n",
                strat, filter, path, rs.size(), cell(rs, 0), cell(rs, 1), cell(rs, 2), cell(rs, 3), npairs, rev,
                dh, dn, pct((double) dh / dn), ph, pn, pct((double) ph / pn), ores);
    }

    private static String fmt(double m) {
        return Double.isNaN(m) ? "-" : String.format(Locale.ROOT, "%.1f", m);
    }

    private static void printHonest(List<Run> rs) {
        System.out.println("정직 계정이 e-value를 넘은 비율. 이론상 P(언젠가 E ≥ x) ≤ 1/x: log10E ≥ 1, 2, 3은 각각 10%, 1%, 0.1% 이하.");
        System.out.println("| 전략 | 계정 | 혼합 max≥1 | ≥2 | ≥3 | 쌍(한쪽만) max≥1 | ≥2 | ≥3 | 먼저 반응 max≥1 | ≥2 | ≥3 | 확정(혼합/쌍/이중/먼저) | 미끼 반응률 | 위약 반응률 | 쌍(미끼만/위약만/둘다/없음) 합 | 재방문 청크(평균) |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        Map<String, List<Run>> by = new LinkedHashMap<>();
        for (Run r : rs) {
            by.computeIfAbsent(r.strategy, k -> new ArrayList<>()).add(r);
        }
        by.put("정직 전체", rs);
        for (var e : by.entrySet()) {
            List<Run> l = e.getValue();
            int n = l.size();
            int dn = l.stream().mapToInt(r -> r.decoyN).sum(), dh = l.stream().mapToInt(r -> r.decoyHits).sum();
            int pn = l.stream().mapToInt(r -> r.placeboN).sum(), ph = l.stream().mapToInt(r -> r.placeboHits).sum();
            long[] conf = new long[4];
            for (int k = 0; k < 4; k++) {
                final int kk = k;
                conf[k] = l.stream().filter(r -> r.crossed(kk)).count();
            }
            System.out.printf(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s | %s | %s | %s | %d/%d/%d/%d | %d/%d (%.2f%%) | %d/%d (%.2f%%) | %d/%d/%d/%d | %.1f |%n",
                    e.getKey(), n,
                    frac(l, r -> r.maxMix >= 1, n), frac(l, r -> r.maxMix >= 2, n), frac(l, r -> r.maxMix >= 3, n),
                    frac(l, r -> r.maxPair >= 1, n), frac(l, r -> r.maxPair >= 2, n), frac(l, r -> r.maxPair >= 3, n),
                    frac(l, r -> r.maxFirst >= 1, n), frac(l, r -> r.maxFirst >= 2, n), frac(l, r -> r.maxFirst >= 3, n),
                    conf[0], conf[1], conf[2], conf[3],
                    dh, dn, pct((double) dh / dn), ph, pn, pct((double) ph / pn),
                    l.stream().mapToInt(r -> r.pD).sum(), l.stream().mapToInt(r -> r.pP).sum(),
                    l.stream().mapToInt(r -> r.pB).sum(), l.stream().mapToInt(r -> r.pN).sum(),
                    l.stream().mapToInt(r -> r.revisits).average().orElse(0));
        }
    }

    private static String frac(List<Run> l, java.util.function.Predicate<Run> p, int n) {
        long c = l.stream().filter(p).count();
        return String.format(Locale.ROOT, "%d (%.2f%%)", c, 100.0 * c / n);
    }

    private static void printNet(int accounts, double agentMinutes) {
        double secs = agentMinutes * 60;
        System.out.println("## 네트워크·CPU 비용(정직 코호트 기준, 플레이어당)");
        System.out.printf(Locale.ROOT, "플레이어 %d명, 합계 %.0f분(%.0f초).%n", accounts, agentMinutes, secs);
        System.out.printf(Locale.ROOT, "청크 전송 %d회(플레이어·분당 %.1f회, 그중 재방문 %d회). 미끼가 들어간 뭉치 %d개(청크 전송당 %.4f개, 뭉치당 %.2f블록).%n",
                Metrics.chunkSends, Metrics.chunkSends / agentMinutes, Metrics.revisitSends, Metrics.embeddedClusters,
                (double) Metrics.embeddedClusters / Metrics.chunkSends, (double) Metrics.embeddedBlocks / Math.max(1, Metrics.embeddedClusters));
        System.out.printf(Locale.ROOT, "청크 데이터에 넣은 미끼 블록 %d개: 별도 패킷 0개. 회수(진짜 블록 되돌리기, 블록 갱신 패킷) 블록 %d개 → 패킷 %d개(초당 %.4f개).%n",
                Metrics.embeddedBlocks, Metrics.hideBlocks, Metrics.hidePackets, Metrics.hidePackets / secs);
        long first = Metrics.chunkSends - Metrics.revisitSends;
        System.out.printf(Locale.ROOT, "엔진 청크 처리(계획+패킷 시야 검사+등록, 시뮬레이터의 메모리 배열 월드 기준) 평균 %.1f µs/회. 처음 받는 청크 %d회 %.1f µs/회, 재방문 %d회 %.1f µs/회(계획 기억 재사용), 합계 %.2f초.%n",
                Metrics.chunkNanos / 1000.0 / Metrics.chunkSends, first, (Metrics.chunkNanos - Metrics.revisitNanos) / 1000.0 / Math.max(1, first),
                Metrics.revisitSends, Metrics.revisitNanos / 1000.0 / Math.max(1, Metrics.revisitSends), Metrics.chunkNanos / 1e9);
        System.out.println();
    }

    private static void printShape(Simulation sim) {
        List<int[]> real = new ArrayList<>();
        for (SimWorld w : sim.worlds) {
            real.addAll(w.realClusters());
        }
        List<int[]> dec = Metrics.decoyClusters;
        int[] edges = {1, 2, 3, 4, 5, 7, 10, 13};
        String[] names = {"1", "2", "3", "4", "5-6", "7-9", "10-12", "13+"};
        double[] pr = new double[edges.length], pd = new double[edges.length];
        for (int[] c : real) {
            pr[bin(c[0], edges)]++;
        }
        for (int[] c : dec) {
            pd[bin(c[0], edges)]++;
        }
        System.out.println("## 미끼와 진짜 광맥의 모양(클라이언트에 보이는 26방향 연결 덩어리 기준)");
        System.out.printf(Locale.ROOT, "진짜 광맥 %d개(월드 6종 전체), 미끼 뭉치 %d개(정직 코호트 동안 청크 데이터에 들어간 것).%n", real.size(), dec.size());
        System.out.println("| 크기 | " + String.join(" | ", names) + " |");
        System.out.println("| --- | " + "--- | ".repeat(names.length));
        StringBuilder a = new StringBuilder("| 진짜 |"), b = new StringBuilder("| 미끼 |");
        double tv = 0;
        for (int i = 0; i < names.length; i++) {
            double x = pr[i] / real.size(), y = pd[i] / dec.size();
            a.append(String.format(Locale.ROOT, " %.1f%% |", 100 * x));
            b.append(String.format(Locale.ROOT, " %.1f%% |", 100 * y));
            tv += Math.abs(x - y) / 2;
        }
        System.out.println(a);
        System.out.println(b);
        double ry = real.stream().mapToInt(c -> c[1] + SimWorld.Y0).average().orElse(0);
        double dy = dec.stream().mapToInt(c -> c[1]).average().orElse(0);
        double rt = real.stream().mapToInt(c -> c[2]).average().orElse(0), dt = dec.stream().mapToInt(c -> c[2]).average().orElse(0);
        double rs = real.stream().mapToInt(c -> c[0]).average().orElse(0), ds = dec.stream().mapToInt(c -> c[0]).average().orElse(0);
        // 높이 8칸 띠별 총변동거리(최소 y 기준)
        int bands = 10;
        double tvY = 0;
        StringBuilder yr = new StringBuilder("y 띠(최소 y 기준, 8칸) 진짜/미끼: ");
        for (int k = 0; k < bands; k++) {
            final int lo = -64 + 8 * k;
            double x = real.stream().filter(c -> c[1] + SimWorld.Y0 >= lo && c[1] + SimWorld.Y0 < lo + 8).count() / (double) real.size();
            double y = dec.stream().filter(c -> c[1] >= lo && c[1] < lo + 8).count() / (double) dec.size();
            tvY += Math.abs(x - y) / 2;
            yr.append(String.format(Locale.ROOT, "[%d,%d) %.1f%%/%.1f%%  ", lo, lo + 8, 100 * x, 100 * y));
        }
        System.out.printf(Locale.ROOT, "크기 분포 총변동거리 %.3f. 높이 분포(8칸 띠) 총변동거리 %.3f. 평균 크기 진짜 %.2f / 미끼 %.2f. 평균 최소 y 진짜 %.1f / 미끼 %.1f. 평균 y 두께 진짜 %.2f / 미끼 %.2f.%n",
                tv, tvY, rs, ds, ry, dy, rt, dt);
        long rSingle = real.stream().filter(c -> c[0] == 1).count(), dSingle = dec.stream().filter(c -> c[0] == 1).count();
        System.out.printf(Locale.ROOT, "단일 블록 비율 진짜 %.1f%% / 미끼 %.1f%%. 크기 13 이상 진짜 %.1f%% / 미끼 %.1f%%.%n",
                100.0 * rSingle / real.size(), 100.0 * dSingle / dec.size(),
                100.0 * real.stream().filter(c -> c[0] >= 13).count() / real.size(), 100.0 * dec.stream().filter(c -> c[0] >= 13).count() / dec.size());
        System.out.println(yr);
        System.out.printf(Locale.ROOT, "표본 뱅크(%d개)의 평균 최소 y %.1f(진짜 전체 %.1f).%n", sim.core.decoys.profile().bankSize(), sim.core.decoys.profile().meanY(), ry);
        System.out.println();
    }

    private static int bin(int size, int[] edges) {
        int b = 0;
        for (int i = 0; i < edges.length; i++) {
            if (size >= edges[i]) {
                b = i;
            }
        }
        return b;
    }
}

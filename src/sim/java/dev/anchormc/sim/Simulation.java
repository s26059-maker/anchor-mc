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
 * 월드·에이전트·클라이언트 표시만 가짜다. 배치는 에이전트가 청크를 "받을 때" 일어난다.
 *
 * 사용: gradlew runSim --args="--runs 200 --honest 600 --minutes 120 --seed 1 [--pairs 3 --cooldown 15 --ppc 0.5 --sections honest,shape,xray]"
 */
public final class Simulation {
    private final Map<UUID, Agent> agents = new HashMap<>();
    private final AnchorCore core;
    private final List<SimWorld> worlds = new ArrayList<>();
    private final long seed;
    private SimWorld current;

    record Run(String strategy, long[] cross, double simMinutes, int decoyN, int decoyHits, int placeboN, int placeboHits,
               double maxMix, double maxPair, double maxAvg, int pD, int pP, int pB, int pN, int oresMined) {
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
                Metrics.show(voxels);
                Agent a = agents.get(player);
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

    private Run run(String label, int strategyIdx, int runIdx, long capTicks, Factory f) {
        RandomGenerator r = rng(seed, strategyIdx, runIdx);
        current = worlds.get(runIdx % worlds.size()).copy();
        UUID id = new UUID(strategyIdx, runIdx);
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
        return new Run(label, a.crossTick.clone(), a.tick / 1200.0, dn, dh, pn, ph, a.maxMix, a.maxPair, a.maxAvg,
                pD, pP, pB, pN, a.oresMined);
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
        int pairs = Params.defaults().maxActivePairs();
        long cooldownSec = Params.defaults().cooldownTicks() / 20;
        double ppc = Params.defaults().pairsPerChunk();
        long delay = 0;
        String sections = "honest,shape,net,xray";
        String rows = "all";
        System.setOut(new java.io.PrintStream(new java.io.FileOutputStream(java.io.FileDescriptor.out), true,
                java.nio.charset.StandardCharsets.UTF_8));
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "--runs" -> runs = Integer.parseInt(args[i + 1]);
                case "--honest" -> honest = Integer.parseInt(args[i + 1]);
                case "--minutes" -> minutes = Double.parseDouble(args[i + 1]);
                case "--honest-minutes" -> honestMinutes = Double.parseDouble(args[i + 1]);
                case "--seed" -> seed = Long.parseLong(args[i + 1]);
                case "--pairs" -> pairs = Integer.parseInt(args[i + 1]);
                case "--cooldown" -> cooldownSec = Long.parseLong(args[i + 1]);
                case "--ppc" -> ppc = Double.parseDouble(args[i + 1]);
                case "--delay" -> delay = Long.parseLong(args[i + 1]);
                case "--sections" -> sections = args[i + 1];
                case "--rows" -> rows = args[i + 1];
                default -> { }
            }
        }
        Params d = Params.defaults();
        Params params = new Params(d.reactionRadius(), d.windowTicks(), d.giveUpDistance(), d.retractDistance(),
                d.yMin(), d.yMax(), d.minDistance(), pairs, cooldownSec * 20, d.maxAttempts(), ppc, d.profileSamples());
        EvidenceParams ep = EvidenceParams.defaults();
        Simulation sim = new Simulation(seed, params, ep);
        long t0 = System.currentTimeMillis();

        System.out.printf(Locale.ROOT, "설정: 반경 r=%.1f, 창 %ds, 쌍 상한 %d, 쿨다운 %ds, 청크당 쌍 확률 %.2f, α=%.0e, p0 배수 %.1f, seed=%d, 미끼 도착 지연 %d틱%n",
                params.reactionRadius(), params.windowTicks() / 20, params.maxActivePairs(), params.cooldownTicks() / 20,
                params.pairsPerChunk(), ep.alpha(), ep.p0Multiplier(), seed, delay);
        System.out.printf(Locale.ROOT, "월드 %dx%dx%d(y -64..15, %dx%d청크) x6종, 청크 반경 %d(%d청크), 채굴 비용 이동 %d틱/블록 + 굴착 %d틱/블록%n%n",
                SimWorld.SX, SimWorld.SZ, SimWorld.SY, SimWorld.CX, SimWorld.CZ, Agent.R, (2 * Agent.R + 1) * (2 * Agent.R + 1),
                Agent.MOVE_TICKS, Agent.DIG_TICKS);
        final long showDelay = delay;
        Metrics.recordClusters = true;

        // ---- 정직 코호트: 서버 시작 직후부터 p0를 실측하며 진행한다 ----
        long hCap = (long) (honestMinutes * 1200);
        List<Run> honestRuns = new ArrayList<>();
        int half = honest / 2;
        for (int i = 0; i < half; i++) {
            honestRuns.add(sim.run("정직-동굴탐색", 1, i, hCap, (w, c, id, n, r, s) -> {
                Agent a = new Agents.Wander(w, c, id, n, r, s);
                a.showDelay = showDelay;
                return a;
            }));
            honestRuns.add(sim.run("정직-브랜치마이닝", 2, i, hCap, (w, c, id, n, r, s) -> {
                Agent a = new Agents.Branch(w, c, id, n, r);
                a.showDelay = showDelay;
                return a;
            }));
        }
        if (honest > 0) {
            var st = sim.core.evidence.stats();
            System.out.println("## 정직 코호트");
            System.out.printf(Locale.ROOT, "정직 계정 %d개(각 최대 %.0f분). 위약 %d/%d 반응(%.2f%%) → p0 = %.4f(배수 적용 후), 광맥 표본 %d개(뱅크 사용 %s)%n",
                    honestRuns.size(), honestMinutes, st.placeboHits(), st.placeboN(), pct(st.placeboRate()), st.p0(),
                    sim.core.decoys.profile().bankSize(), sim.core.decoys.profile().usingBank());
            printHonest(honestRuns);
            System.out.println();
            if (sections.contains("net")) {
                printNet(honestRuns.size(), sim.agentMinutes);
            }
            if (sections.contains("shape")) {
                printShape(sim);
            }
        }

        // ---- 엑스레이 ----
        if (sections.contains("xray") && runs > 0) {
            long cap = (long) (minutes * 1200);
            System.out.printf(Locale.ROOT, "%n## 엑스레이(각 최대 %.0f분, p0는 위약 반응률로 계속 갱신, 정직 코호트 직후 p0=%.4f)%n", minutes, sim.core.evidence.currentP0());
            System.out.println("확정 규칙: 혼합 = 대안 혼합 E ≥ 10^9. 쌍 = 쌍 정확 E ≥ 10^9. 이중 = 혼합 ≥ 10^9 그리고 쌍 ≥ 10^3. 평균 = (혼합+쌍)/2 ≥ 10^9. 시간은 채굴 시간(분): 중앙값(p90).");
            System.out.println("| 전략 | 필터 | 계정 | 혼합 확정 | 쌍 확정 | 이중 확정 | 평균 확정 | 쌍 수(평균) | 미끼 반응률 | 위약 반응률 | 캔 진짜 광석 |");
            System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
            record Strat(String name, double trust, int k) { }
            List<Strat> strats = List.of(new Strat("신뢰100%", 1.0, 0), new Strat("신뢰50%", 0.5, 0), new Strat("신뢰20%", 0.2, 0),
                    new Strat("검증형(3번 속으면 무시)", 1.0, 3), new Strat("검증형(10번 속으면 무시)", 1.0, 10));
            record Fil(String name, Agents.Filters f) { }
            List<Fil> fils = List.of(new Fil("끔", Agents.Filters.NONE), new Fil("시점", Agents.Filters.onlyTiming()),
                    new Fil("모양", Agents.Filters.onlyShape()), new Fil("시점+모양", Agents.Filters.timingAndShape()));
            int idx = 10;
            for (Strat s : strats) {
                for (Fil f : rows.equals("basic") ? fils.subList(0, 1) : fils) {
                    final int k = s.k();
                    final double tr = s.trust();
                    final Agents.Filters ff = f.f();
                    row(sim, "엑스레이 " + s.name(), f.name(), idx++, runs, cap, (w, c, id, n, r, st0) -> {
                        Agent a = new Agents.Xray(w, c, id, n, r, st0, tr, k, ff);
                        a.showDelay = showDelay;
                        return a;
                    });
                }
            }
            if (!rows.equals("basic")) {
                // 추가: 가장 강한 필터(청크 데이터가 아닌 블록 갱신 패킷으로 온 광석 무시)
                row(sim, "엑스레이 신뢰100%", "패킷 경로", idx++, runs, cap, (w, c, id, n, r, st0) -> {
                    Agent a = new Agents.Xray(w, c, id, n, r, st0, 1.0, 0, Agents.Filters.packetOnly());
                    a.showDelay = showDelay;
                    return a;
                });
                row(sim, "엑스레이 신뢰100%", "시점+모양+패킷", idx++, runs, cap, (w, c, id, n, r, st0) -> {
                    Agent a = new Agents.Xray(w, c, id, n, r, st0, 1.0, 0, Agents.Filters.all());
                    a.showDelay = showDelay;
                    return a;
                });
                // 추가: 미끼 전송이 청크 이벤트보다 1틱 늦게 나가는 경우의 시점 필터
                row(sim, "엑스레이 신뢰100%", "시점(전송 1틱 지연)", idx++, runs, cap, (w, c, id, n, r, st0) -> {
                    Agent a = new Agents.Xray(w, c, id, n, r, st0, 1.0, 0, Agents.Filters.onlyTiming());
                    a.showDelay = 1;
                    return a;
                });
                row(sim, "엑스레이 신뢰100%", "끔(전송 1틱 지연)", idx++, runs, cap, (w, c, id, n, r, st0) -> {
                    Agent a = new Agents.Xray(w, c, id, n, r, st0, 1.0, 0, Agents.Filters.NONE);
                    a.showDelay = 1;
                    return a;
                });
                    }
        }
        var end = sim.core.evidence.stats();
        System.out.printf(Locale.ROOT, "%n마지막 p0 = %.4f (위약 %d/%d = %.2f%%), 판정 전 회수(증거 제외) %d건, 짝 못 찾은 쌍 %d, 경과 %.1f초%n",
                end.p0(), end.placeboHits(), end.placeboN(), pct(end.placeboRate()), sim.core.voided(),
                sim.core.evidence.pendingPairs(), (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static String cell(List<Run> rs, int rule) {
        List<Double> t = rs.stream().filter(r -> r.crossed(rule)).map(r -> r.crossMin(rule)).toList();
        return String.format(Locale.ROOT, "%d (%.0f%%) %s(%s)", t.size(), 100.0 * t.size() / rs.size(), fmt(quantile(t, 0.5)), fmt(quantile(t, 0.9)));
    }

    private static void row(Simulation sim, String label, String filter, int idx, int runs, long cap, Factory f) {
        List<Run> rs = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            rs.add(sim.run(label, idx, i, cap, f));
        }
        int dn = rs.stream().mapToInt(r -> r.decoyN).sum(), dh = rs.stream().mapToInt(r -> r.decoyHits).sum();
        int pn = rs.stream().mapToInt(r -> r.placeboN).sum(), ph = rs.stream().mapToInt(r -> r.placeboHits).sum();
        double ores = rs.stream().mapToInt(r -> r.oresMined).average().orElse(0);
        double npairs = rs.stream().mapToInt(r -> r.pD + r.pP + r.pB + r.pN).average().orElse(0);
        System.out.printf(Locale.ROOT, "| %s | %s | %d | %s | %s | %s | %s | %.1f | %d/%d (%.1f%%) | %d/%d (%.2f%%) | %.1f |%n",
                label, filter, rs.size(), cell(rs, 0), cell(rs, 1), cell(rs, 2), cell(rs, 3), npairs,
                dh, dn, pct((double) dh / dn), ph, pn, pct((double) ph / pn), ores);
    }

    private static String fmt(double m) {
        return Double.isNaN(m) ? "-" : String.format(Locale.ROOT, "%.1f", m);
    }

    private static void printHonest(List<Run> rs) {
        System.out.println("정직 계정이 e-value를 넘은 비율. 이론상 P(언젠가 E ≥ x) ≤ 1/x: log10E ≥ 1, 2, 3은 각각 10%, 1%, 0.1% 이하.");
        System.out.println("| 전략 | 계정 | 혼합 max≥1 | ≥2 | ≥3 | 쌍 max≥1 | ≥2 | ≥3 | 평균 max≥2 | 확정(혼합/쌍/이중/평균) | 미끼 반응률 | 위약 반응률 | 쌍(미끼만/위약만/둘다/없음) 합 |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |");
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
            System.out.printf(Locale.ROOT, "| %s | %d | %s | %s | %s | %s | %s | %s | %s | %d/%d/%d/%d | %d/%d (%.2f%%) | %d/%d (%.2f%%) | %d/%d/%d/%d |%n",
                    e.getKey(), n,
                    frac(l, r -> r.maxMix >= 1, n), frac(l, r -> r.maxMix >= 2, n), frac(l, r -> r.maxMix >= 3, n),
                    frac(l, r -> r.maxPair >= 1, n), frac(l, r -> r.maxPair >= 2, n), frac(l, r -> r.maxPair >= 3, n),
                    frac(l, r -> r.maxAvg >= 2, n),
                    conf[0], conf[1], conf[2], conf[3],
                    dh, dn, pct((double) dh / dn), ph, pn, pct((double) ph / pn),
                    l.stream().mapToInt(r -> r.pD).sum(), l.stream().mapToInt(r -> r.pP).sum(),
                    l.stream().mapToInt(r -> r.pB).sum(), l.stream().mapToInt(r -> r.pN).sum());
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
        System.out.printf(Locale.ROOT, "청크 전송 %d회(플레이어·분당 %.1f회). 그중 미끼가 나간 것 %d회(청크당 %.4f회).%n",
                Metrics.chunkSends, Metrics.chunkSends / agentMinutes, Metrics.showCalls, (double) Metrics.showCalls / Metrics.chunkSends);
        System.out.printf(Locale.ROOT, "미끼 표시: 블록 %d개 → 패킷 %d개(청크당 %.4f개, 플레이어당 초당 %.4f개, 표시 1회당 %.2f패킷·%.2f블록).%n",
                Metrics.showBlocks, Metrics.showPackets, (double) Metrics.showPackets / Metrics.chunkSends,
                Metrics.showPackets / secs, (double) Metrics.showPackets / Metrics.showCalls, (double) Metrics.showBlocks / Metrics.showCalls);
        System.out.printf(Locale.ROOT, "미끼 회수(진짜 블록 되돌리기): 블록 %d개 → 패킷 %d개(초당 %.4f개, 청크 언로드로 정리된 것은 패킷 없음).%n",
                Metrics.hideBlocks, Metrics.hidePackets, Metrics.hidePackets / secs);
        long packets = Metrics.showPackets + Metrics.hidePackets;
        long bytes = packets * 12 + (Metrics.showBlocks + Metrics.hideBlocks) * 4;
        System.out.printf(Locale.ROOT, "합계 패킷 초당 %.4f개, 바이트는 섹션 갱신 패킷을 헤더 12바이트 + 블록당 4바이트로 잡은 추정 초당 %.3f바이트(플레이어 100명이면 초당 패킷 %.2f개).%n",
                packets / secs, bytes / secs, 100 * packets / secs);
        System.out.printf(Locale.ROOT, "엔진 청크 처리(onChunkSent, 스캔·배치 포함) 평균 %.1f µs/회, 합계 %.2f초(시뮬레이터의 메모리 배열 월드 기준, 실제 Paper 월드 조회는 더 느리다).%n",
                Metrics.chunkNanos / 1000.0 / Metrics.chunkSends, Metrics.chunkNanos / 1e9);
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
        System.out.printf(Locale.ROOT, "진짜 광맥 %d개(월드 6종 전체), 미끼 뭉치 %d개(정직 코호트 동안 전송된 것).%n", real.size(), dec.size());
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
        System.out.printf(Locale.ROOT, "크기 분포 총변동거리 %.3f. 평균 크기 진짜 %.2f / 미끼 %.2f. 평균 최소 y 진짜 %.1f / 미끼 %.1f. 평균 y 두께 진짜 %.2f / 미끼 %.2f.%n",
                tv, rs, ds, ry, dy, rt, dt);
        long rSingle = real.stream().filter(c -> c[0] == 1).count(), dSingle = dec.stream().filter(c -> c[0] == 1).count();
        System.out.printf(Locale.ROOT, "단일 블록 비율 진짜 %.1f%% / 미끼 %.1f%%. 크기 13 이상 진짜 %.1f%% / 미끼 %.1f%%.%n",
                100.0 * rSingle / real.size(), 100.0 * dSingle / dec.size(),
                100.0 * real.stream().filter(c -> c[0] >= 13).count() / real.size(), 100.0 * dec.stream().filter(c -> c[0] >= 13).count() / dec.size());
        // 높이 분포(엔진 y 8칸 구간별 비율)
        StringBuilder yr = new StringBuilder("y 구간(최소 y 기준) 진짜/미끼: ");
        for (int lo = -64; lo < 16; lo += 16) {
            final int l0 = lo;
            double x = real.stream().filter(c -> c[1] + SimWorld.Y0 >= l0 && c[1] + SimWorld.Y0 < l0 + 16).count() / (double) real.size();
            double y = dec.stream().filter(c -> c[1] >= l0 && c[1] < l0 + 16).count() / (double) dec.size();
            yr.append(String.format(Locale.ROOT, "[%d,%d) %.1f%%/%.1f%%  ", lo, lo + 16, 100 * x, 100 * y));
        }
        System.out.println(yr);
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

package dev.anchormc.sim;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.Display;
import dev.anchormc.core.Host;
import dev.anchormc.core.Params;
import dev.anchormc.core.Pos;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.MemoryStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 서버 없이 도는 채굴 시뮬레이션. 실제 DecoyEngine·ResponseTracker·EvidenceEngine을 그대로 쓰고
 * 월드·에이전트·클라이언트 표시만 가짜다.
 *
 * 사용: gradlew runSim --args="--runs 100 --honest 300 --minutes 120 --seed 1 [--pairs 3 --cooldown 15]"
 */
public final class Simulation {
    private final Map<UUID, Agent> agents = new HashMap<>();
    private final AnchorCore core;
    private final List<SimWorld> worlds = new ArrayList<>();
    private final long seed;

    record Run(String strategy, boolean confirmed, double minutes, double simMinutes, int decoyN, int decoyHits,
               int placeboN, int placeboHits, double maxLog10E, int oresMined) {
    }

    private Simulation(long seed, Params params, EvidenceParams ep) {
        this.seed = seed;
        Display display = new Display() {
            @Override
            public void show(UUID player, Pos pos, Host host) {
                Agent a = agents.get(player);
                if (a != null) {
                    double dx = pos.x() - a.x, dy = pos.y() - SimWorld.Y0 - a.y, dz = pos.z() - a.z;
                    a.shown.add(pos);
                    a.popDistance.put(pos, Math.sqrt(dx * dx + dy * dy + dz * dz));
                }
            }

            @Override
            public void hide(UUID player, Pos pos) {
                Agent a = agents.get(player);
                if (a != null) {
                    a.shown.remove(pos);
                }
            }
        };
        Function<String, dev.anchormc.core.BlockView> views = name -> current;
        this.core = new AnchorCore(params, ep, views, display, new MemoryStore(), rng(seed, 999, 0), () -> 1L, c -> {
            Agent a = agents.get(c.account().id);
            if (a != null && !a.confirmed) {
                a.confirmed = true;
                a.confirmedTick = a.engineTick;
            }
        });
        RandomGenerator wr = rng(seed, 777, 0);
        for (int i = 0; i < 6; i++) {
            worlds.add(SimWorld.generate(wr));
        }
    }

    private SimWorld current;

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

    private Run run(String label, int strategyIdx, int runIdx, long capTicks, Factory f) {
        RandomGenerator r = rng(seed, strategyIdx, runIdx);
        current = worlds.get(runIdx % worlds.size()).copy();
        UUID id = new UUID(strategyIdx, runIdx);
        Agent a = f.make(current, core, id, label + "-" + runIdx, r, randomAir(current, r));
        agents.put(id, a);
        a.run(capTicks);
        agents.remove(id);
        var v = core.evidence.viewOf(id);
        int dn = 0, dh = 0, pn = 0, ph = 0;
        if (v != null) {
            dn = v.decoyN();
            dh = v.decoyHits();
            pn = v.placeboN();
            ph = v.placeboHits();
        }
        return new Run(label, a.confirmed, a.confirmed ? a.confirmedTick / 1200.0 : Double.NaN, a.tick / 1200.0,
                dn, dh, pn, ph, a.maxLog10E, a.oresMined);
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
                default -> { }
            }
        }
        Params d = Params.defaults();
        Params params = new Params(d.reactionRadius(), d.windowTicks(), d.giveUpDistance(), d.retractDistance(),
                d.yMin(), d.yMax(), d.minDistance(), d.maxDistance(), pairs, cooldownSec * 20, d.maxAttempts());
        EvidenceParams ep = EvidenceParams.defaults();
        Simulation sim = new Simulation(seed, params, ep);
        long t0 = System.currentTimeMillis();

        System.out.printf(Locale.ROOT, "설정: 반경 r=%.1f, 창 %ds, 밀도 쌍 %d개/쿨다운 %ds, α=%.0e, p0 배수 %.1f, seed=%d%n",
                params.reactionRadius(), params.windowTicks() / 20, params.maxActivePairs(), params.cooldownTicks() / 20,
                ep.alpha(), ep.p0Multiplier(), seed);
        System.out.printf(Locale.ROOT, "월드 %dx%dx%d(y -64..15) x6종, 채굴 비용 이동 %d틱/블록 + 굴착 %d틱/블록%n%n",
                SimWorld.SX, SimWorld.SZ, SimWorld.SY, Agent.MOVE_TICKS, Agent.DIG_TICKS);

        // ---- 정직 코호트: 서버 시작 직후부터 p0를 실측하며 진행한다 ----
        long hCap = (long) (honestMinutes * 1200);
        List<Run> honestRuns = new ArrayList<>();
        int half = honest / 2;
        for (int i = 0; i < half; i++) {
            honestRuns.add(sim.run("정직-동굴탐색", 1, i, hCap, Agents.Wander::new));
            honestRuns.add(sim.run("정직-브랜치마이닝", 2, i, hCap, (w, c, id, n, r, s) -> new Agents.Branch(w, c, id, n, r)));
        }
        var st = sim.core.evidence.stats();
        System.out.println("## 정직 코호트");
        System.out.printf(Locale.ROOT, "정직 계정 %d개(각 최대 %.0f분). 위약 %d/%d 반응(%.2f%%) → p0 = %.4f(배수 적용 후)%n",
                honestRuns.size(), honestMinutes, st.placeboHits(), st.placeboN(), pct(st.placeboRate()), st.p0());
        printHonest(honestRuns, ep);
        double p0AfterHonest = st.p0();

        // ---- 엑스레이 ----
        long cap = (long) (minutes * 1200);
        System.out.printf(Locale.ROOT, "%n## 엑스레이(각 최대 %.0f분, 그 사이 p0는 위약 반응률로 계속 갱신, 정직 코호트 직후 p0=%.4f)%n", minutes, p0AfterHonest);
        System.out.println("| 전략 | 계정 | 확정 | 확정까지 채굴 시간 중앙값(분) | p90(분) | 미끼 관측(평균) | 미끼 반응률 | 위약 반응률 | 캔 진짜 광석(평균) |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        row(sim, "엑스레이 신뢰100%", 10, runs, cap, (w, c, id, n, r, s) -> new Agents.Xray(w, c, id, n, r, s, 1.0, 0));
        row(sim, "엑스레이 신뢰50%", 11, runs, cap, (w, c, id, n, r, s) -> new Agents.Xray(w, c, id, n, r, s, 0.5, 0));
        row(sim, "엑스레이 신뢰20%", 12, runs, cap, (w, c, id, n, r, s) -> new Agents.Xray(w, c, id, n, r, s, 0.2, 0));
        row(sim, "엑스레이 검증형(3번 속으면 무시)", 13, runs, cap, (w, c, id, n, r, s) -> new Agents.Xray(w, c, id, n, r, s, 1.0, 3));
        row(sim, "엑스레이 검증형(10번 속으면 무시)", 14, runs, cap, (w, c, id, n, r, s) -> new Agents.Xray(w, c, id, n, r, s, 1.0, 10));

        var end = sim.core.evidence.stats();
        System.out.printf(Locale.ROOT, "%n마지막 p0 = %.4f (위약 %d/%d = %.2f%%), 판정 전 회수(증거 제외) %d건, 경과 %.1f초%n",
                end.p0(), end.placeboHits(), end.placeboN(), pct(end.placeboRate()), sim.core.voided(),
                (System.currentTimeMillis() - t0) / 1000.0);
    }

    private static void printHonest(List<Run> rs, EvidenceParams ep) {
        System.out.println("| 전략 | 계정 | 확정(E ≥ 1/α) | max log10E ≥ 1 | ≥ 2 | ≥ 3 | 미끼 반응률 | 위약 반응률 | 미끼 관측(평균) |");
        System.out.println("| --- | --- | --- | --- | --- | --- | --- | --- | --- |");
        Map<String, List<Run>> by = new java.util.LinkedHashMap<>();
        for (Run r : rs) {
            by.computeIfAbsent(r.strategy, k -> new ArrayList<>()).add(r);
        }
        by.put("정직 전체", rs);
        for (var e : by.entrySet()) {
            List<Run> l = e.getValue();
            long conf = l.stream().filter(Run::confirmed).count();
            long c1 = l.stream().filter(r -> r.maxLog10E >= 1).count();
            long c2 = l.stream().filter(r -> r.maxLog10E >= 2).count();
            long c3 = l.stream().filter(r -> r.maxLog10E >= 3).count();
            int dn = l.stream().mapToInt(r -> r.decoyN).sum(), dh = l.stream().mapToInt(r -> r.decoyHits).sum();
            int pn = l.stream().mapToInt(r -> r.placeboN).sum(), ph = l.stream().mapToInt(r -> r.placeboHits).sum();
            System.out.printf(Locale.ROOT, "| %s | %d | %d (%.1f%%) | %d (%.1f%%) | %d (%.1f%%) | %d (%.1f%%) | %d/%d (%.2f%%) | %d/%d (%.2f%%) | %.1f |%n",
                    e.getKey(), l.size(), conf, 100.0 * conf / l.size(),
                    c1, 100.0 * c1 / l.size(), c2, 100.0 * c2 / l.size(), c3, 100.0 * c3 / l.size(),
                    dh, dn, pct((double) dh / dn), ph, pn, pct((double) ph / pn), (double) dn / l.size());
        }
        System.out.printf(Locale.ROOT, "(정직 계정에서 max log10E ≥ t가 나올 이론상 상한 비율은 10^-t: 10%%, 1%%, 0.1%%. 확정 문턱은 log10E ≥ %.0f)%n",
                -Math.log10(ep.alpha()));
    }

    private static void row(Simulation sim, String label, int idx, int runs, long cap, Factory f) {
        List<Run> rs = new ArrayList<>();
        for (int i = 0; i < runs; i++) {
            rs.add(sim.run(label, idx, i, cap, f));
        }
        List<Double> times = rs.stream().filter(Run::confirmed).map(Run::minutes).toList();
        int dn = rs.stream().mapToInt(r -> r.decoyN).sum(), dh = rs.stream().mapToInt(r -> r.decoyHits).sum();
        int pn = rs.stream().mapToInt(r -> r.placeboN).sum(), ph = rs.stream().mapToInt(r -> r.placeboHits).sum();
        double ores = rs.stream().mapToInt(r -> r.oresMined).average().orElse(0);
        System.out.printf(Locale.ROOT, "| %s | %d | %d (%.0f%%) | %s | %s | %.1f | %d/%d (%.1f%%) | %d/%d (%.2f%%) | %.1f |%n",
                label, rs.size(), times.size(), 100.0 * times.size() / rs.size(),
                fmt(quantile(times, 0.5)), fmt(quantile(times, 0.9)),
                (double) dn / rs.size(), dh, dn, pct((double) dh / dn), ph, pn, pct((double) ph / pn), ores);
    }

    private static String fmt(double m) {
        return Double.isNaN(m) ? "-" : String.format(Locale.ROOT, "%.1f", m);
    }
}

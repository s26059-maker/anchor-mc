package dev.anchormc.core;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.random.RandomGenerator;

/**
 * 미끼·위약 배치와 회수. Bukkit을 모른다(BlockView·Display로만 바깥과 닿는다).
 *
 * 1.2단계의 흐름(플러그인):
 * 1. 서버가 청크 데이터 패킷을 보낼 때 패킷을 만드는 스레드가 {@link #prepareChunk}를 부른다. 패킷 자신의 블록 데이터를 BlockView로 삼아
 *    (플레이어, 월드, 청크, 비밀 시드)만으로 쌍을 정하고(같은 청크를 다시 받아도 같다), 미끼 블록을 돌려준다. 플러그인은 그것만 패킷에 쓴다.
 * 2. 메인 스레드가 {@link #registerChunk}로 그 쌍을 추적에 올리고 실제 월드로 한 번 더 봉인을 검사한다.
 * 미끼가 클라이언트로 나가는 길은 prepareChunk의 DecoyGuard 검사(패킷 시야) 하나뿐이고, 회수는 Display.hide(블록 갱신)뿐이다.
 * 시뮬레이터·테스트는 {@link #onChunkSent}로 위 두 단계를 한 번에 부른다(패킷에 넣는 대신 Display.show로 클라이언트에 전달).
 */
public final class DecoyEngine {
    private static final int PLANS_PER_PLAYER = 8192;
    private static final double VERIFY_NEAR = 24.0;
    private static final int VERIFY_FAR_EVERY = 10;
    private long verifyCalls;

    private Params params;
    private final Function<String, BlockView> views;
    private final ResponseTracker tracker;
    private final Display display;
    private final Seeds seeds;
    private volatile VeinProfile profile;
    private volatile PairPlanner planner;
    private final YBalance balance;
    private final Map<UUID, Map<ChunkPlan.Key, ChunkPlan>> plans = new ConcurrentHashMap<>();
    private final Map<Long, PlannedPair> pairsById = new ConcurrentHashMap<>();
    private final AtomicLong visits = new AtomicLong();
    private volatile boolean consistentRevisit = true;
    private final Map<RetireCause, LongAdder> retireCounts = new EnumMap<>(RetireCause.class);

    /** 시뮬레이터·테스트용: 비밀 시드를 rng에서 뽑는다. */
    public DecoyEngine(Params params, Function<String, BlockView> views, Display display,
                       Consumer<Outcome> sink, RandomGenerator rng) {
        this(params, views, display, sink, secretFrom(rng));
    }

    /** 서버용: 비밀 시드를 config에서 받는다. */
    public DecoyEngine(Params params, Function<String, BlockView> views, Display display,
                       Consumer<Outcome> sink, byte[] secret) {
        this.params = params;
        this.views = views;
        this.display = display;
        this.seeds = new Seeds(secret);
        this.profile = new VeinProfile(params.profileSamples());
        this.balance = new YBalance(params.yMin(), params.yMax());
        this.planner = new PairPlanner(seeds, profile, balance, params);
        for (RetireCause c : RetireCause.values()) {
            retireCounts.put(c, new LongAdder());
        }
        this.tracker = new ResponseTracker(params, new ResponseTracker.Hooks() {
            @Override
            public void outcome(Outcome o) {
                PlannedPair pp = pairsById.get(o.pairId());
                if (pp != null) {
                    if (o.result() == Result.HIT || o.result() == Result.LATE_HIT) {
                        pp.markHit();
                    }
                    if (o.result() == Result.HIT || o.result() == Result.MISS) {
                        pp.consume(o.result());
                    }
                }
                sink.accept(o);
            }

            @Override
            public void retired(Site s, boolean restoreBlock, long tick) {
                Reason why = s.reason;
                // 사유가 영구 회수(노출 위험·바뀐 월드)면 이 쌍은 다시 보내지 않는다. 자격 상실·청크 언로드처럼 영구가 아니면
                // 화면에서만 거두고 계획은 남긴다(다시 받으면 같은 미끼가 돌아온다).
                if (s.plan != null && why != null) {
                    if (why.cause().permanent()) {
                        if (s.plan.retire(why)) {
                            count(why.cause()); // 쌍을 처음 접은 자리(미끼든 위약이든)가 센다
                        }
                    } else {
                        s.plan.note(why);
                        // 그동안 화면에 있던 시간을 쌍에 더한다(양쪽 자리가 같은 시간이라 미끼 쪽에서만 센다).
                        if (s.kind == SiteKind.DECOY) {
                            s.plan.addExposure(tick - s.registeredTick);
                            count(why.cause()); // 쌍마다 미끼 자리가 하나라 쌍 단위로 센다
                        }
                    }
                }
                // 종류에 따라 달라지는 곳은 여기와 미끼를 내보내는 곳뿐이다(위약은 보낸 적이 없으니 되돌릴 것도 없다).
                if (s.kind == SiteKind.DECOY && restoreBlock) {
                    List<Pos> ps = new ArrayList<>(s.voxels.size());
                    for (Voxel v : s.voxels) {
                        ps.add(v.pos());
                    }
                    display.hide(s.player, ps);
                }
            }
        });
    }

    private void count(RetireCause c) {
        retireCounts.get(c).increment();
    }

    /** 사유별 회수 횟수(쌍 단위). /anchor stats가 쓴다. 모든 사유가 들어 있다(없으면 0). */
    public Map<RetireCause, Long> retireCounts() {
        Map<RetireCause, Long> out = new EnumMap<>(RetireCause.class);
        retireCounts.forEach((c, n) -> out.put(c, n.sum()));
        return out;
    }

    /** 주기 검사가 이웃 청크를 몰라 판단을 보류 중인 활성 자리 수. */
    public int heldSites() {
        int n = 0;
        for (Site s : tracker.allActive()) {
            if (s.held != null) {
                n++;
            }
        }
        return n;
    }

    private static byte[] secretFrom(RandomGenerator rng) {
        byte[] b = new byte[32];
        for (int i = 0; i < 4; i++) {
            long v = rng.nextLong();
            for (int k = 0; k < 8; k++) {
                b[i * 8 + k] = (byte) (v >>> (8 * k));
            }
        }
        return b;
    }

    public void setParams(Params p) {
        if (p.profileSamples() != params.profileSamples()) {
            this.profile = new VeinProfile(p.profileSamples());
        }
        this.params = p;
        this.planner = new PairPlanner(seeds, profile, balance, p);
        tracker.setParams(p);
    }

    /**
     * false면 같은 청크를 받을 때마다 새로 뽑는다(1.1단계의 동작을 재현하는 대조군용, 시뮬레이터만 쓴다).
     */
    public void setConsistentRevisit(boolean on) {
        this.consistentRevisit = on;
    }

    /** 높이 분포 보정을 끈다(대조군용, 시뮬레이터만 쓴다). */
    public void setHeightBalance(boolean on) {
        balance.setEnabled(on);
    }

    public ResponseTracker tracker() {
        return tracker;
    }

    public VeinProfile profile() {
        return profile;
    }

    public List<Site> activeSites() {
        return tracker.allActive();
    }

    /** 주기적으로(예: 1초마다) 플레이어별로 호출. 위치 판정만 한다. 새 자리는 만들지 않는다. */
    public void tick(PlayerState p, long tick) {
        if (!p.eligible()) {
            tracker.dropPlayer(p.id(), tick, true);
            return;
        }
        tracker.observePosition(p.id(), p.world(), p.x(), p.y(), p.z(), tick);
    }

    // ---- 광맥 표본 학습(메인 스레드) ----

    /** 로드된 청크에서 진짜 광맥 표본을 모은다. 메인 스레드에서만 부른다(실제 월드를 읽는다). */
    public void learnChunk(String world, int cx, int cz) {
        VeinProfile prof = profile;
        if (!prof.wantsSamples()) {
            return;
        }
        BlockView view = views.apply(world);
        if (view != null && view.chunkLoaded(cx, cz)) {
            VeinScanner.scanChunk(view, world, cx, cz, params.yMin(), params.yMax(), prof);
        }
    }

    // ---- 1단계: 패킷을 만드는 스레드 ----

    /**
     * 이 플레이어에게 가는 청크 (cx, cz) 데이터 패킷에 넣을 것을 정한다. 어느 스레드에서 불러도 된다(서버 월드를 읽지 않는다).
     * chunkView는 패킷이 담은 블록 데이터로 만든 시야여야 한다. 반환된 decoyVoxels는 이미 이 시야에서 DecoyGuard를 통과했다.
     */
    public ChunkPlan.Patch prepareChunk(UUID player, String world, int cx, int cz, BlockView chunkView) {
        long visit = consistentRevisit ? 0 : visits.incrementAndGet();
        ChunkPlan.Key key = new ChunkPlan.Key(player, world, cx, cz, visit);
        ChunkPlan plan = planFor(key, chunkView);
        List<PlannedPair> live = new ArrayList<>();
        List<Voxel> decoyVoxels = new ArrayList<>();
        for (PlannedPair pp : plan.pairs()) {
            if (pp.retired()) {
                continue; // 이미 회수됐다: 다시 보내지 않는다
            }
            // 불변식 1을 패킷 경로에도 강제한다: 지금 보내는 데이터에서도 봉인돼 있지 않으면 이 쌍은 영영 접는다.
            DecoyGuard.Seal bad = badSeal(chunkView, pp);
            if (!bad.sealed()) {
                if (pp.retire(new Reason(RetireCause.PACKET_UNSEALED, DecoyGuard.explain(chunkView, bad), -1))) {
                    count(RetireCause.PACKET_UNSEALED);
                }
                continue;
            }
            live.add(pp);
            decoyVoxels.addAll(pp.decoy());
        }
        return new ChunkPlan.Patch(player, world, cx, cz, decoyVoxels, live);
    }

    private ChunkPlan planFor(ChunkPlan.Key key, BlockView view) {
        Map<ChunkPlan.Key, ChunkPlan> mine = plans.computeIfAbsent(key.player(), k -> new LinkedHashMap<>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<ChunkPlan.Key, ChunkPlan> e) {
                if (size() > PLANS_PER_PLAYER) {
                    e.getValue().pairs().forEach(pp -> pairsById.remove(pp.pairId));
                    return true;
                }
                return false;
            }
        });
        synchronized (mine) {
            ChunkPlan p = mine.get(key);
            if (p == null) {
                p = planner.plan(key.player(), key.world(), key.cx(), key.cz(), key.visit(), view);
                mine.put(key, p);
                for (PlannedPair pp : p.pairs()) {
                    pairsById.put(pp.pairId, pp);
                }
            }
            return p;
        }
    }

    // ---- 2단계: 메인 스레드 ----

    /**
     * prepareChunk로 패킷에 들어간 쌍을 추적에 올린다. 패킷을 만든 뒤 월드가 바뀌었을 수 있으므로 실제 월드로 다시 검사하고,
     * 봉인이 깨졌으면 이미 나간 미끼를 진짜 블록으로 되돌린다(불변식 2·3). 플레이어가 자격을 잃었거나 월드가 달라도 되돌린다.
     */
    public void registerChunk(PlayerState p, ChunkPlan.Patch patch, long tick) {
        BlockView live = views.apply(patch.world());
        boolean allowed = p.eligible() && p.world().equals(patch.world()) && live != null;
        for (PlannedPair pp : patch.pairs()) {
            if (pp.retired()) {
                hideVoxels(p.id(), pp.decoy());
                continue;
            }
            if (!allowed) {
                pp.note(new Reason(RetireCause.REGISTER_NOT_ALLOWED,
                        "자격=" + p.eligible() + ", 월드 " + p.world() + " / 패킷 " + patch.world(), tick));
                count(RetireCause.REGISTER_NOT_ALLOWED);
                hideVoxels(p.id(), pp.decoy());
                continue;
            }
            DecoyGuard.Seal bad = badSeal(live, pp);
            if (bad.state() == DecoyGuard.State.BREACHED) {
                // 월드가 바뀌어 봉인이 깨졌다: 다시 보내지 않는다. (모름이면 판단을 보류하고 추적한다: 이미 패킷 시야에서 봉인이 확인됐다)
                if (pp.retire(new Reason(RetireCause.REGISTER_UNSEALED, DecoyGuard.explain(live, bad), tick))) {
                    count(RetireCause.REGISTER_UNSEALED);
                }
                hideVoxels(p.id(), pp.decoy());
                continue;
            }
            if (tracker.hasPair(p.id(), pp.pairId)) {
                continue; // 같은 청크가 다시 전송됐다(언로드 없이): 이미 추적 중
            }
            // 자리는 종류와 무관한 순서(계획에서 뽑힌 순서)로 올린다.
            Site sa = new Site(p.id(), p.name(), pp.firstIsDecoy() ? SiteKind.DECOY : SiteKind.PLACEBO, pp.first(), pp.pairId, tick, pp);
            Site sb = new Site(p.id(), p.name(), pp.firstIsDecoy() ? SiteKind.PLACEBO : SiteKind.DECOY, pp.second(), pp.pairId, tick, pp);
            sa.hitReported = pp.hitSeen();
            sb.hitReported = pp.hitSeen();
            if (pp.consumed()) {
                // 이미 한 번 증거에 쓰인 쌍: 판정은 다시 만들지 않는다(result가 채워져 있다). 미끼는 (일관성 때문에) 보이고,
                // 아직 반응이 한 번도 없었으면 두 자리 모두 "먼저 반응한 쪽" 검정을 위해 계속 지켜본다. 노출되면 거둔다.
                sa.result = pp.result();
                sb.result = pp.result();
                if (pp.hitSeen()) {
                    tracker.add(sa.kind == SiteKind.DECOY ? sa : sb);
                } else {
                    tracker.add(sa);
                    tracker.add(sb);
                }
            } else {
                tracker.add(sa);
                tracker.add(sb);
            }
        }
    }

    private void hideVoxels(UUID player, List<Voxel> vs) {
        List<Pos> ps = new ArrayList<>(vs.size());
        for (Voxel v : vs) {
            ps.add(v.pos());
        }
        display.hide(player, ps);
    }

    // ---- 시뮬레이터·테스트용 한 번에 ----

    /**
     * 이 플레이어에게 청크 (cx, cz)가 전송됐다: 광맥 표본을 배우고, 패킷 시야로 쌍을 정해 미끼를 Display.show로 전달하고 추적에 올린다.
     * 서버 플러그인은 이 함수 대신 prepareChunk/registerChunk를 쓴다.
     */
    public void onChunkSent(PlayerState p, int cx, int cz, long tick) {
        if (!p.eligible()) {
            return;
        }
        BlockView live = views.apply(p.world());
        if (live == null || !live.chunkLoaded(cx, cz)) {
            return;
        }
        learnChunk(p.world(), cx, cz);
        ChunkPlan.Patch patch = prepareChunk(p.id(), p.world(), cx, cz, new ChunkOnlyView(live, cx, cz));
        if (patch.isEmpty()) {
            return;
        }
        for (PlannedPair pp : patch.pairs()) {
            display.show(p.id(), pp.decoy()); // 뭉치마다(실제 패킷에서는 한 청크의 미끼가 함께 들어간다)
        }
        registerChunk(p, patch, tick);
    }

    /** 쌍의 두 뭉치 전체의 검사 결과(뚫림이 모름보다 우선). */
    private static DecoyGuard.Seal badSeal(BlockView view, PlannedPair pp) {
        DecoyGuard.Seal a = DecoyGuard.checkAll(view, pp.decoy());
        if (a.state() == DecoyGuard.State.BREACHED) {
            return a;
        }
        DecoyGuard.Seal b = DecoyGuard.checkAll(view, pp.placebo());
        return b.state() == DecoyGuard.State.BREACHED || a.sealed() ? b : a;
    }

    static boolean sealedAll(BlockView view, List<Voxel> voxels) {
        for (Voxel v : voxels) {
            if (!DecoyGuard.sealed(view, v.pos())) {
                return false;
            }
        }
        return true;
    }

    public void onMove(UUID player, String world, double x, double y, double z, long tick) {
        tracker.observePosition(player, world, x, y, z, tick);
    }

    /** 플레이어가 블록을 깬다(변경 전). 반응을 먼저 기록하고, 그 다음 노출 처리로 미끼를 거둔다. */
    public void onPlayerBreak(UUID player, Pos block, long tick) {
        tracker.observeDig(player, block, tick);
        tracker.blockChanging(block, tick);
    }

    /** 폭발·유체·피스톤·낙하 블록 등 누가 깨든 블록이 바뀌려 할 때(변경 전). */
    public void onBlockChanging(Pos block, long tick) {
        tracker.blockChanging(block, tick);
    }

    /**
     * 이벤트 없이 바뀐 경우를 잡는 주기 검사. 뭉치의 어느 블록이든 여섯 면이 하나라도 뚫렸으면 즉시 뭉치째 거둔다.
     * 밀도가 높으면 플레이어당 자리가 수천 개라 매번 전부 훑을 수 없다: 플레이어 24블록 안의 자리는 매번, 나머지는 10번에 한 번씩 돌아가며 본다.
     * (블록 변경 이벤트가 있는 변화는 이 검사와 상관없이 변경 전에 회수된다. 이 검사는 월드에딧 같은 이벤트 없는 변화용이다.)
     */
    public void verifyAll(long tick) {
        long call = ++verifyCalls;
        for (Site s : tracker.allActive()) {
            if ((s.pairId + call) % VERIFY_FAR_EVERY != 0 && !nearPlayer(s)) {
                continue;
            }
            verifySite(s, tick, RetireCause.PERIODIC_BREACH);
        }
    }

    /**
     * 자리 하나를 검사한다. 뚫렸으면 거둔다. 이웃 청크가 로드돼 있지 않아 블록을 모르면 판단을 보류한다(거두지 않는다):
     * 미끼는 이미 패킷 시야에서 봉인이 확인된 채 나갔고, 모르는 이웃이 나중에 로드되면 그때 다시 검사한다({@link #onChunkLoaded}).
     */
    private void verifySite(Site s, long tick, RetireCause cause) {
        BlockView v = views.apply(s.pos.world());
        if (v == null) {
            tracker.retire(s, Result.VOID, tick, true, new Reason(cause, "월드 " + s.pos.world() + "를 찾을 수 없다", tick));
            return;
        }
        DecoyGuard.Seal seal = DecoyGuard.checkAll(v, s.voxels);
        switch (seal.state()) {
            case SEALED -> s.held = null;
            case UNKNOWN -> s.held = DecoyGuard.explain(v, seal);
            case BREACHED -> tracker.retire(s, Result.VOID, tick, true, new Reason(cause, DecoyGuard.explain(v, seal), tick));
        }
    }

    /**
     * 청크가 서버에 로드됐다: 그 청크와 이웃 청크에 걸친 자리를 바로 다시 검사한다. 판단을 보류하던(이웃을 몰랐던) 자리가
     * 그사이 이벤트 없이 바뀌어 실제로 노출됐으면 여기서 거둔다. 아직 한 번도 검사되지 않은 자리도 같이 본다. 메인 스레드에서 부른다.
     */
    public void onChunkLoaded(String world, int cx, int cz, long tick) {
        for (Site s : tracker.allActive()) {
            if (!s.pos.world().equals(world)) {
                continue;
            }
            for (Voxel v : s.voxels) {
                if (Math.abs(v.pos().chunkX() - cx) <= 1 && Math.abs(v.pos().chunkZ() - cz) <= 1) {
                    verifySite(s, tick, RetireCause.CHUNK_LOAD_BREACH);
                    break;
                }
            }
        }
    }

    private boolean nearPlayer(Site s) {
        double[] p = tracker.lastPos(s.player);
        return p != null && s.distanceTo(p[0], p[1], p[2]) < VERIFY_NEAR;
    }

    public void expire(long tick) {
        tracker.expire(tick);
    }

    public void dropPlayer(UUID player, long tick, boolean restoreBlock) {
        tracker.dropPlayer(player, tick, restoreBlock);
    }

    /** 플레이어가 나갔다: 추적과 계획 기억을 모두 지운다(다시 들어오면 같은 시드에서 같은 자리가 다시 계산된다). */
    public void forgetPlayer(UUID player, long tick) {
        tracker.dropPlayerFinal(player, tick);
        tracker.forgetPosition(player);
        Map<ChunkPlan.Key, ChunkPlan> mine = plans.remove(player);
        if (mine != null) {
            synchronized (mine) {
                mine.values().forEach(cp -> cp.pairs().forEach(pp -> pairsById.remove(pp.pairId)));
            }
        }
    }

    /** 서버가 청크를 내렸다. */
    public void dropChunk(String world, int cx, int cz, long tick) {
        tracker.dropChunk(world, cx, cz, tick);
    }

    /** 이 플레이어의 클라이언트가 청크를 버렸다(PlayerChunkUnloadEvent). */
    public void dropChunkFor(UUID player, String world, int cx, int cz, long tick) {
        tracker.dropChunkFor(player, world, cx, cz, tick);
    }

    /** 플러그인 종료·리로드 시: 모든 미끼를 진짜 블록으로 되돌린다. */
    public void shutdown(long tick) {
        tracker.dropAll(tick);
    }

    // ---- /anchor debug ----

    /** 미끼·위약 한 자리의 상태. */
    public record SiteDebug(SiteKind kind, Pos pos, int blocks, String status) {
    }

    /** 이 플레이어에게 계획된 모든 자리와 상태. 관리자 점검용이다. */
    public List<SiteDebug> debugSites(UUID player) {
        List<SiteDebug> out = new ArrayList<>();
        Map<ChunkPlan.Key, ChunkPlan> mine = plans.get(player);
        if (mine == null) {
            return out;
        }
        List<ChunkPlan> snapshot;
        synchronized (mine) {
            snapshot = new ArrayList<>(mine.values());
        }
        for (ChunkPlan cp : snapshot) {
            for (PlannedPair pp : cp.pairs()) {
                out.add(debugOf(player, pp, SiteKind.DECOY, pp.decoy()));
                out.add(debugOf(player, pp, SiteKind.PLACEBO, pp.placebo()));
            }
        }
        return out;
    }

    private static String reasonText(PlannedPair pp) {
        return pp.reason() == null ? "기록 없음" : pp.reason().text();
    }

    private SiteDebug debugOf(UUID player, PlannedPair pp, SiteKind kind, List<Voxel> vs) {
        String status = null;
        for (Site s : tracker.sitesOf(player)) {
            if (s.pairId == pp.pairId && s.kind == kind) {
                status = s.result == null ? "활성" : "판정 완료(" + s.result + ", 화면 유지)";
                if (s.held != null) {
                    status += " · 판단 보류 중: " + s.held;
                }
                break;
            }
        }
        if (status == null) {
            if (pp.retired()) {
                status = "회수됨(다시 안 보냄) · 사유: " + reasonText(pp);
            } else if (pp.consumed()) {
                status = "판정 완료(" + pp.result() + ", 청크 밖: 다시 받으면 " + (kind == SiteKind.DECOY ? "미끼는 복원" : "새 관측 없음") + ")";
            } else {
                status = "대기(청크 밖: 다시 받으면 복원)";
            }
            if (!pp.retired() && pp.reason() != null) {
                status += " · 마지막 거둠 사유: " + reasonText(pp);
            }
        }
        return new SiteDebug(kind, vs.get(0).pos(), vs.size(), status);
    }
}

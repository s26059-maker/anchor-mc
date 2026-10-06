package dev.anchormc;

import dev.anchormc.core.BlockView;
import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Display;
import dev.anchormc.core.Outcome;
import dev.anchormc.core.Params;
import dev.anchormc.core.Result;
import dev.anchormc.core.SiteKind;
import dev.anchormc.evidence.EvidenceEngine;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.EvidenceStore;

import java.util.PriorityQueue;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * 배치 엔진과 증거 엔진을 잇는다. 플러그인과 시뮬레이터가 같은 배선을 쓴다.
 * 판정된 HIT/MISS만 증거로 가고 VOID는 세기만 한다. VOID가 난 쌍은 쌍 정확 검정에서도 통째로 뺀다.
 *
 * 증거는 판정이 나온 순서가 아니라 창이 끝나는 시각 순으로 쌓는다(3단계 수정). 반응은 창 안에서 일찍 나오고 무반응은 창이 끝나야 나와서,
 * 나온 순서대로 쌓으면 같은 시기의 반응이 무반응보다 먼저 들어가 e-value가 일시적으로 부풀고 "한 번이라도 문턱을 넘음"이 그 꼭대기를 잡는다.
 * 순서가 결과와 무관해야 부분곱이 귀무에서 상위마팅게일이 된다(Ville). 그래서 판정을 대기열에 두었다가 {@link #expire}가 창 끝 순서로 내보낸다.
 */
public final class AnchorCore {
    public final DecoyEngine decoys;
    public final EvidenceEngine evidence;
    private int voided;
    /** 시뮬레이터 진단용: 판정이 증거 엔진에 들어가기 직전에 본다(그 시점의 p0도 읽을 수 있다). 서버에서는 쓰지 않는다. */
    public volatile Consumer<Outcome> tap;
    /** false면 판정이 나온 즉시 증거에 넣는다(3단계 이전 동작, 시뮬레이터가 수정 전후를 비교하는 대조군용). 서버는 항상 true. */
    public volatile boolean holdUntilWindowEnd = true;

    /** 창이 끝나기를 기다리는 판정. 순서는 (창 끝 틱, 쌍 번호, 나온 순서): 결과와 무관하고, 한 쌍 안에서는 실제 반응 순서가 유지된다. */
    private record Held(UUID player, String name, boolean decoy, boolean hit, boolean late, long pairId, long due, long seq) {
    }

    private final PriorityQueue<Held> held = new PriorityQueue<>(java.util.Comparator.comparingLong(Held::due)
            .thenComparingLong(Held::pairId).thenComparingLong(Held::seq));
    private final Consumer<EvidenceEngine.Confirmation> onConfirm;
    private long seq;

    /** 시뮬레이터·테스트용: 비밀 시드를 rng에서 뽑는다. */
    public AnchorCore(Params params, EvidenceParams evidenceParams,
                      Function<String, BlockView> views, Display display, EvidenceStore store,
                      RandomGenerator rng, LongSupplier millis,
                      Consumer<EvidenceEngine.Confirmation> onConfirm) {
        this(evidenceParams, store, millis, onConfirm, sink -> new DecoyEngine(params, views, display, sink, rng));
    }

    /** 서버용: 비밀 시드를 config에서 받는다. */
    public AnchorCore(Params params, EvidenceParams evidenceParams,
                      Function<String, BlockView> views, Display display, EvidenceStore store,
                      byte[] secret, LongSupplier millis,
                      Consumer<EvidenceEngine.Confirmation> onConfirm) {
        this(evidenceParams, store, millis, onConfirm, sink -> new DecoyEngine(params, views, display, sink, secret));
    }

    private AnchorCore(EvidenceParams evidenceParams, EvidenceStore store, LongSupplier millis,
                       Consumer<EvidenceEngine.Confirmation> onConfirm,
                       Function<Consumer<Outcome>, DecoyEngine> makeEngine) {
        this.evidence = new EvidenceEngine(evidenceParams, store, millis);
        this.onConfirm = onConfirm;
        this.decoys = makeEngine.apply((Outcome o) -> {
            Consumer<Outcome> t = tap;
            if (t != null) {
                t.accept(o);
            }
            if (o.result() == Result.VOID) {
                voided++;
                evidence.voidPair(o.pairId());
                return;
            }
            Held h = new Held(o.player(), o.playerName(), o.kind() == SiteKind.DECOY, o.result() != Result.MISS,
                    o.result() == Result.LATE_HIT, o.pairId(), o.due(), seq++);
            if (holdUntilWindowEnd) {
                held.add(h);
            } else {
                apply(h);
            }
        });
    }

    private void apply(Held h) {
        EvidenceEngine.Confirmation c = h.late()
                ? evidence.observeLateHit(h.player(), h.name(), h.decoy(), h.pairId())
                : evidence.observe(h.player(), h.name(), h.decoy(), h.hit(), h.pairId());
        if (c != null) {
            onConfirm.accept(c);
        }
    }

    /**
     * 주기 호출(플러그인은 1초마다, 시뮬레이터는 엔진 틱마다): 창이 끝난 자리를 판정하고(DecoyEngine.expire), 창이 끝난 판정을 창 끝 순서로 증거에 넣는다.
     * 판정 만료 직후에 불러야 한다(그래야 창 끝 시각이 지난 자리가 모두 판정된 뒤에 순서대로 나간다).
     */
    public void expire(long tick) {
        decoys.expire(tick);
        release(tick);
    }

    private void release(long tick) {
        while (!held.isEmpty() && held.peek().due() <= tick) {
            apply(held.poll());
        }
    }

    /** 서버 종료·테스트용: 대기 중인 판정을 전부 같은 순서로 증거에 넣는다. */
    public void releaseAll() {
        while (!held.isEmpty()) {
            apply(held.poll());
        }
    }

    /** 창 끝을 기다리는 판정 수(점검용). */
    public int heldCount() {
        return held.size();
    }

    /**
     * 플레이어 한 명의 판정 기록(저장소 포함)·누적 e-value·반응 기록·화면의 미끼를 모두 초기화한다. 배치는 시드 그대로라 같은 자리가 새로 계획된다.
     * 지워진 계정 기록의 스냅샷을 돌려준다(기록이 없었으면 null). 메인 스레드에서 부른다.
     */
    public EvidenceEngine.View resetPlayer(java.util.UUID id, long tick) {
        held.removeIf(h -> h.player().equals(id)); // 창 끝을 기다리던 판정도 버린다
        java.util.List<Long> pairs = decoys.resetPlayer(id, tick);
        return evidence.resetPlayer(id, pairs);
    }

    /** 판정 전에 거둬 증거에서 뺀 자리 수. */
    public int voided() {
        return voided;
    }
}

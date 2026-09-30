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

import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.random.RandomGenerator;

/**
 * 배치 엔진과 증거 엔진을 잇는다. 플러그인과 시뮬레이터가 같은 배선을 쓴다.
 * 판정된 HIT/MISS만 증거로 가고 VOID는 세기만 한다. VOID가 난 쌍은 쌍 정확 검정에서도 통째로 뺀다.
 */
public final class AnchorCore {
    public final DecoyEngine decoys;
    public final EvidenceEngine evidence;
    private int voided;

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
        this.decoys = makeEngine.apply((Outcome o) -> {
            if (o.result() == Result.VOID) {
                voided++;
                evidence.voidPair(o.pairId());
                return;
            }
            if (o.result() == Result.LATE_HIT) {
                var lc = evidence.observeLateHit(o.player(), o.playerName(), o.kind() == SiteKind.DECOY, o.pairId());
                if (lc != null) {
                    onConfirm.accept(lc);
                }
                return;
            }
            var c = evidence.observe(o.player(), o.playerName(), o.kind() == SiteKind.DECOY, o.result() == Result.HIT, o.pairId());
            if (c != null) {
                onConfirm.accept(c);
            }
        });
    }

    /** 판정 전에 거둬 증거에서 뺀 자리 수. */
    public int voided() {
        return voided;
    }
}

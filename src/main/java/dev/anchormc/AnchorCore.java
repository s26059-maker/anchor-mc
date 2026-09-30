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
 * 판정된 HIT/MISS만 증거로 가고 VOID는 세기만 한다.
 */
public final class AnchorCore {
    public final DecoyEngine decoys;
    public final EvidenceEngine evidence;
    private int voided;

    public AnchorCore(Params params, EvidenceParams evidenceParams,
                      Function<String, BlockView> views, Display display, EvidenceStore store,
                      RandomGenerator rng, LongSupplier millis,
                      Consumer<EvidenceEngine.Confirmation> onConfirm) {
        this.evidence = new EvidenceEngine(evidenceParams, store, millis);
        this.decoys = new DecoyEngine(params, views, display, (Outcome o) -> {
            if (o.result() == Result.VOID) {
                voided++;
                return;
            }
            var c = evidence.observe(o.player(), o.playerName(), o.kind() == SiteKind.DECOY, o.result() == Result.HIT);
            if (c != null) {
                onConfirm.accept(c);
            }
        }, rng);
    }

    /** 판정 전에 거둬 증거에서 뺀 자리 수. */
    public int voided() {
        return voided;
    }
}

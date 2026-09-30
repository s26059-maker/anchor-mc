package dev.anchormc.evidence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 계정별 e-value 누적과 p0 추정.
 * 위약 반응은 전체 위약 반응률(p0 추정)과 계정 기록에만 쓰이고, 혼합 e-value는 미끼 반응으로만 쌓인다.
 * 쌍 정확 e-value는 (미끼, 위약) 쌍이 둘 다 판정된 뒤 한쪽만 반응한 쌍으로만 쌓인다(p0 불필요).
 * 메인 스레드에서만 호출한다.
 */
public final class EvidenceEngine {
    /** 새로 확정된 계정. */
    public record Confirmation(AccountRecord account, double p0) {
    }

    /** /anchor status용 스냅샷. */
    public record View(String name, int decoyN, int decoyHits, int placeboN, int placeboHits,
                       double log10E, boolean confirmed, long confirmedAt,
                       int pairDecoyOnly, int pairPlaceboOnly, int pairBoth, int pairNeither, double log10EPaired) {
        public double decoyRate() {
            return decoyN == 0 ? Double.NaN : (double) decoyHits / decoyN;
        }

        public double placeboRate() {
            return placeboN == 0 ? Double.NaN : (double) placeboHits / placeboN;
        }
    }

    /** /anchor stats용. */
    public record Stats(long placeboN, long placeboHits, double placeboRate, double p0, int confirmedAccounts) {
    }

    /** 쌍의 먼저 판정된 쪽. VOIDED면 짝 한쪽이 증거에서 빠져 쌍째 버린다. */
    private record Pending(boolean voided, boolean decoy, boolean hit) {
    }

    private static final int NO_PAIR = -1;

    private EvidenceParams params;
    private final EvidenceStore store;
    private final LongSupplier millis;
    private final Map<UUID, AccountRecord> cache = new HashMap<>();
    private final Map<Long, Pending> pending = new HashMap<>();
    private long placeboN;
    private long placeboHits;

    public EvidenceEngine(EvidenceParams params, EvidenceStore store, LongSupplier millis) {
        this.params = params;
        this.store = store;
        this.millis = millis;
        long[] t = store.placeboTotals();
        this.placeboN = t[0];
        this.placeboHits = t[1];
    }

    public void setParams(EvidenceParams p) {
        this.params = p;
    }

    /** 지금 e-value에 쓰는 p0: 위약 반응률의 배수. 위약 관측이 너무 적으면 상한. */
    public double currentP0() {
        if (placeboN < params.minPlaceboSamples() || placeboN == 0) {
            return EvidenceParams.P0_CAP;
        }
        double rate = placeboHits > 0
                ? (double) placeboHits / placeboN
                : 1 - Math.pow(0.05, 1.0 / placeboN); // 반응 0건이면 95% 상한
        return Math.min(EvidenceParams.P0_CAP, Math.max(EvidenceParams.P0_FLOOR, params.p0Multiplier() * rate));
    }

    private AccountRecord account(UUID id, String name) {
        AccountRecord r = cache.get(id);
        if (r == null) {
            r = store.load(id);
            if (r == null) {
                r = new AccountRecord(id, name);
            }
            cache.put(id, r);
        }
        r.name = name;
        return r;
    }

    /** 쌍 번호 없이 판정된 관측 하나(혼합 e-value만 갱신). */
    public Confirmation observe(UUID id, String name, boolean decoy, boolean hit) {
        return observe(id, name, decoy, hit, NO_PAIR);
    }

    /** 판정된 관측 하나. 이 호출로 새로 확정되면 그 정보를 돌려준다. pairId가 -1이면 쌍 검정에는 쓰지 않는다. */
    public Confirmation observe(UUID id, String name, boolean decoy, boolean hit, long pairId) {
        AccountRecord r = account(id, name);
        double p0 = currentP0(); // 이 관측 이전의 데이터로만 정한다
        if (decoy) {
            Mixture.observe(r.logs, hit, p0);
            r.decoyN++;
            if (hit) {
                r.decoyHits++;
            }
        } else {
            r.placeboN++;
            placeboN++;
            if (hit) {
                r.placeboHits++;
                placeboHits++;
            }
        }
        if (pairId != NO_PAIR) {
            Pending first = pending.remove(pairId);
            if (first == null) {
                pending.put(pairId, new Pending(false, decoy, hit));
            } else if (!first.voided()) {
                boolean decoyHit = decoy ? hit : first.hit();
                boolean placeboHit = decoy ? first.hit() : hit;
                if (decoyHit && placeboHit) {
                    r.pairBoth++;
                } else if (!decoyHit && !placeboHit) {
                    r.pairNeither++;
                } else if (decoyHit) {
                    r.pairDecoyOnly++;
                    Mixture.observePaired(r.logsPaired, true);
                } else {
                    r.pairPlaceboOnly++;
                    Mixture.observePaired(r.logsPaired, false);
                }
            }
        }
        Confirmation out = null;
        if (!r.confirmed() && meetsRule(r)) {
            r.confirmedAt = millis.getAsLong();
            out = new Confirmation(r.copy(), p0);
        }
        store.save(r);
        return out;
    }

    /** 쌍의 한쪽이 판정 없이(VOID) 거둬졌다: 이 쌍은 쌍 검정에서 뺀다. */
    public void voidPair(long pairId) {
        if (pairId == NO_PAIR) {
            return;
        }
        Pending first = pending.remove(pairId);
        if (first == null) {
            pending.put(pairId, new Pending(true, false, false));
        }
    }

    private boolean meetsRule(AccountRecord r) {
        boolean mix = Mixture.logE(r.logs) >= Math.log(1 / params.alpha());
        return switch (params.rule()) {
            case MIXTURE -> mix;
            case PAIRED -> Mixture.logE(r.logsPaired) >= Math.log(1 / params.alpha());
            case BOTH -> mix && Mixture.logE(r.logsPaired) >= Math.log(1 / params.pairedAlpha());
        };
    }

    public View view(String name) {
        AccountRecord r = null;
        for (AccountRecord c : cache.values()) {
            if (c.name.equalsIgnoreCase(name)) {
                r = c;
                break;
            }
        }
        if (r == null) {
            r = store.findByName(name);
        }
        return r == null ? null : toView(r);
    }

    /** 이미 본 계정만(저장소는 안 봄). 시뮬레이터·호출이 잦은 곳용. 없으면 null. */
    public View viewOf(UUID id) {
        AccountRecord r = cache.get(id);
        return r == null ? null : toView(r);
    }

    private static View toView(AccountRecord r) {
        return new View(r.name, r.decoyN, r.decoyHits, r.placeboN, r.placeboHits,
                r.log10E(), r.confirmed(), r.confirmedAt,
                r.pairDecoyOnly, r.pairPlaceboOnly, r.pairBoth, r.pairNeither, r.log10EPaired());
    }

    public Stats stats() {
        double rate = placeboN == 0 ? Double.NaN : (double) placeboHits / placeboN;
        return new Stats(placeboN, placeboHits, rate, currentP0(), store.confirmedCount());
    }

    /** 짝을 기다리는 쌍 수(테스트·점검용). */
    public int pendingPairs() {
        return pending.size();
    }
}

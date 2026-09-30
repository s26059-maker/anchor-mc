package dev.anchormc.evidence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.LongSupplier;

/**
 * 계정별 e-value 누적과 p0 추정.
 * 위약 반응은 전체 위약 반응률(p0 추정)과 계정 기록에만 쓰이고, e-value는 미끼 반응으로만 쌓인다.
 * 메인 스레드에서만 호출한다.
 */
public final class EvidenceEngine {
    /** 새로 확정된 계정. */
    public record Confirmation(AccountRecord account, double p0) {
    }

    /** /anchor status용 스냅샷. */
    public record View(String name, int decoyN, int decoyHits, int placeboN, int placeboHits,
                       double log10E, boolean confirmed, long confirmedAt) {
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

    private EvidenceParams params;
    private final EvidenceStore store;
    private final LongSupplier millis;
    private final Map<UUID, AccountRecord> cache = new HashMap<>();
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

    /** 판정된 관측 하나. 이 호출로 새로 확정되면 그 정보를 돌려준다. */
    public Confirmation observe(UUID id, String name, boolean decoy, boolean hit) {
        AccountRecord r = account(id, name);
        Confirmation out = null;
        if (decoy) {
            double p0 = currentP0(); // 이 관측 이전의 데이터로만 정한다
            Mixture.observe(r.logs, hit, p0);
            r.decoyN++;
            if (hit) {
                r.decoyHits++;
            }
            if (!r.confirmed() && Mixture.logE(r.logs) >= Math.log(1 / params.alpha())) {
                r.confirmedAt = millis.getAsLong();
                out = new Confirmation(r.copy(), p0);
            }
        } else {
            r.placeboN++;
            placeboN++;
            if (hit) {
                r.placeboHits++;
                placeboHits++;
            }
        }
        store.save(r);
        return out;
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
                r.log10E(), r.confirmed(), r.confirmedAt);
    }

    public Stats stats() {
        double rate = placeboN == 0 ? Double.NaN : (double) placeboHits / placeboN;
        return new Stats(placeboN, placeboHits, rate, currentP0(), store.confirmedCount());
    }
}

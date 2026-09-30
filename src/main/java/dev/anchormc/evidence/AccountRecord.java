package dev.anchormc.evidence;

import java.util.UUID;

/** 계정 하나의 누적 증거. */
public final class AccountRecord {
    public final UUID id;
    public String name;
    public int decoyN;
    public int decoyHits;
    public int placeboN;
    public int placeboHits;
    public double[] logs;
    /** 쌍 정확 검정: 미끼만 반응 / 위약만 반응 / 둘 다 / 둘 다 안 함한 쌍 수. */
    public int pairDecoyOnly;
    public int pairPlaceboOnly;
    public int pairBoth;
    public int pairNeither;
    public double[] logsPaired;
    /** 확정된 시각(epoch ms). 0이면 아직. */
    public long confirmedAt;

    public AccountRecord(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.logs = Mixture.newLogs();
        this.logsPaired = Mixture.newPairedLogs();
    }

    public AccountRecord copy() {
        AccountRecord c = new AccountRecord(id, name);
        c.decoyN = decoyN;
        c.decoyHits = decoyHits;
        c.placeboN = placeboN;
        c.placeboHits = placeboHits;
        c.logs = logs.clone();
        c.pairDecoyOnly = pairDecoyOnly;
        c.pairPlaceboOnly = pairPlaceboOnly;
        c.pairBoth = pairBoth;
        c.pairNeither = pairNeither;
        c.logsPaired = logsPaired.clone();
        c.confirmedAt = confirmedAt;
        return c;
    }

    public double log10E() {
        return Mixture.log10E(logs);
    }

    public double log10EPaired() {
        return Mixture.log10E(logsPaired);
    }

    public boolean confirmed() {
        return confirmedAt != 0;
    }
}

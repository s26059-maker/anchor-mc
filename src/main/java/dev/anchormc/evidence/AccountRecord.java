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
    /** 먼저 반응한 쪽 검정(1.2단계): 반응이 있었던 쌍마다 미끼가 먼저 반응했나 위약이 먼저 반응했나. */
    public int firstDecoy;
    public int firstPlacebo;
    public double[] logsFirst;
    /**
     * 한 번이라도 문턱을 넘었나(비트): 1 = 혼합 E ≥ 1/alpha, 2 = 먼저 반응한 쪽 E ≥ 1/pairedAlpha, 4 = 먼저 반응한 쪽 E ≥ 1/alpha.
     * 확정은 e-과정의 "언젠가 문턱을 넘음"에 대한 것(Ville)이라 두 과정을 각각 그 기준으로 묶는다. 한 번 넘으면 이후 E가 내려가도 유지한다.
     */
    public int ever;
    public static final int EVER_MIX = 1, EVER_FIRST_GUARD = 2, EVER_FIRST_STRICT = 4;
    /** 확정된 시각(epoch ms). 0이면 아직. */
    public long confirmedAt;

    public AccountRecord(UUID id, String name) {
        this.id = id;
        this.name = name;
        this.logs = Mixture.newLogs();
        this.logsPaired = Mixture.newPairedLogs();
        this.logsFirst = Mixture.newPairedLogs();
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
        c.firstDecoy = firstDecoy;
        c.firstPlacebo = firstPlacebo;
        c.logsFirst = logsFirst.clone();
        c.ever = ever;
        c.confirmedAt = confirmedAt;
        return c;
    }

    public double log10E() {
        return Mixture.log10E(logs);
    }

    public double log10EPaired() {
        return Mixture.log10E(logsPaired);
    }

    public double log10EFirst() {
        return Mixture.log10E(logsFirst);
    }

    public boolean confirmed() {
        return confirmedAt != 0;
    }
}

package dev.anchormc.evidence;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 테스트·시뮬레이터용 메모리 저장소. */
public final class MemoryStore implements EvidenceStore {
    private final Map<UUID, AccountRecord> map = new HashMap<>();

    @Override
    public synchronized AccountRecord load(UUID id) {
        AccountRecord r = map.get(id);
        return r == null ? null : r.copy();
    }

    @Override
    public synchronized AccountRecord findByName(String name) {
        for (AccountRecord r : map.values()) {
            if (r.name.equalsIgnoreCase(name)) {
                return r.copy();
            }
        }
        return null;
    }

    @Override
    public synchronized void save(AccountRecord r) {
        map.put(r.id, r.copy());
    }

    @Override
    public synchronized void delete(UUID id) {
        map.remove(id);
    }

    @Override
    public synchronized long[] placeboTotals() {
        long n = 0, h = 0;
        for (AccountRecord r : map.values()) {
            n += r.placeboN;
            h += r.placeboHits;
        }
        return new long[] {n, h};
    }

    @Override
    public synchronized int confirmedCount() {
        return (int) map.values().stream().filter(AccountRecord::confirmed).count();
    }

    @Override
    public void close() {
    }
}

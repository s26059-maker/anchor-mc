package dev.anchormc.evidence;

import java.util.UUID;

/** 증거 저장소. SQLite 말고 MySQL·Redis 구현으로 갈아 끼울 수 있게 이 인터페이스만 쓴다. */
public interface EvidenceStore extends AutoCloseable {
    /** 없으면 null. */
    AccountRecord load(UUID id);

    /** 이름(대소문자 무시)으로 가장 최근 계정. 없으면 null. */
    AccountRecord findByName(String name);

    void save(AccountRecord record);

    /** 계정 기록을 지운다(없으면 아무것도 안 한다). */
    void delete(UUID id);

    /** 전체 위약 관측 수와 반응 수: {n, hits}. */
    long[] placeboTotals();

    /** 확정된 계정 수. */
    int confirmedCount();

    @Override
    void close();
}

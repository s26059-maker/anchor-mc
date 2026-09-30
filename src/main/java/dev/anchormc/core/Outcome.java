package dev.anchormc.core;

import java.util.UUID;

/** pairId: 같은 동전 던지기로 만든 (미끼, 위약) 쌍의 번호. 쌍 정확 검정이 짝을 맞추는 데 쓴다. */
public record Outcome(UUID player, String playerName, SiteKind kind, Result result, long tick, Pos pos, long pairId) {
}

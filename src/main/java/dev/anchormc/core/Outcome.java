package dev.anchormc.core;

import java.util.UUID;

/**
 * pairId: 같은 동전 던지기로 만든 (미끼, 위약) 쌍의 번호. 쌍 정확 검정이 짝을 맞추는 데 쓴다.
 * due: 이 자리의 판정 창이 끝나는 틱(생성 틱 + 창). 반응(HIT)은 창 안에서 일찍 나오고 무반응(MISS)은 창이 끝나야 나오므로,
 * 판정이 나온 순서는 결과에 의존한다. 혼합 e-value는 결과와 무관한 이 시각의 순서로 쌓는다(AnchorCore).
 */
public record Outcome(UUID player, String playerName, SiteKind kind, Result result, long tick, Pos pos, long pairId, long due) {
    public Outcome(UUID player, String playerName, SiteKind kind, Result result, long tick, Pos pos, long pairId) {
        this(player, playerName, kind, result, tick, pos, pairId, tick);
    }
}

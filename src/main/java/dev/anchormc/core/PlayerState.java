package dev.anchormc.core;

import java.util.UUID;

/**
 * 엔진이 한 플레이어에 대해 알아야 하는 것. eligible=false(크리에이티브·관전 등)면 새 자리를 만들지 않고 있던 것도 거둔다.
 * spectator=true면 관전자 모드다(디버그로 자격을 준 경우에도): 이때의 반응은 증거에 넣지 않고 표시만 유지한다.
 */
public record PlayerState(UUID id, String name, String world, double x, double y, double z, boolean eligible, boolean spectator) {
    public PlayerState(UUID id, String name, String world, double x, double y, double z, boolean eligible) {
        this(id, name, world, x, y, z, eligible, false);
    }
}

package dev.anchormc.core;

import java.util.UUID;

/** 엔진이 한 플레이어에 대해 알아야 하는 것. eligible=false(크리에이티브·관전 등)면 새 자리를 만들지 않고 있던 것도 거둔다. */
public record PlayerState(UUID id, String name, String world, double x, double y, double z, boolean eligible) {
}

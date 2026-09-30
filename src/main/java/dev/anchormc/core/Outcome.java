package dev.anchormc.core;

import java.util.UUID;

public record Outcome(UUID player, String playerName, SiteKind kind, Result result, long tick, Pos pos) {
}

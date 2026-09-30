package dev.anchormc.core;

import java.util.UUID;

/** 미끼 또는 위약 한 자리. 종류(kind)만 다르고 상태와 수명은 같다. */
public final class Site {
    public final UUID player;
    public final String playerName;
    public final SiteKind kind;
    public final Pos pos;
    public final Host host;
    public final long createdTick;
    Result result;
    boolean active = true;

    Site(UUID player, String playerName, SiteKind kind, Pos pos, Host host, long createdTick) {
        this.player = player;
        this.playerName = playerName;
        this.kind = kind;
        this.pos = pos;
        this.host = host;
        this.createdTick = createdTick;
    }

    /** 아직 판정 전이면 null. */
    public Result result() {
        return result;
    }

    public boolean active() {
        return active;
    }
}

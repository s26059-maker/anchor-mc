package dev.anchormc.plugin;

import dev.anchormc.core.Eligibility;
import dev.anchormc.core.PlayerState;
import org.bukkit.GameMode;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 패킷 스레드가 Bukkit API를 만지지 않고 "이 플레이어에게 미끼를 넣어도 되나, 지금 어느 월드인가"를 알기 위한 표.
 * 메인 스레드가 이벤트와 1초 주기로 갱신하고 패킷 스레드는 읽기만 한다.
 */
final class PlayerRegistry {
    record Info(String world, boolean eligible) {
    }

    private final Map<UUID, Info> map = new ConcurrentHashMap<>();
    /** 디버그 옵션(debug.allow-spectator). 켜져 있으면 관전자도 자격이 있다. */
    private volatile boolean allowSpectator;

    void setAllowSpectator(boolean on) {
        this.allowSpectator = on;
    }

    boolean allowSpectator() {
        return allowSpectator;
    }

    /** 이 게임모드가 (살아 있고 접속 중일 때) 자격이 있나. */
    boolean eligibleMode(GameMode gm) {
        return Eligibility.eligible(gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE, gm == GameMode.SPECTATOR, allowSpectator, true, false);
    }

    Info get(UUID id) {
        return map.get(id);
    }

    void update(Player p) {
        PlayerState s = stateOf(p);
        map.put(p.getUniqueId(), new Info(s.world(), s.eligible()));
    }

    void set(UUID id, String world, boolean eligible) {
        map.put(id, new Info(world, eligible));
    }

    void remove(UUID id) {
        map.remove(id);
    }

    void clear() {
        map.clear();
    }

    /** 메인 스레드에서 플레이어의 현재 상태. */
    PlayerState stateOf(Player p) {
        GameMode gm = p.getGameMode();
        boolean eligible = Eligibility.eligible(gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE, gm == GameMode.SPECTATOR, allowSpectator,
                p.isOnline(), p.isDead());
        var l = p.getLocation();
        return new PlayerState(p.getUniqueId(), p.getName(), l.getWorld().getName(), l.getX(), l.getY() + 1.0, l.getZ(), eligible, gm == GameMode.SPECTATOR);
    }

    /** 이미 나간 플레이어: 자격 없음으로 등록해 나간 미끼를 되돌리게 한다. */
    static PlayerState gone(UUID id, Info info) {
        return new PlayerState(id, "", info.world(), 0, 0, 0, false);
    }
}

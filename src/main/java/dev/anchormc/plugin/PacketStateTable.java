package dev.anchormc.plugin;

import com.github.retrooper.packetevents.protocol.player.ClientVersion;
import com.github.retrooper.packetevents.protocol.world.states.WrappedBlockState;
import com.github.retrooper.packetevents.protocol.world.states.type.StateTypes;
import dev.anchormc.core.Host;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.ToIntFunction;

/**
 * 청크 패킷 안의 블록 상태 번호(전역 ID)를 분류한다. 상태 번호별 결과를 캐시하고 스레드 안전이라 패킷 스레드에서 쓴다.
 * 분류 규칙(불투명·안정 여부)은 블록 이름으로 받는 classifier가 정한다. 서버는 Bukkit Material로, 테스트는 이름 목록으로 만든다.
 */
public final class PacketStateTable {
    public static final int OPAQUE = 1, STONE = 2, DEEPSLATE = 4, DIAMOND = 8;

    private final ClientVersion version;
    private final ToIntFunction<String> classifier;
    private final Map<Integer, Integer> cache = new ConcurrentHashMap<>();
    private final int stoneOreId, deepslateOreId;

    /** version: 패킷의 상태 번호가 따르는 버전(서버 버전). classifier: 블록 이름("stone" 형태) → 위 플래그의 합. */
    public PacketStateTable(ClientVersion version, ToIntFunction<String> classifier) {
        this.version = version;
        this.classifier = classifier;
        this.stoneOreId = WrappedBlockState.getDefaultState(version, StateTypes.DIAMOND_ORE).getGlobalId();
        this.deepslateOreId = WrappedBlockState.getDefaultState(version, StateTypes.DEEPSLATE_DIAMOND_ORE).getGlobalId();
    }

    public int flags(int globalId) {
        return cache.computeIfAbsent(globalId, id -> {
            try {
                String name = WrappedBlockState.getByGlobalId(version, id).getType().getName();
                int colon = name.indexOf(':');
                return classifier.applyAsInt(colon >= 0 ? name.substring(colon + 1) : name);
            } catch (RuntimeException e) {
                return 0;
            }
        });
    }

    /** 미끼로 넣을 블록 상태 번호(바탕이 돌이면 일반, 심층암이면 심층 다이아 광석). */
    public int decoyId(Host host) {
        return host == Host.DEEPSLATE ? deepslateOreId : stoneOreId;
    }
}

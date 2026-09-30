package dev.anchormc.plugin;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.PacketEventsAPI;
import com.github.retrooper.packetevents.injector.ChannelInjector;
import com.github.retrooper.packetevents.manager.player.PlayerManager;
import com.github.retrooper.packetevents.manager.protocol.ProtocolManager;
import com.github.retrooper.packetevents.manager.server.ServerManager;
import com.github.retrooper.packetevents.manager.server.ServerVersion;
import com.github.retrooper.packetevents.netty.NettyManager;

/**
 * 서버 없이 PacketEvents 정적 초기화(블록 상태 매핑 로드는 PacketEvents.getAPI().getSettings()를 요구한다)를 통과시키는 테스트 보조.
 * 추상 메서드만 비운 최소 구현이라 netty 같은 서버 쪽 클래스가 필요 없다.
 */
final class PacketEventsTestSupport {
    private PacketEventsTestSupport() {
    }

    static synchronized void init() {
        if (PacketEvents.getAPI() == null) {
            PacketEvents.setAPI(new PacketEventsAPI<Object>() {
                @Override
                public boolean isLoaded() {
                    return true;
                }

                @Override
                public void init() {
                }

                @Override
                public boolean isInitialized() {
                    return true;
                }

                @Override
                public boolean isTerminated() {
                    return false;
                }

                @Override
                public Object getPlugin() {
                    return null;
                }

                @Override
                public ServerManager getServerManager() {
                    return () -> ServerVersion.V_26_2;
                }

                @Override
                public ProtocolManager getProtocolManager() {
                    return null;
                }

                @Override
                public PlayerManager getPlayerManager() {
                    return null;
                }

                @Override
                public NettyManager getNettyManager() {
                    return null;
                }

                @Override
                public ChannelInjector getInjector() {
                    return null;
                }
            });
        }
    }
}

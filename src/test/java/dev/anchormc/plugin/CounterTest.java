package dev.anchormc.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * /anchor stats의 "청크 패킷에 미끼 삽입" 카운터는 저장되지 않는 메모리 값이라 플러그인을 켤 때마다 0에서 시작한다
 * (Bukkit 클래스 없이는 리스너를 만들 수 없어 소스로 확인한다).
 */
class CounterTest {
    @Test
    void insertCounterIsInMemoryOnlyAndIncrementedPerPatchedChunk() throws Exception {
        String src = Files.readString(Path.of("src/main/java/dev/anchormc/plugin/PacketDecoyListener.java"));
        assertTrue(src.contains("patched = new AtomicLong()"), "0에서 시작하는 메모리 카운터여야 한다");
        assertFalse(src.contains("Store") || src.contains("getConfig") || src.contains("Files."), "카운터가 어딘가에 저장되면 안 된다");
        assertEquals(1, src.lines().filter(l -> l.contains("patched.incrementAndGet()")).count());
        String plugin = Files.readString(Path.of("src/main/java/dev/anchormc/plugin/AnchorPlugin.java"));
        assertTrue(plugin.contains("이번 기동 이후 청크 패킷에 미끼 삽입"), "stats가 '이번 기동 이후'임을 밝혀야 한다");
    }
}

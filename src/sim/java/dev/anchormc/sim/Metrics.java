package dev.anchormc.sim;

import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 시뮬레이터 전역 계측: 네트워크 패킷 수, 엔진 배치 시간, 미끼 뭉치 모양. 단일 스레드에서만 쓴다. */
final class Metrics {
    static long chunkSends, chunkNanos, revisitSends, revisitNanos, embeddedBlocks, embeddedClusters;
    static long showCalls, showPackets, showBlocks, hidePackets, hideBlocks, hideCalls;
    /** 미끼 뭉치 크기, 최소 y(엔진 좌표), y 두께. */
    static final List<int[]> decoyClusters = new ArrayList<>();
    static boolean recordClusters;

    private Metrics() {
    }

    static void reset() {
        chunkSends = chunkNanos = revisitSends = revisitNanos = embeddedBlocks = embeddedClusters = 0;
        showCalls = showPackets = showBlocks = hidePackets = hideBlocks = hideCalls = 0;
        decoyClusters.clear();
    }

    static void chunkSend(long nanos, boolean revisit) {
        chunkSends++;
        chunkNanos += nanos;
        if (revisit) {
            revisitSends++;
            revisitNanos += nanos;
        }
    }

    /** 청크 데이터 안에 들어간 미끼 뭉치(별도 패킷이 없다). */
    static void embedded(List<Voxel> vs, boolean revisit) {
        embeddedClusters++;
        embeddedBlocks += vs.size();
        if (!revisit) {
            record(vs); // 같은 청크를 다시 받아 같은 뭉치가 또 들어가는 것은 모양 통계에 다시 세지 않는다
        }
    }

    private static void record(List<Voxel> vs) {
        if (recordClusters) {
            int miny = Integer.MAX_VALUE, maxy = Integer.MIN_VALUE;
            for (Voxel v : vs) {
                miny = Math.min(miny, v.pos().y());
                maxy = Math.max(maxy, v.pos().y());
            }
            decoyClusters.add(new int[] {vs.size(), miny, maxy - miny + 1});
        }
    }

    /** 뭉치를 청크 섹션(16^3)별로 묶은 패킷 수(sendMultiBlockChange가 섹션마다 패킷 하나를 낸다). */
    private static int sections(List<Pos> ps) {
        Set<Long> s = new HashSet<>();
        for (Pos p : ps) {
            s.add(((long) (p.x() >> 4) << 40) ^ ((long) (p.z() >> 4) << 16) ^ (p.y() >> 4) + 64);
        }
        return s.size();
    }

    static void show(List<Voxel> vs) {
        List<Pos> ps = new ArrayList<>(vs.size());
        int miny = Integer.MAX_VALUE, maxy = Integer.MIN_VALUE;
        for (Voxel v : vs) {
            ps.add(v.pos());
            miny = Math.min(miny, v.pos().y());
            maxy = Math.max(maxy, v.pos().y());
        }
        showCalls++;
        showPackets += sections(ps);
        showBlocks += vs.size();
        record(vs);
    }

    static void hide(List<Pos> ps) {
        hideCalls++;
        hidePackets += sections(ps);
        hideBlocks += ps.size();
    }
}

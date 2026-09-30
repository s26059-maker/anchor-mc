package dev.anchormc.sim;

import dev.anchormc.core.Pos;
import dev.anchormc.core.Voxel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** 시뮬레이터 전역 계측: 네트워크 패킷 수, 엔진 배치 시간, 미끼 뭉치 모양. 단일 스레드에서만 쓴다. */
final class Metrics {
    static long chunkSends, chunkNanos;
    static long showCalls, showPackets, showBlocks, hidePackets, hideBlocks, hideCalls;
    /** 미끼 뭉치 크기, 최소 y(엔진 좌표), y 두께. */
    static final List<int[]> decoyClusters = new ArrayList<>();
    static boolean recordClusters;

    private Metrics() {
    }

    static void reset() {
        chunkSends = chunkNanos = 0;
        showCalls = showPackets = showBlocks = hidePackets = hideBlocks = hideCalls = 0;
        decoyClusters.clear();
    }

    static void chunkSend(long nanos) {
        chunkSends++;
        chunkNanos += nanos;
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
        if (recordClusters) {
            decoyClusters.add(new int[] {vs.size(), miny, maxy - miny + 1});
        }
    }

    static void hide(List<Pos> ps) {
        hideCalls++;
        hidePackets += sections(ps);
        hideBlocks += ps.size();
    }
}

package dev.anchormc.plugin;

import com.github.retrooper.packetevents.protocol.world.chunk.BaseChunk;
import com.github.retrooper.packetevents.protocol.world.chunk.Column;
import dev.anchormc.core.BlockView;
import dev.anchormc.core.Host;

/**
 * 청크 데이터 패킷이 담은 블록으로 만든 BlockView. 클라이언트가 이 패킷에서 보게 될 세계와 정확히 같다(이 청크만: 이웃 청크는 "로드 안 됨").
 * 서버 월드를 읽지 않으므로 패킷을 만드는 스레드에서 안전하다. 읽기 전용이다.
 */
final class PacketChunkView implements BlockView {
    private final BaseChunk[] sections;
    private final int cx, cz, minY;
    private final PacketStateTable table;

    PacketChunkView(Column column, int minY, PacketStateTable table) {
        this.sections = column.getChunks();
        this.cx = column.getX();
        this.cz = column.getZ();
        this.minY = minY;
        this.table = table;
    }

    /** 이 블록의 패킷 안 위치가 유효하면 섹션 번호, 아니면 -1. */
    int sectionIndex(int x, int y, int z) {
        if ((x >> 4) != cx || (z >> 4) != cz || y < minY) {
            return -1;
        }
        int s = (y - minY) >> 4;
        return s < sections.length ? s : -1;
    }

    BaseChunk section(int index) {
        return sections[index];
    }

    int minY() {
        return minY;
    }

    private int flags(int x, int y, int z) {
        int s = sectionIndex(x, y, z);
        if (s < 0 || sections[s] == null) {
            return 0;
        }
        return table.flags(sections[s].getBlockId(x & 15, (y - minY) & 15, z & 15));
    }

    @Override
    public boolean isStableOpaque(int x, int y, int z) {
        return (flags(x, y, z) & PacketStateTable.OPAQUE) != 0;
    }

    @Override
    public Host hostAt(int x, int y, int z) {
        int f = flags(x, y, z);
        if ((f & PacketStateTable.STONE) != 0) {
            return Host.STONE;
        }
        return (f & PacketStateTable.DEEPSLATE) != 0 ? Host.DEEPSLATE : null;
    }

    @Override
    public boolean isDiamondOre(int x, int y, int z) {
        return (flags(x, y, z) & PacketStateTable.DIAMOND) != 0;
    }

    @Override
    public boolean chunkLoaded(int c1, int c2) {
        return c1 == cx && c2 == cz;
    }
}

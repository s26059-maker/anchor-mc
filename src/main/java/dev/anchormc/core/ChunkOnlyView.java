package dev.anchormc.core;

/**
 * 한 청크 안의 블록만 보이는 창. 청크 데이터 패킷이 담고 있는 정보와 같다: 이웃 청크는 "로드 안 됨"이다.
 * 시뮬레이터와 테스트에서 실제 월드를 패킷 시야로 좁히는 데 쓴다(플러그인은 패킷 데이터로 직접 만든 BlockView를 쓴다).
 */
public final class ChunkOnlyView implements BlockView {
    private final BlockView base;
    private final int cx, cz;

    public ChunkOnlyView(BlockView base, int cx, int cz) {
        this.base = base;
        this.cx = cx;
        this.cz = cz;
    }

    private boolean in(int x, int z) {
        return (x >> 4) == cx && (z >> 4) == cz;
    }

    @Override
    public boolean isStableOpaque(int x, int y, int z) {
        return in(x, z) && base.isStableOpaque(x, y, z);
    }

    @Override
    public Host hostAt(int x, int y, int z) {
        return in(x, z) ? base.hostAt(x, y, z) : null;
    }

    @Override
    public boolean isDiamondOre(int x, int y, int z) {
        return in(x, z) && base.isDiamondOre(x, y, z);
    }

    @Override
    public boolean chunkLoaded(int c1, int c2) {
        return c1 == cx && c2 == cz;
    }
}

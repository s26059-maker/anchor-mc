package dev.anchormc.core;

/** 한 월드의 서버 쪽 블록 상태를 읽는 창. 읽기 전용이다(불변식 4: 서버 월드는 바꾸지 않는다). */
public interface BlockView {
    /**
     * 불투명 고체이면서 스스로 사라지지 않는 블록이면 true.
     * 중력 블록·피스톤류·TNT 같은 불안정 블록, 투명·부분 블록, 로드되지 않은 곳, 월드 밖은 전부 false.
     */
    boolean isStableOpaque(int x, int y, int z);

    /** 미끼가 들어갈 수 있는 돌 계열이면 그 종류, 아니면 null. 로드되지 않은 곳도 null. */
    Host hostAt(int x, int y, int z);

    /** 진짜 다이아 광석(일반·심층)이면 true. 광맥 표본 추출과 "진짜 광석에 붙지 않기" 검사에 쓴다. */
    default boolean isDiamondOre(int x, int y, int z) {
        return false;
    }

    /** 회수 사유 기록용: 그 좌표의 블록을 사람이 읽을 수 있게 설명한다(플러그인은 재질 이름을 돌려준다). */
    default String describe(int x, int y, int z) {
        return isStableOpaque(x, y, z) ? "블록=불투명 고체" : "블록=불투명 고체 아님";
    }

    /** 그 청크가 로드돼 있으면 true. */
    default boolean chunkLoaded(int cx, int cz) {
        return true;
    }
}

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
}

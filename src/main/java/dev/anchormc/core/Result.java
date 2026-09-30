package dev.anchormc.core;

public enum Result {
    /** 반경 안까지 옴. */
    HIT,
    /** 시간 창이 끝나거나 멀어질 때까지 안 옴. */
    MISS,
    /** 노출 위험·언로드·접속 종료 등으로 판정 전에 거둠. 증거에서 제외한다. */
    VOID
}

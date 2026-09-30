package dev.anchormc.core;

/** 미끼 자격 규칙 한 곳. 플러그인(게임모드·생사)을 읽어 이 함수에 넘긴다. */
public final class Eligibility {
    private Eligibility() {
    }

    /**
     * 서바이벌·모험이고 접속 중이며 살아 있으면 자격이 있다. 관전자는 디버그 옵션(debug.allow-spectator)이 켜졌을 때만 자격이 있다
     * (실서버 시각 확인용: 관전자로 벽 속을 날며 미끼가 보이는지 본다). 크리에이티브와 그 밖의 모드는 늘 자격이 없다.
     */
    public static boolean eligible(boolean survivalOrAdventure, boolean spectator, boolean allowSpectator, boolean online, boolean dead) {
        return (survivalOrAdventure || (spectator && allowSpectator)) && online && !dead;
    }
}

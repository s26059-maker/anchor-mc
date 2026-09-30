package dev.anchormc.core;

/**
 * 자리를 거두거나 계획을 접은 이유. permanent면 그 쌍은 다시 보내지 않고(노출 위험·바뀐 월드), 아니면 화면에서만 거두고 계획은 남긴다
 * (자격을 되찾거나 청크를 다시 받으면 같은 미끼가 돌아온다).
 */
public enum RetireCause {
    PACKET_UNSEALED("패킷 시야에서 봉인 깨짐(보내기 전)", true),
    REGISTER_UNSEALED("등록 때 실제 월드에서 봉인 깨짐", true),
    PERIODIC_BREACH("주기 검사: 이웃이 불투명 고체가 아님", true),
    CHUNK_LOAD_BREACH("보류하던 이웃 청크가 로드된 뒤 노출됨", true),
    BLOCK_EVENT("블록 변경 이벤트", true),
    TOO_CLOSE("밀착 거리", true),
    INELIGIBLE("플레이어 자격 상실(게임모드·사망 등)", false),
    REGISTER_NOT_ALLOWED("등록 때 자격 없음·월드 다름", false),
    LEFT_WORLD("월드 이동", false),
    QUIT("퇴장", false),
    CHUNK_DROPPED("청크 언로드(회수 아님, 노출 시간 정지)", false),
    SHUTDOWN("플러그인 종료·리로드", false);

    private final String label;
    private final boolean permanent;

    RetireCause(String label, boolean permanent) {
        this.label = label;
        this.permanent = permanent;
    }

    public String label() {
        return label;
    }

    public boolean permanent() {
        return permanent;
    }
}

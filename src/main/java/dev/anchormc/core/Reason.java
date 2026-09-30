package dev.anchormc.core;

/** 회수 사유 하나: 원인, 자세한 내용(좌표·블록 종류·청크 로드 여부), 그때의 틱. */
public record Reason(RetireCause cause, String detail, long tick) {
    public String text() {
        return detail == null || detail.isEmpty() ? cause.label() : cause.label() + " — " + detail;
    }
}

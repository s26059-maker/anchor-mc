package dev.anchormc.core;

/**
 * 안전 불변식 1의 단일 판정 함수. 미끼를 보내기 전과 살아 있는 동안 주기적으로 같은 함수로 검사한다.
 */
public final class DecoyGuard {
    private DecoyGuard() {
    }

    /** 그 좌표가 돌 속이고, 여섯 면이 전부 안정적인 불투명 고체면 true. */
    public static boolean sealed(BlockView view, Pos p) {
        if (view.hostAt(p.x(), p.y(), p.z()) == null) {
            return false;
        }
        for (int[] f : Pos.FACES) {
            if (!view.isStableOpaque(p.x() + f[0], p.y() + f[1], p.z() + f[2])) {
                return false;
            }
        }
        return true;
    }
}

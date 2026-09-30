package dev.anchormc.core;

import java.util.List;
import java.util.Locale;

/**
 * 안전 불변식 1의 단일 판정 함수. 미끼를 보내기 전과 살아 있는 동안 주기적으로 같은 함수로 검사한다.
 * 결과는 셋이다: 봉인됨 / 뚫림 / 모름(이웃 청크가 로드돼 있지 않아 블록을 알 수 없음).
 * 새로 보내는 데는 "봉인됨"만 쓴다(모름은 안 된다). 이미 보낸 것을 거둘지는 "뚫림"일 때만 정한다(모름은 판단 보류).
 */
public final class DecoyGuard {
    public enum State { SEALED, BREACHED, UNKNOWN }

    /** 검사 결과. voxel은 검사한 좌표, blocker는 그 결과를 만든 좌표(봉인됨이면 null). */
    public record Seal(State state, Pos voxel, Pos blocker) {
        public boolean sealed() {
            return state == State.SEALED;
        }
    }

    private static final Seal OK = new Seal(State.SEALED, null, null);

    private DecoyGuard() {
    }

    /** 그 좌표가 돌 속이고, 여섯 면이 전부 안정적인 불투명 고체면 true. */
    public static boolean sealed(BlockView view, Pos p) {
        return scan(view, p.x(), p.y(), p.z()) == 0;
    }

    /** 코드: 0 봉인됨, 1 자기 자리가 돌이 아님, 2 자기 청크를 모름, 10+i 면 i가 뚫림, 20+i 면 i의 청크를 모름. 뚫림이 모름보다 먼저다. */
    private static int scan(BlockView view, int x, int y, int z) {
        if (!view.chunkLoaded(Math.floorDiv(x, 16), Math.floorDiv(z, 16))) {
            return 2;
        }
        if (view.hostAt(x, y, z) == null) {
            return 1;
        }
        int unknown = 0;
        for (int i = 0; i < Pos.FACES.length; i++) {
            int[] f = Pos.FACES[i];
            int nx = x + f[0], ny = y + f[1], nz = z + f[2];
            if (!view.isStableOpaque(nx, ny, nz)) {
                if (view.chunkLoaded(Math.floorDiv(nx, 16), Math.floorDiv(nz, 16))) {
                    return 10 + i;
                }
                if (unknown == 0) {
                    unknown = 20 + i;
                }
            }
        }
        return unknown;
    }

    private static Seal decode(int code, Pos p) {
        if (code == 0) {
            return OK;
        }
        if (code == 1) {
            return new Seal(State.BREACHED, p, p);
        }
        if (code == 2) {
            return new Seal(State.UNKNOWN, p, p);
        }
        int[] f = Pos.FACES[code % 10];
        return new Seal(code >= 20 ? State.UNKNOWN : State.BREACHED, p, p.offset(f[0], f[1], f[2]));
    }

    public static Seal check(BlockView view, Pos p) {
        return decode(scan(view, p.x(), p.y(), p.z()), p);
    }

    /** 뭉치 전체의 결과: 하나라도 뚫렸으면 그 뚫림, 아니면 모름이 하나라도 있으면 그 모름, 아니면 봉인됨. */
    public static Seal checkAll(BlockView view, List<Voxel> voxels) {
        Seal unknown = null;
        for (Voxel v : voxels) {
            Seal s = check(view, v.pos());
            if (s.state() == State.BREACHED) {
                return s;
            }
            if (s.state() == State.UNKNOWN && unknown == null) {
                unknown = s;
            }
        }
        return unknown == null ? OK : unknown;
    }

    /** 사람이 읽는 설명: 어느 자리의 어느 좌표가, 무슨 블록이고, 그 청크가 로드돼 있었는지. */
    public static String explain(BlockView view, Seal s) {
        if (s.sealed()) {
            return "";
        }
        Pos b = s.blocker();
        String what = b.equals(s.voxel()) ? "자기 자리" : "이웃";
        String verdict = s.state() == State.UNKNOWN ? "청크 로드 안 됨(판단 보류)" : "뚫림";
        return String.format(Locale.ROOT, "자리 (%d, %d, %d)의 %s (%d, %d, %d) %s, 청크(%d, %d) 청크 로드=%b, %s",
                s.voxel().x(), s.voxel().y(), s.voxel().z(), what, b.x(), b.y(), b.z(), view.describe(b.x(), b.y(), b.z()),
                b.chunkX(), b.chunkZ(), view.chunkLoaded(b.chunkX(), b.chunkZ()), verdict);
    }
}

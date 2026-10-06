package dev.anchormc.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 실서버 자동 테스트(/anchor simtest)의 경로 계획. Bukkit을 모르는 순수 계산이라 단위 테스트가 된다.
 * 한 걸음은 항상 정확히 1블록이다(큰 점프는 반응 구간을 건너뛰는 버그를 만든다). 위약 자리는 후보에서 아예 뺀다.
 */
public final class SimRoute {
    private SimRoute() {
    }

    /** 이보다 먼 미끼는 고르지 않는다(걸음 수 상한). */
    public static final int MAX_STEPS = 64;

    /**
     * 한 동작. moveTo가 있으면 dig를 부순 뒤 그 발 위치(블록 좌표)로 옮긴다. mine이면 옮기지 않고 dig(미끼 자리)만 부순다.
     */
    public record Action(Pos moveTo, List<Pos> dig, boolean mine) {
    }

    /** 이 플레이어의 자리 목록에서 [미끼]이고 활성인 것만, 같은 월드에서 가까운 순으로 n개. 위약은 절대 고르지 않는다. */
    public static List<DecoyEngine.SiteDebug> pickDecoys(List<DecoyEngine.SiteDebug> all, Pos feet, int n) {
        List<DecoyEngine.SiteDebug> c = new ArrayList<>();
        for (DecoyEngine.SiteDebug d : all) {
            if (d.kind() == SiteKind.DECOY && d.status().startsWith("활성") && d.pos().world().equals(feet.world())
                    && manhattan(d.pos(), feet) <= MAX_STEPS) {
                c.add(d);
            }
        }
        c.sort(Comparator.comparingDouble(d -> d.pos().distanceTo(feet.x() + 0.5, feet.y() + 0.5, feet.z() + 0.5)));
        return new ArrayList<>(c.subList(0, Math.min(Math.max(n, 0), c.size())));
    }

    /** 목표들을 주어진 순서로 차례로 찾아가는 동작 열. 앞 목표를 부순 자리에서 다음 목표로 이어 간다. */
    public static List<Action> xray(Pos feet, List<Pos> targets) {
        List<Action> out = new ArrayList<>();
        Pos cur = feet;
        for (Pos t : targets) {
            while (!reached(cur, t)) {
                cur = stepToward(cur, t);
                out.add(new Action(cur, List.of(cur, cur.offset(0, 1, 0)), false));
            }
            out.add(new Action(null, List.of(t), true));
        }
        return out;
    }

    /** 곧은 가지치기: (dx,dz)는 한 축의 단위 방향. 2칸 높이, 한 걸음 1블록. 미끼·위약 정보를 받지 않는다. */
    public static List<Action> straight(Pos feet, int dx, int dz, int blocks) {
        if (Math.abs(dx) + Math.abs(dz) != 1) {
            throw new IllegalArgumentException("방향은 가로 한 축의 단위 벡터여야 한다");
        }
        List<Action> out = new ArrayList<>();
        for (int i = 1; i <= blocks; i++) {
            Pos p = feet.offset(dx * i, 0, dz * i);
            out.add(new Action(p, List.of(p, p.offset(0, 1, 0)), false));
        }
        return out;
    }

    /** 발·머리 두 칸 중 어느 쪽이든 목표와 면으로 맞닿았거나 겹치면 닿은 것. */
    static boolean reached(Pos feet, Pos t) {
        return Math.min(manhattan(feet, t), manhattan(feet.offset(0, 1, 0), t)) <= 1;
    }

    /** 가장 먼 축으로 정확히 1블록. 위로 갈 때는 머리 칸 기준으로 거리를 잰다. */
    static Pos stepToward(Pos feet, Pos t) {
        int dx = t.x() - feet.x(), dz = t.z() - feet.z();
        int dy = t.y() - feet.y();
        if (dy > 0) {
            dy -= 1; // 머리가 한 칸 위
        }
        int ax = Math.abs(dx), ay = Math.abs(dy), az = Math.abs(dz);
        if (ax >= ay && ax >= az) {
            return feet.offset(Integer.signum(dx), 0, 0);
        }
        if (az >= ay) {
            return feet.offset(0, 0, Integer.signum(dz));
        }
        return feet.offset(0, Integer.signum(dy), 0);
    }

    static int manhattan(Pos a, Pos b) {
        return Math.abs(a.x() - b.x()) + Math.abs(a.y() - b.y()) + Math.abs(a.z() - b.z());
    }
}

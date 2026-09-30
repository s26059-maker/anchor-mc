package dev.anchormc.sim;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.PlayerState;
import dev.anchormc.core.Pos;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;

/** 채굴 에이전트. 시간은 틱(20틱=1초). 이동·채굴에 비용을 붙이고 1초마다 서버 엔진을 돌린다. */
abstract class Agent {
    static final int DIG_TICKS = 9;
    static final int MOVE_TICKS = 5;
    static final int[][] DIRS = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};

    final SimWorld world;
    final AnchorCore core;
    final UUID id;
    final String name;
    final RandomGenerator rng;

    int x, y, z;
    long tick;
    long engineTick;
    long nextEngineTick = 20;
    int oresMined;
    boolean confirmed;
    long confirmedTick = -1;
    double maxLog10E;

    /** 이 에이전트 화면에 떠 있는 가짜 광석과, 그것이 떴을 때 에이전트와의 거리. */
    final Set<Pos> shown = new HashSet<>();
    final Map<Pos, Double> popDistance = new HashMap<>();

    Agent(SimWorld world, AnchorCore core, UUID id, String name, RandomGenerator rng, int[] start) {
        this.world = world;
        this.core = core;
        this.id = id;
        this.name = name;
        this.rng = rng;
        this.x = start[0];
        this.y = start[1];
        this.z = start[2];
    }

    /** 한 칸 움직인다(막혀 있으면 캔다). 월드 밖이면 false. */
    boolean stepTo(int nx, int ny, int nz) {
        if (!world.in(nx, ny, nz)) {
            return false;
        }
        int cost = MOVE_TICKS;
        if (world.solid(nx, ny, nz)) {
            core.decoys.onPlayerBreak(id, SimWorld.pos(nx, ny, nz), tick); // 변경 전에 알린다
            if (world.get(nx, ny, nz) == SimWorld.ORE) {
                oresMined++;
            }
            world.set(nx, ny, nz, SimWorld.AIR);
            cost += DIG_TICKS;
        }
        x = nx;
        y = ny;
        z = nz;
        core.decoys.onMove(id, SimWorld.NAME, x + 0.5, y + SimWorld.Y0 + 0.5, z + 0.5, tick);
        advance(cost);
        return true;
    }

    void advance(int ticks) {
        tick += ticks;
        while (tick >= nextEngineTick && !confirmed) {
            engineTick = nextEngineTick;
            core.decoys.tick(new PlayerState(id, name, SimWorld.NAME, x + 0.5, y + SimWorld.Y0 + 0.5, z + 0.5, true), engineTick);
            core.decoys.expire(engineTick);
            core.decoys.verifyAll(engineTick);
            var v = core.evidence.viewOf(id);
            if (v != null) {
                maxLog10E = Math.max(maxLog10E, v.log10E());
            }
            nextEngineTick += 20;
        }
    }

    /** 다음 행동 하나. 아무것도 못 했으면 false(호출자가 시간을 흘린다). */
    abstract boolean step();

    /** 확정되거나 시간이 다 될 때까지. */
    void run(long capTicks) {
        while (!confirmed && tick < capTicks) {
            if (!step()) {
                advance(20);
            }
        }
        core.decoys.dropPlayer(id, engineTick, true);
    }

    double minutes(long ticks) {
        return ticks / 1200.0;
    }
}

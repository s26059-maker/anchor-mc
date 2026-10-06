package dev.anchormc.plugin;

import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Pos;
import dev.anchormc.core.SimRoute;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * /anchor simtest: 실서버에서 사람 대신 미끼로 파 들어가거나(xray) 곧게 가지치기(honest)를 해 보는 디버그 도구.
 * 판정 로직은 건드리지 않는다. 플레이어를 1블록씩, STEP_TICKS틱 간격으로 옮기고 지나는 블록은 서버가 부순다
 * (BlockBreakEvent가 그 플레이어 이름으로 불려 실제 채굴과 같은 경로를 탄다). 게임모드는 바꾸지 않는다.
 * 텔레포트는 PlayerMoveEvent를 부르지 않으므로 걸음마다 엔진의 이동 알림만 대신 불러 걷는 것과 같게 한다.
 */
final class SimTest {
    /** 한 걸음 간격(틱). 1블록씩이라 반응 구간(반경 안)을 건너뛰지 않는다. */
    static final long STEP_TICKS = 4;
    /** 끝난 뒤 요약까지 기다리는 틱: 1초 위치 판정이 한 번은 돌게 한다. */
    static final long REPORT_DELAY = 40;
    static final int MAX_COUNT = 50;
    static final int MAX_BLOCKS = 256;

    private final Plugin plugin;
    private final PlayerRegistry registry;
    private final DecoyEngine engine;
    private final Function<String, String> statusText;
    private final Map<UUID, Run> runs = new HashMap<>();

    SimTest(Plugin plugin, PlayerRegistry registry, DecoyEngine engine, Function<String, String> statusText) {
        this.plugin = plugin;
        this.registry = registry;
        this.engine = engine;
        this.statusText = statusText;
    }

    private static void say(CommandSender s, String m) {
        s.sendMessage(Component.text("[simtest] " + m));
    }

    private static Pos feetOf(Player p) {
        Location l = p.getLocation();
        return new Pos(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    /** 숫자 인자: 1..max가 아니면 null. */
    static Integer parseCount(String s, int max) {
        try {
            int n = Integer.parseInt(s);
            return n >= 1 && n <= max ? n : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 가로 방향을 한 축으로 맞춘다. yaw 0 = +z(남), 90 = -x(서). */
    static int[] cardinal(float yaw) {
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        return Math.abs(fx) > Math.abs(fz) ? new int[]{fx > 0 ? 1 : -1, 0} : new int[]{0, fz > 0 ? 1 : -1};
    }

    void command(CommandSender s, String[] args) {
        if (args.length < 4) {
            say(s, "/anchor simtest <플레이어> xray <개수> | honest <블록수>");
            return;
        }
        Player p = Bukkit.getPlayerExact(args[1]);
        if (p == null) {
            say(s, args[1] + ": 접속 중이 아니다");
            return;
        }
        String mode = args[2].toLowerCase(Locale.ROOT);
        if (!mode.equals("xray") && !mode.equals("honest")) {
            say(s, "모드는 xray 또는 honest");
            return;
        }
        boolean xray = mode.equals("xray");
        Integer n = parseCount(args[3], xray ? MAX_COUNT : MAX_BLOCKS);
        if (n == null) {
            say(s, "<" + (xray ? "개수" : "블록수") + ">는 1~" + (xray ? MAX_COUNT : MAX_BLOCKS) + " 정수");
            return;
        }
        if (runs.containsKey(p.getUniqueId())) {
            say(s, p.getName() + ": 이미 시험 중이다");
            return;
        }
        if (!registry.stateOf(p).eligible()) {
            say(s, p.getName() + ": 판정 대상이 아니다(게임모드는 서바이벌이어야 한다). 게임모드를 바꾸지는 않는다");
            return;
        }
        Pos feet = feetOf(p);
        List<SimRoute.Action> plan;
        String what;
        if (xray) {
            var picked = SimRoute.pickDecoys(engine.debugSites(p.getUniqueId()), feet, n);
            if (picked.isEmpty()) {
                say(s, "가까운(" + SimRoute.MAX_STEPS + "블록 이내) 활성 [미끼] 자리가 없다. /anchor debug로 확인");
                return;
            }
            plan = SimRoute.xray(feet, picked.stream().map(DecoyEngine.SiteDebug::pos).toList());
            what = "xray 미끼 " + picked.size() + "개(요청 " + n + ")";
        } else {
            int[] d = cardinal(p.getLocation().getYaw());
            plan = SimRoute.straight(feet, d[0], d[1], n);
            what = "honest 직진 " + n + "블록(방향 " + d[0] + "," + d[1] + ")";
        }
        say(s, p.getName() + ": " + what + ", " + plan.size() + "동작, " + STEP_TICKS + "틱 간격으로 시작");
        Run run = new Run(s, p.getUniqueId(), p.getName(), what, plan);
        runs.put(p.getUniqueId(), run);
        run.task = Bukkit.getScheduler().runTaskTimer(plugin, run, 1L, STEP_TICKS);
    }

    void cancelAll() {
        for (Run r : new ArrayList<>(runs.values())) {
            r.finish("서버 정리로 중단", false);
        }
    }

    private final class Run implements Runnable {
        final CommandSender sender;
        final UUID id;
        final String name;
        final String what;
        final List<SimRoute.Action> plan;
        BukkitTask task;
        int next;
        int steps;
        int broken;
        int targets;
        boolean done;

        Run(CommandSender sender, UUID id, String name, String what, List<SimRoute.Action> plan) {
            this.sender = sender;
            this.id = id;
            this.name = name;
            this.what = what;
            this.plan = plan;
        }

        @Override
        public void run() {
            Player p = Bukkit.getPlayer(id);
            if (p == null || !p.isOnline()) {
                finish("접속 종료로 중단", true);
                return;
            }
            if (!registry.stateOf(p).eligible()) {
                finish("판정 대상에서 빠져 중단(게임모드 변경·사망 등)", true);
                return;
            }
            if (next >= plan.size()) {
                finish("완료", true);
                return;
            }
            SimRoute.Action a = plan.get(next);
            String err = execute(p, a);
            if (err != null) {
                finish("중단: " + err, true);
                return;
            }
            next++;
        }

        /** 한 동작. 문제가 있으면 사유, 없으면 null. */
        private String execute(Player p, SimRoute.Action a) {
            World w = p.getWorld();
            if (!a.dig().isEmpty() && !a.dig().get(0).world().equals(w.getName())) {
                return "월드가 바뀌었다";
            }
            for (Pos d : a.dig()) {
                Block b = w.getBlockAt(d.x(), d.y(), d.z());
                if (b.getType().isAir()) {
                    continue;
                }
                if (b.isLiquid() || b.getType().getHardness() < 0) {
                    return "부술 수 없는 블록(" + b.getType().getKey().getKey() + ") " + d.x() + "," + d.y() + "," + d.z();
                }
                if (!p.breakBlock(b) && !b.getType().isAir()) {
                    return "블록 파괴가 취소됐다 " + d.x() + "," + d.y() + "," + d.z();
                }
                broken++;
                if (a.mine()) {
                    targets++;
                }
            }
            if (a.moveTo() != null) {
                Location cur = p.getLocation();
                Location to = new Location(w, a.moveTo().x() + 0.5, a.moveTo().y(), a.moveTo().z() + 0.5, cur.getYaw(), cur.getPitch());
                p.teleport(to);
                p.setFallDistance(0f);
                p.setVelocity(new Vector(0, 0, 0));
                engine.onMove(id, w.getName(), to.getX(), to.getY(), to.getZ(), Bukkit.getCurrentTick());
                steps++;
            }
            return null;
        }

        void finish(String why, boolean report) {
            if (done) {
                return;
            }
            done = true;
            if (task != null) {
                task.cancel();
            }
            runs.remove(id);
            say(sender, name + ": " + what + " " + why + " | 이동 " + steps + "걸음, 부순 블록 " + broken + "개, 미끼 자리 채굴 " + targets + "회. "
                    + (report ? (REPORT_DELAY / 20) + "초 뒤 /anchor status 요약" : ""));
            if (!report) {
                return;
            }
            Bukkit.getScheduler().runTaskLater(plugin, () -> sender.sendMessage(Component.text(statusText.apply(name))), REPORT_DELAY);
        }
    }
}

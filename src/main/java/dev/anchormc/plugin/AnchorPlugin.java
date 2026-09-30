package dev.anchormc.plugin;

import dev.anchormc.AnchorCore;
import dev.anchormc.core.BlockView;
import dev.anchormc.core.Params;
import dev.anchormc.core.PlayerState;
import dev.anchormc.evidence.AsyncStore;
import dev.anchormc.evidence.EvidenceEngine;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.SqliteStore;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.random.RandomGenerator;

public final class AnchorPlugin extends JavaPlugin {
    private AnchorCore core;
    private AsyncStore store;
    private BukkitTask spawnTask;
    private BukkitTask verifyTask;
    private final Map<String, BlockView> views = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Params params;
        EvidenceParams ep;
        try {
            params = readParams(getConfig());
            ep = readEvidenceParams(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml 오류: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        applyShadowMode(getConfig());

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("데이터 폴더를 만들 수 없다");
        }
        store = new AsyncStore(new SqliteStore(getDataFolder().toPath().resolve(getConfig().getString("storage.file", "anchor.db"))),
                t -> getLogger().severe("증거 저장 실패: " + t));
        core = new AnchorCore(params, ep, this::viewOf, new BukkitDisplay(), store,
                RandomGenerator.getDefault(), System::currentTimeMillis, this::onConfirmed);

        getServer().getPluginManager().registerEvents(new AnchorListener(core.decoys), this);
        // 1초마다 배치·만료, 0.5초마다 노출 재검사(이벤트 없이 바뀐 경우 대비).
        spawnTask = getServer().getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        verifyTask = getServer().getScheduler().runTaskTimer(this, () -> core.decoys.verifyAll(now()), 10L, 10L);
        getLogger().info("anchor-mc 켜짐(섀도 모드: 처벌하지 않고 기록·알림만)");
    }

    @Override
    public void onDisable() {
        if (spawnTask != null) {
            spawnTask.cancel();
        }
        if (verifyTask != null) {
            verifyTask.cancel();
        }
        if (core != null) {
            core.decoys.shutdown(now()); // 모든 미끼를 진짜 블록으로 되돌린다
        }
        if (store != null) {
            store.close();
        }
    }

    private static long now() {
        return Bukkit.getCurrentTick();
    }

    private BlockView viewOf(String world) {
        World w = Bukkit.getWorld(world);
        return w == null ? null : views.computeIfAbsent(world, k -> new BukkitBlockView(w));
    }

    static PlayerState stateOf(Player p) {
        GameMode gm = p.getGameMode();
        boolean eligible = (gm == GameMode.SURVIVAL || gm == GameMode.ADVENTURE) && p.isOnline() && !p.isDead();
        var l = p.getLocation();
        return new PlayerState(p.getUniqueId(), p.getName(), l.getWorld().getName(), l.getX(), l.getY() + 1.0, l.getZ(), eligible);
    }

    /** 1초마다 위치 판정과 만료. 새 자리는 여기서 만들지 않는다(청크 전송 이벤트에서만). */
    private void secondTick() {
        long tick = now();
        for (Player p : Bukkit.getOnlinePlayers()) {
            core.decoys.tick(stateOf(p), tick);
        }
        core.decoys.expire(tick);
    }

    private void onConfirmed(EvidenceEngine.Confirmation c) {
        String msg = String.format(Locale.ROOT,
                "[anchor-mc] 확정(섀도 모드, 처벌 없음): %s 미끼 %d/%d 위약 %d/%d log10E=%.1f p0=%.3f",
                c.account().name, c.account().decoyHits, c.account().decoyN,
                c.account().placeboHits, c.account().placeboN, c.account().log10E(), c.p0());
        getLogger().warning(msg);
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("anchor.alert")) {
                p.sendMessage(Component.text(msg));
            }
        }
    }

    // ---- 설정 ----

    static Params readParams(FileConfiguration c) {
        Params d = Params.defaults();
        return new Params(
                c.getDouble("reaction-radius", d.reactionRadius()),
                Math.round(c.getDouble("window-seconds", d.windowTicks() / 20.0) * 20),
                c.getDouble("give-up-distance", d.giveUpDistance()),
                c.getDouble("retract-distance", d.retractDistance()),
                c.getInt("sites.y-min", d.yMin()),
                c.getInt("sites.y-max", d.yMax()),
                c.getDouble("sites.min-distance", d.minDistance()),
                c.getInt("sites.max-active-pairs", d.maxActivePairs()),
                Math.round(c.getDouble("sites.cooldown-seconds", d.cooldownTicks() / 20.0) * 20),
                c.getInt("sites.max-attempts", d.maxAttempts()),
                c.getDouble("sites.pairs-per-chunk", d.pairsPerChunk()),
                c.getInt("sites.profile-samples", d.profileSamples()));
    }

    static EvidenceParams readEvidenceParams(FileConfiguration c) {
        EvidenceParams d = EvidenceParams.defaults();
        EvidenceParams.Rule rule;
        try {
            rule = EvidenceParams.Rule.valueOf(c.getString("confirm-rule", d.rule().name()).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("confirm-rule은 MIXTURE, PAIRED, BOTH 중 하나");
        }
        return new EvidenceParams(
                c.getDouble("alpha", d.alpha()),
                c.getDouble("p0-multiplier", d.p0Multiplier()),
                c.getInt("min-placebo-samples", d.minPlaceboSamples()),
                c.getDouble("paired-alpha", d.pairedAlpha()),
                rule);
    }

    /** 1차 MVP에는 처벌 동작이 없다. false로 둬도 섀도 모드로 동작한다고 알린다. */
    private void applyShadowMode(FileConfiguration c) {
        if (!c.getBoolean("shadow-mode", true)) {
            getLogger().warning("shadow-mode: false여도 이 버전에는 처벌 동작이 없어 로그와 관리자 알림만 한다");
        }
    }

    // ---- 명령어 ----

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anchor.admin")) {
            sender.sendMessage(Component.text("권한이 없다(anchor.admin)"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("/anchor status <플레이어> | stats | reload"));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender, args);
            case "stats" -> stats(sender);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(Component.text("/anchor status <플레이어> | stats | reload"));
        }
        return true;
    }

    private void status(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Component.text("/anchor status <플레이어>"));
            return;
        }
        EvidenceEngine.View v = core.evidence.view(args[1]);
        if (v == null) {
            s.sendMessage(Component.text(args[1] + ": 기록 없음"));
            return;
        }
        s.sendMessage(Component.text(String.format(Locale.ROOT,
                "%s | 미끼 %d/%d (%s) | 위약 %d/%d (%s) | log10E=%.2f (문턱 %.1f) | 쌍: 미끼만 %d 위약만 %d 둘다 %d 없음 %d log10E쌍=%.2f | %s",
                v.name(), v.decoyHits(), v.decoyN(), pct(v.decoyRate()),
                v.placeboHits(), v.placeboN(), pct(v.placeboRate()),
                v.log10E(), -Math.log10(getConfig().getDouble("alpha", 1e-9)),
                v.pairDecoyOnly(), v.pairPlaceboOnly(), v.pairBoth(), v.pairNeither(), v.log10EPaired(),
                v.confirmed() ? "확정(섀도)" : "미확정")));
    }

    private void stats(CommandSender s) {
        EvidenceEngine.Stats st = core.evidence.stats();
        s.sendMessage(Component.text(String.format(Locale.ROOT,
                "위약 %d/%d 반응률 %s → 현재 p0 = %.4f (배수 %.1f) | 확정 계정 %d | 판정 전 회수 %d",
                st.placeboHits(), st.placeboN(), pct(st.placeboRate()), st.p0(),
                getConfig().getDouble("p0-multiplier", 2.0), st.confirmedAccounts(), core.voided())));
    }

    private void reload(CommandSender s) {
        reloadConfig();
        try {
            core.decoys.setParams(readParams(getConfig()));
            core.evidence.setParams(readEvidenceParams(getConfig()));
            applyShadowMode(getConfig());
            s.sendMessage(Component.text("설정을 다시 읽었다"));
        } catch (IllegalArgumentException e) {
            s.sendMessage(Component.text("config.yml 오류, 이전 설정 유지: " + e.getMessage()));
        }
    }

    private static String pct(double r) {
        return Double.isNaN(r) ? "-" : String.format(Locale.ROOT, "%.1f%%", r * 100);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String o : List.of("status", "stats", "reload")) {
                if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("status")) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        }
        return out;
    }
}

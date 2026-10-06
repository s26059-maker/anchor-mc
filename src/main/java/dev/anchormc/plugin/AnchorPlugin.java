package dev.anchormc.plugin;

import com.github.retrooper.packetevents.PacketEvents;
import dev.anchormc.AnchorCore;
import dev.anchormc.core.BlockView;
import dev.anchormc.core.DecoyEngine;
import dev.anchormc.core.Params;
import dev.anchormc.core.RetireCause;
import dev.anchormc.core.SiteKind;
import dev.anchormc.evidence.AsyncStore;
import dev.anchormc.evidence.EvidenceEngine;
import dev.anchormc.evidence.EvidenceParams;
import dev.anchormc.evidence.SqliteStore;
import dev.anchormc.evidence.StatusText;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class AnchorPlugin extends JavaPlugin {
    private AnchorCore core;
    private AsyncStore store;
    private BukkitTask spawnTask;
    private BukkitTask verifyTask;
    private PacketDecoyListener packetListener;
    private SimTest simTest;
    private final PlayerRegistry registry = new PlayerRegistry();
    private final Map<String, BlockView> views = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        if (getServer().getPluginManager().getPlugin("packetevents") == null) {
            getLogger().severe("PacketEvents 플러그인(2.14.0 이상)이 필요하다. 설치한 뒤 다시 켜라.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        Params params;
        EvidenceParams ep;
        byte[] secret;
        try {
            params = readParams(getConfig());
            ep = readEvidenceParams(getConfig());
            secret = secret();
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml 오류: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        applyShadowMode(getConfig());
        applyDebugSpectator(getConfig());
        auditConfig();

        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("데이터 폴더를 만들 수 없다");
        }
        store = new AsyncStore(new SqliteStore(getDataFolder().toPath().resolve(getConfig().getString("storage.file", "anchor.db"))),
                t -> getLogger().severe("증거 저장 실패: " + t));
        core = new AnchorCore(params, ep, this::viewOf, new BukkitDisplay(), store,
                secret, System::currentTimeMillis, this::onConfirmed);
        java.util.Arrays.fill(secret, (byte) 0);
        simTest = new SimTest(this, registry, core.decoys, this::statusText);

        getServer().getPluginManager().registerEvents(new AnchorListener(core.decoys, registry, this), this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            registry.update(p);
        }
        packetListener = new PacketDecoyListener(this, core.decoys, buildStateTable(), registry);
        PacketEvents.getAPI().getEventManager().registerListener(packetListener);
        // 1초마다 위치 판정·만료·플레이어 표 갱신, 0.5초마다 노출 재검사(이벤트 없이 바뀐 경우 대비).
        spawnTask = getServer().getScheduler().runTaskTimer(this, this::secondTick, 20L, 20L);
        verifyTask = getServer().getScheduler().runTaskTimer(this, () -> core.decoys.verifyAll(now()), 10L, 10L);
        getLogger().info("anchor-mc 켜짐(섀도 모드: 처벌하지 않고 기록·알림만, 미끼는 청크 데이터 패킷에 직접 삽입)");
    }

    @Override
    public void onDisable() {
        if (packetListener != null && PacketEvents.getAPI() != null) {
            PacketEvents.getAPI().getEventManager().unregisterListener(packetListener);
        }
        if (simTest != null) {
            simTest.cancelAll();
        }
        if (spawnTask != null) {
            spawnTask.cancel();
        }
        if (verifyTask != null) {
            verifyTask.cancel();
        }
        if (core != null) {
            core.decoys.shutdown(now()); // 모든 미끼를 진짜 블록으로 되돌린다
            core.releaseAll(); // 창 끝을 기다리던 판정도 저장소에 넣는다
        }
        registry.clear();
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

    /**
     * 모든 블록 재질을 미리(메인 스레드) 분류해 두고 패킷 스레드는 이름으로 조회만 한다.
     * 분류 규칙은 BukkitBlockView와 같은 것(불투명·안정)이라 패킷 시야와 실제 월드 시야의 판정이 일치한다.
     */
    private PacketStateTable buildStateTable() {
        Map<String, Integer> flags = new HashMap<>();
        for (Material m : Material.values()) {
            if (m.isLegacy() || !m.isBlock()) {
                continue;
            }
            int f = 0;
            if (m.isOccluding() && !m.hasGravity() && !BukkitBlockView.UNSTABLE.contains(m)) {
                f |= PacketStateTable.OPAQUE;
            }
            if (m == Material.STONE) {
                f |= PacketStateTable.STONE;
            } else if (m == Material.DEEPSLATE) {
                f |= PacketStateTable.DEEPSLATE;
            } else if (m == Material.DIAMOND_ORE || m == Material.DEEPSLATE_DIAMOND_ORE) {
                f |= PacketStateTable.DIAMOND;
            }
            flags.put(m.getKey().getKey(), f);
        }
        Map<String, Integer> frozen = Map.copyOf(flags);
        var version = PacketEvents.getAPI().getServerManager().getVersion().toClientVersion();
        return new PacketStateTable(version, name -> frozen.getOrDefault(name, 0));
    }

    /**
     * 서버 비밀 시드. 비어 있으면 새로 만들어 config.yml에 저장한다. 어떤 로그·메시지에도 값을 쓰지 않는다.
     * 16진수 문자열(32자 이상)이면 그 바이트, 아니면 문자열 자체(16자 이상)를 바이트로 쓴다.
     */
    private byte[] secret() {
        String v = getConfig().getString("secret-seed", "");
        if (v == null || v.isBlank()) {
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            v = HexFormat.of().formatHex(fresh);
            getConfig().set("secret-seed", v);
            saveConfig();
            java.util.Arrays.fill(fresh, (byte) 0);
            getLogger().warning("secret-seed가 비어 있어 새로 만들어 config.yml에 저장했다(값은 출력하지 않는다). 이 값을 바꾸면 미끼 자리가 전부 바뀐다.");
        }
        v = v.strip();
        if (v.length() >= 32 && v.length() % 2 == 0 && v.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return HexFormat.of().parseHex(v);
        }
        byte[] raw = v.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 16) {
            throw new IllegalArgumentException("secret-seed는 16자 이상이어야 한다");
        }
        return raw;
    }

    /** 1초마다 위치 판정과 만료, 패킷 스레드가 읽는 플레이어 표 갱신. 새 자리는 여기서 만들지 않는다(청크 패킷에서만). */
    private void secondTick() {
        long tick = now();
        for (Player p : Bukkit.getOnlinePlayers()) {
            registry.update(p);
            core.decoys.tick(registry.stateOf(p), tick);
        }
        core.expire(tick);
    }

    private void onConfirmed(EvidenceEngine.Confirmation c) {
        String msg = String.format(Locale.ROOT,
                "[anchor-mc] 확정(섀도 모드, 처벌 없음): %s 미끼 %d/%d 위약 %d/%d 먼저 반응 log10E=%.1f (혼합 log10E=%.1f 참고용) p0=%.3f",
                c.account().name, c.account().decoyHits, c.account().decoyN,
                c.account().placeboHits, c.account().placeboN, c.account().log10EFirst(), c.account().log10E(), c.p0());
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

    private static final String USAGE = "/anchor status <플레이어> | stats | debug <플레이어> | simtest <플레이어> xray <개수>|honest <블록수>|honest-branch <본갱도 블록수> | reset <플레이어> | reload";

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("anchor.admin")) {
            sender.sendMessage(Component.text("권한이 없다(anchor.admin)"));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text(USAGE));
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "status" -> status(sender, args);
            case "stats" -> stats(sender);
            case "debug" -> debug(sender, args);
            case "simtest" -> simTest.command(sender, args);
            case "reset" -> reset(sender, args);
            case "reload" -> reload(sender);
            default -> sender.sendMessage(Component.text(USAGE));
        }
        return true;
    }

    private void status(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Component.text("/anchor status <플레이어>"));
            return;
        }
        s.sendMessage(Component.text(statusText(args[1])));
    }

    private String statusText(String name) {
        EvidenceEngine.View v = core.evidence.view(name);
        if (v == null) {
            return name + ": 기록 없음";
        }
        return StatusText.format(v, readEvidenceParams(getConfig()));
    }

static boolean readAllowSpectator(FileConfiguration c) {
        return c.getBoolean("debug.allow-spectator", false);
    }

    /** 테스트용 옵션: 켜져 있으면 콘솔에 크게 경고한다(실운영에 켜 둔 채 두지 않게). 시작과 reload 때마다 부른다. */
    private void applyDebugSpectator(FileConfiguration c) {
        boolean on = readAllowSpectator(c);
        registry.setAllowSpectator(on);
        if (on) {
            for (String line : SPECTATOR_WARNING) {
                getLogger().warning(line);
            }
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            registry.update(p); // 다음 1초 틱이 자격 변화(관전자 복구·거둠)를 반영한다
        }
    }

    static final String[] SPECTATOR_WARNING = {
            "################################################################",
            "#  경고: debug.allow-spectator: true  (테스트 전용 옵션)         #",
            "#  관전자 모드 플레이어도 미끼를 받고, 그 반응은 증거에서 뺀다.  #",
            "#  실운영에서는 반드시 false로 되돌려라.                         #",
            "################################################################"};

    private void stats(CommandSender s) {
        if (registry.allowSpectator()) {
            s.sendMessage(Component.text("디버그: 관전자 허용 중 (debug.allow-spectator: true, 실운영에서는 꺼라)"));
        }
        EvidenceEngine.Stats st = core.evidence.stats();
        s.sendMessage(Component.text(String.format(Locale.ROOT,
                "위약 %d/%d 반응률 %s → 현재 p0 = %.4f (배수 %.1f) | 확정 계정 %d | 판정 전 회수 %d | 이번 기동 이후 청크 패킷에 미끼 삽입 %d회(실패 %d회, 재시작하면 0부터) | 자격 회복으로 다시 보인 쌍 %d | 광맥 표본 %d개(%s)",
                st.placeboHits(), st.placeboN(), pct(st.placeboRate()), st.p0(),
                getConfig().getDouble("p0-multiplier", 2.0), st.confirmedAccounts(), core.voided(),
                packetListener.patchedChunks(), packetListener.failures(), core.decoys.restoredPairs(),
                core.decoys.profile().bankSize(), core.decoys.profile().usingBank() ? "표본 사용" : "바닐라 기본값 사용")));
        s.sendMessage(Component.text(retireSummary()));
    }

    /** 사유별 회수 횟수(쌍 단위)와 지금 판단 보류 중인 활성 자리 수. 0인 사유는 뺀다. */
    private String retireSummary() {
        StringBuilder b = new StringBuilder("회수 사유별(쌍 단위): ");
        int shown = 0;
        for (var e : core.decoys.retireCounts().entrySet()) {
            if (e.getValue() > 0) {
                RetireCause c = e.getKey();
                b.append(shown++ > 0 ? " | " : "").append(c.name()).append('(').append(c.label()).append(") ").append(e.getValue())
                        .append(c.permanent() ? "" : " [계획 유지]");
            }
        }
        if (shown == 0) {
            b.append("아직 없음");
        }
        return b.append(" || 판단 보류 중인 활성 자리 ").append(core.decoys.heldSites()).toString();
    }

    /**
     * 그 플레이어에게 계획된 미끼·위약 좌표와 상태. 실서버에서 미끼가 실제로 나갔는지 확인하는 용도다.
     * 좌표가 그대로 나오므로 관리자 전용이고 시드는 절대 나오지 않는다.
     */
    private void debug(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Component.text("/anchor debug <플레이어>"));
            return;
        }
        Player t = Bukkit.getPlayerExact(args[1]);
        if (t == null) {
            s.sendMessage(Component.text(args[1] + ": 접속 중이 아니다"));
            return;
        }
        List<DecoyEngine.SiteDebug> all = core.decoys.debugSites(t.getUniqueId());
        int active = 0, done = 0, retired = 0, waiting = 0, decoys = 0;
        for (DecoyEngine.SiteDebug d : all) {
            if (d.kind() == SiteKind.DECOY) {
                decoys++;
            }
            if (d.status().startsWith("활성")) {
                active++;
            } else if (d.status().startsWith("판정 완료")) {
                done++;
            } else if (d.status().startsWith("회수됨")) {
                retired++;
            } else {
                waiting++;
            }
        }
        s.sendMessage(Component.text(String.format(Locale.ROOT,
                "%s: 계획된 자리 %d개(미끼 %d, 위약 %d) | 활성 %d, 판정 완료 %d, 회수됨 %d, 대기(청크 밖) %d | 이 플레이어 청크 패킷에 삽입한 총 횟수는 /anchor stats",
                t.getName(), all.size(), decoys, all.size() - decoys, active, done, retired, waiting)));
        Location at = t.getLocation();
        List<DecoyEngine.SiteDebug> near = new ArrayList<>(all);
        near.sort(Comparator.comparingDouble(d -> d.pos().world().equals(at.getWorld().getName())
                ? d.pos().distanceTo(at.getX(), at.getY(), at.getZ()) : Double.MAX_VALUE));
        s.sendMessage(Component.text(retireSummary()));
        int shown = 0;
        for (DecoyEngine.SiteDebug d : near) {
            if (shown++ >= 40) {
                s.sendMessage(Component.text("... 가까운 40개만 보였다(전체 " + all.size() + "개)"));
                break;
            }
            double dist = d.pos().world().equals(at.getWorld().getName())
                    ? d.pos().distanceTo(at.getX(), at.getY(), at.getZ()) : Double.NaN;
            s.sendMessage(Component.text(String.format(Locale.ROOT, "[%s] %s (%d, %d, %d) %d블록 · 거리 %s | %s",
                    d.kind() == SiteKind.DECOY ? "미끼" : "위약", d.pos().world(), d.pos().x(), d.pos().y(), d.pos().z(),
                    d.blocks(), Double.isNaN(dist) ? "-" : String.format(Locale.ROOT, "%.1f", dist), d.status())));
        }
    }

    /**
     * 그 플레이어의 판정 기록(SQLite 포함)·누적 e-value·반응 기록을 전부 지운다. 확인 없이 바로 실행하고 콘솔에 남긴다.
     * 미끼·위약 자리는 시드가 같아 그대로다: 화면의 미끼는 되돌리고 계획을 비우며, 청크를 다시 받으면 같은 좌표가 새 상태로 돌아온다.
     */
    private void reset(CommandSender s, String[] args) {
        if (args.length < 2) {
            s.sendMessage(Component.text("/anchor reset <플레이어>"));
            return;
        }
        Player online = Bukkit.getPlayerExact(args[1]);
        UUID id = online != null ? online.getUniqueId() : core.evidence.idOf(args[1]);
        if (id == null) {
            s.sendMessage(Component.text(args[1] + ": 기록 없음(접속 중도 아니고 저장된 기록도 없다)"));
            return;
        }
        String name = online != null ? online.getName() : args[1];
        EvidenceEngine.View old = core.resetPlayer(id, now());
        String was = old == null ? "지울 누적 기록 없음" : String.format(Locale.ROOT,
                "지운 기록: 미끼 %d/%d 위약 %d/%d log10E=%.2f%s", old.decoyHits(), old.decoyN(), old.placeboHits(), old.placeboN(),
                old.log10E(), old.confirmed() ? " 확정됨" : "");
        getLogger().warning(String.format(Locale.ROOT, "[anchor-mc] /anchor reset: %s가 %s(%s)의 판정 기록·e-value·반응 기록을 초기화했다. %s",
                s.getName(), name, id, was));
        s.sendMessage(Component.text(name + ": 초기화했다(" + was + "). 미끼·위약 자리는 시드 그대로이며 청크를 다시 받으면 새 상태로 돌아온다"));
    }

    /**
     * 서버의 config.yml(파일 그대로)을 이 버전의 기본 파일과 견줘 예전·없는·모르는 키와 기본값에서 벗어난 확정 규칙을 콘솔에 경고하고,
     * 지금 적용되는 확정 조건을 한 줄로 남는다. 경고 수를 돌려준다.
     */
    private int auditConfig() {
        try {
            java.io.File f = new java.io.File(getDataFolder(), "config.yml");
            org.bukkit.configuration.file.YamlConfiguration file = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(f);
            org.bukkit.configuration.file.YamlConfiguration def;
            try (java.io.Reader r = new java.io.InputStreamReader(getResource("config.yml"), StandardCharsets.UTF_8)) {
                def = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(r);
            }
            Map<String, Object> fm = leaves(file), dm = leaves(def);
            List<String> warns = ConfigAudit.audit(fm, dm);
            for (String w : warns) {
                getLogger().warning("config.yml: " + w);
            }
            getLogger().info("확정 조건: " + ConfigAudit.describeRule(fm, dm));
            return warns.size();
        } catch (Exception e) {
            getLogger().warning("config.yml 점검 실패(동작에는 영향 없음): " + e);
            return 0;
        }
    }

    private static Map<String, Object> leaves(org.bukkit.configuration.ConfigurationSection c) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        for (String k : c.getKeys(true)) {
            if (!c.isConfigurationSection(k)) {
                m.put(k, c.get(k));
            }
        }
        return m;
    }

    private void reload(CommandSender s) {
        reloadConfig();
        try {
            core.decoys.setParams(readParams(getConfig()));
            core.evidence.setParams(readEvidenceParams(getConfig()));
            applyShadowMode(getConfig());
            applyDebugSpectator(getConfig());
            int warns = auditConfig();
            s.sendMessage(Component.text("설정을 다시 읽었다(secret-seed 변경은 서버를 다시 켜야 적용된다)"
                    + (warns > 0 ? ". config.yml 경고 " + warns + "건은 콘솔을 봐라" : "")));
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
            for (String o : List.of("status", "stats", "debug", "simtest", "reset", "reload")) {
                if (o.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("status") || args[0].equalsIgnoreCase("debug") || args[0].equalsIgnoreCase("simtest") || args[0].equalsIgnoreCase("reset"))) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getName().toLowerCase(Locale.ROOT).startsWith(args[1].toLowerCase(Locale.ROOT))) {
                    out.add(p.getName());
                }
            }
        } else if (args.length == 3 && args[0].equalsIgnoreCase("simtest")) {
            for (String o : List.of("xray", "honest", "honest-branch")) {
                if (o.startsWith(args[2].toLowerCase(Locale.ROOT))) {
                    out.add(o);
                }
            }
        }
        return out;
    }
}

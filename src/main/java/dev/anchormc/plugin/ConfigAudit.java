package dev.anchormc.plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;

/**
 * 서버의 config.yml을 이 버전의 기본 파일과 견줘 경고할 것을 모은다(Bukkit과 무관해서 단위 테스트가 된다).
 * saveDefaultConfig는 파일이 이미 있으면 덮어쓰지 않아서, 업데이트 뒤에도 예전 키·기본값·주석이 그대로 남는다.
 * 입력은 잎 키(점으로 이은 경로) 대 값의 맵이다. 비밀 시드 값은 어떤 메시지에도 쓰지 않는다.
 */
final class ConfigAudit {
    private ConfigAudit() {
    }

    /** 사용자가 정하거나 플러그인이 만들어 넣는 값이라 "없음" 경고에서 뺀다. */
    private static final String SECRET = "secret-seed";

    static List<String> audit(Map<String, Object> file, Map<String, Object> defaults) {
        List<String> out = new ArrayList<>();
        int have = intOf(file.get("config-version"), 0), want = intOf(defaults.get("config-version"), 0);
        if (have < want) {
            out.add("예전 형식의 config.yml이다(config-version " + (have == 0 ? "없음" : have) + " < " + want
                    + "). 이 버전에서 키·기본값·주석이 바뀌었을 수 있다. 아래 경고를 보고 직접 고쳐라(파일을 지우고 새로 만들면 secret-seed가 바뀌어 모든 미끼 자리가 달라지니 값을 옮겨 둔다)");
        } else if (have > want) {
            out.add("config.yml이 이 플러그인보다 새 형식이다(config-version " + have + " > " + want + "). 플러그인 버전을 확인해라");
        }
        for (String k : new TreeSet<>(file.keySet())) {
            if (!defaults.containsKey(k)) {
                out.add("모르는 키 '" + k + "': 이 버전이 읽지 않는다(예전 버전에서 지워졌거나 오타). 지우거나 철자를 확인해라");
            }
        }
        for (String k : new TreeSet<>(defaults.keySet())) {
            if (!file.containsKey(k) && !k.equals(SECRET) && !k.equals("config-version")) {
                out.add("없는 키 '" + k + "': 기본값 " + defaults.get(k) + "를 쓴다. 바꾸려면 config.yml에 추가해라");
            }
        }
        Object rule = file.get("confirm-rule"), defRule = defaults.get("confirm-rule");
        if (rule != null && defRule != null && !rule.toString().equalsIgnoreCase(defRule.toString())) {
            out.add("confirm-rule이 기본값(" + defRule + ")과 다르다: " + rule + ". 지금 확정 조건은 [" + describeRule(file, defaults)
                    + "]이다. 시뮬 표(정직 오탐 0, 정확 보장)는 " + defRule + " 기준이다");
        }
        return out;
    }

    /** 이 설정에서 확정이 되려면 무엇이 필요한가(로그에 한 줄로). 값이 없으면 기본값을 쓴다. */
    static String describeRule(Map<String, Object> file, Map<String, Object> defaults) {
        String rule = String.valueOf(file.getOrDefault("confirm-rule", defaults.get("confirm-rule"))).toUpperCase(Locale.ROOT);
        double alpha = -Math.log10(doubleOf(file.getOrDefault("alpha", defaults.get("alpha")), 1e-9));
        double guard = -Math.log10(doubleOf(file.getOrDefault("paired-alpha", defaults.get("paired-alpha")), 1e-3));
        return switch (rule) {
            case "PAIRED" -> String.format(Locale.ROOT, "먼저 반응한 쪽 log10E ≥ %.1f", alpha);
            case "MIXTURE" -> String.format(Locale.ROOT, "혼합 log10E ≥ %.1f", alpha);
            case "BOTH" -> String.format(Locale.ROOT, "혼합 log10E ≥ %.1f 그리고 먼저 반응한 쪽 log10E ≥ %.1f(정직 오탐 상한은 이 문턱)", alpha, guard);
            default -> "알 수 없는 규칙 " + rule;
        };
    }

    private static int intOf(Object o, int dflt) {
        return o instanceof Number n ? n.intValue() : dflt;
    }

    private static double doubleOf(Object o, double dflt) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return o == null ? dflt : Double.parseDouble(o.toString());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}

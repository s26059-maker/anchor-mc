package dev.anchormc.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 서버에 남은 예전 config.yml을 시작할 때 잡아내는 점검. */
class ConfigAuditTest {
    /** 이 프로젝트 config.yml의 단순한 형식(공백 2칸 들여쓰기, key: value)만 푸는 평탄화. 값은 문자열이거나 숫자다. */
    static Map<String, Object> flatten(String yaml) {
        Map<String, Object> out = new LinkedHashMap<>();
        Deque<String> path = new ArrayDeque<>();
        Deque<Integer> indents = new ArrayDeque<>();
        for (String raw : yaml.split("\r?\n")) {
            String line = raw.stripTrailing();
            if (line.isBlank() || line.stripLeading().startsWith("#")) {
                continue;
            }
            int indent = line.length() - line.stripLeading().length();
            while (!indents.isEmpty() && indents.peek() >= indent) {
                indents.pop();
                path.pop();
            }
            String body = line.strip();
            int c = body.indexOf(':');
            String key = body.substring(0, c).strip(), val = body.substring(c + 1).strip();
            if (val.isEmpty()) {
                path.push(key);
                indents.push(indent);
                continue;
            }
            List<String> parts = new ArrayList<>(path);
            Collections.reverse(parts);
            parts.add(key);
            out.put(String.join(".", parts), parse(val));
        }
        return out;
    }

    private static Object parse(String v) {
        if (v.equals("\"\"")) {
            return "";
        }
        try {
            return Integer.parseInt(v);
        } catch (NumberFormatException e) {
            try {
                return Double.parseDouble(v);
            } catch (NumberFormatException e2) {
                return v;
            }
        }
    }

    private static Map<String, Object> current() throws Exception {
        return flatten(Files.readString(Path.of("src/main/resources/config.yml")));
    }

    /** 실서버(C:\mc-test)에서 본 예전 파일의 모양: config-version이 없고 confirm-rule이 BOTH. 지워진 키 하나를 일부러 넣었다. */
    private static final String OLD_SERVER = """
            shadow-mode: true
            reaction-radius: 3.0
            window-seconds: 240
            give-up-distance: 160.0
            retract-distance: 2.0
            alpha: 1.0E-9
            p0-multiplier: 2.0
            min-placebo-samples: 100
            confirm-rule: BOTH
            paired-alpha: 0.001
            secret-seed: 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff
            sites:
              y-min: -64
              y-max: 16
              pairs-per-chunk: 2.0
              profile-samples: 600
              max-attempts: 60
              old-removed-key: 7
            storage:
              file: anchor.db
            debug:
              allow-spectator: false
            """;

    @Test
    void theCurrentDefaultFileHasNoWarningsAgainstItself() throws Exception {
        assertEquals(List.of(), ConfigAudit.audit(current(), current()));
        assertTrue(current().get("config-version") instanceof Integer, "기본 파일에 config-version이 있어야 한다");
        assertEquals("PAIRED", current().get("confirm-rule"));
    }

    @Test
    void anOldServerFileIsReportedForVersionUnknownKeysAndTheNonDefaultRule() throws Exception {
        List<String> w = ConfigAudit.audit(flatten(OLD_SERVER), current());
        String all = String.join("\n", w);
        assertTrue(w.get(0).contains("예전 형식") && w.get(0).contains("config-version 없음"), all);
        assertTrue(all.contains("모르는 키 'sites.old-removed-key'"), all);
        String rule = w.stream().filter(s -> s.startsWith("confirm-rule이 기본값")).findFirst().orElseThrow();
        assertTrue(rule.contains("PAIRED") && rule.contains("BOTH"), rule);
        assertTrue(rule.contains("혼합 log10E ≥ 9.0 그리고 먼저 반응한 쪽 log10E ≥ 3.0"), "문턱 3.0의 출처가 보여야 한다: " + rule);
        assertFalse(all.contains("00112233445566778899"), "비밀 시드 값이 메시지에 나왔다");
    }

    @Test
    void missingKeysAreListedWithTheirDefaultButTheSecretSeedAndVersionAreNot() throws Exception {
        Map<String, Object> file = flatten(OLD_SERVER);
        file.remove("sites.max-attempts");
        file.remove("secret-seed");
        String all = String.join("\n", ConfigAudit.audit(file, current()));
        assertTrue(all.contains("없는 키 'sites.max-attempts': 기본값 60"), all);
        assertFalse(all.contains("없는 키 'secret-seed'"), all);
        assertFalse(all.contains("없는 키 'config-version'"), all);
    }

    @Test
    void aFileMatchingTheDefaultsExceptTheValuesYouMayTuneIsQuiet() throws Exception {
        Map<String, Object> file = current();
        file.put("sites.pairs-per-chunk", 4.0);
        file.put("secret-seed", "0123456789abcdef0123456789abcdef");
        file.put("window-seconds", 120);
        assertEquals(List.of(), ConfigAudit.audit(file, current()));
    }

    @Test
    void describeRuleSpellsOutWhatConfirmationNeeds() throws Exception {
        Map<String, Object> d = current();
        assertEquals("먼저 반응한 쪽 log10E ≥ 9.0", ConfigAudit.describeRule(d, d));
        Map<String, Object> m = new LinkedHashMap<>(d);
        m.put("confirm-rule", "mixture");
        assertEquals("혼합 log10E ≥ 9.0", ConfigAudit.describeRule(m, d));
        m.put("confirm-rule", "BOTH");
        m.put("paired-alpha", 0.001);
        assertTrue(ConfigAudit.describeRule(m, d).contains("먼저 반응한 쪽 log10E ≥ 3.0"));
        m.put("confirm-rule", "NOPE");
        assertTrue(ConfigAudit.describeRule(m, d).startsWith("알 수 없는 규칙"));
    }

    @Test
    void aNewerFileThanThePluginIsAlsoFlagged() throws Exception {
        Map<String, Object> file = current();
        file.put("config-version", 99);
        assertTrue(ConfigAudit.audit(file, current()).get(0).contains("새 형식"));
    }

    @Test
    void pluginRunsTheAuditAtStartupAndOnReload() throws Exception {
        String src = Files.readString(Path.of("src/main/java/dev/anchormc/plugin/AnchorPlugin.java"));
        int calls = src.split("auditConfig\\(\\);", -1).length - 1;
        assertEquals(2, calls, "onEnable과 reload에서 점검해야 한다");
    }
}

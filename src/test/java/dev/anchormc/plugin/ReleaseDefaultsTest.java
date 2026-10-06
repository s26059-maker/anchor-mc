package dev.anchormc.plugin;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 배포 전 확인: 고정 비밀 시드가 안 들어가고, 기본은 섀도 모드·PAIRED이며, 버전이 1.0.0이다. */
class ReleaseDefaultsTest {
    private static String read(String p) throws Exception {
        return Files.readString(Path.of(p));
    }

    @Test
    void defaultConfigShipsNoSecretSeedValueAndNoLongHexLiteral() throws Exception {
        String cfg = read("src/main/resources/config.yml");
        assertTrue(cfg.lines().anyMatch(l -> l.strip().equals("secret-seed: \"\"")), "기본 config.yml의 secret-seed는 비어 있어야 한다");
        assertFalse(Pattern.compile("[0-9a-fA-F]{32,}").matcher(cfg).find(), "config.yml에 비밀 시드처럼 보이는 긴 16진수가 있다");
    }

    @Test
    void noRealSeedIsCommittedAnywhereInSource() throws Exception {
        // 실서버 시험 등에서 쓴 진짜 시드가 소스(테스트 데이터 포함)에 들어가지 않게 한다: 64자리 16진수는 알려진 가짜 값만 허용.
        String fake = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
        Pattern hex64 = Pattern.compile("(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])");
        try (var files = Files.walk(Path.of("src"))) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                var m = hex64.matcher(Files.readString(f));
                while (m.find()) {
                    assertEquals(fake, m.group(), "진짜 시드로 보이는 64자리 16진수가 소스에 있다: " + f);
                }
            }
        }
    }

    @Test
    void anEmptySeedIsGeneratedRandomlyAndIsUsableButNeverTheSameTwice() {
        assertTrue(SecretSeed.needsGeneration(""));
        assertTrue(SecretSeed.needsGeneration("   "));
        assertTrue(SecretSeed.needsGeneration(null));
        assertFalse(SecretSeed.needsGeneration("0123456789abcdef"));
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String s = SecretSeed.generate();
            assertEquals(64, s.length());
            assertTrue(s.matches("[0-9a-f]{64}"), s);
            assertEquals(32, SecretSeed.toBytes(s).length, "만든 시드를 그대로 읽을 수 있어야 한다");
            assertTrue(seen.add(s), "같은 시드가 두 번 나왔다");
        }
        assertFalse(SecretSeed.generate(new SecureRandom()).equals(SecretSeed.generate(new SecureRandom())));
    }

    @Test
    void seedParsingKeepsTheOldRules() {
        assertEquals(32, SecretSeed.toBytes("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff").length);
        assertEquals(16, SecretSeed.toBytes("0123456789abcdef").length, "16자 문자열은 UTF-8 바이트로");
        assertThrows(IllegalArgumentException.class, () -> SecretSeed.toBytes("short"));
    }

    @Test
    void pluginGeneratesTheSeedOnlyThroughSecretSeedAndNeverLogsIt() throws Exception {
        String src = read("src/main/java/dev/anchormc/plugin/AnchorPlugin.java");
        assertTrue(src.contains("SecretSeed.generate()"));
        assertFalse(src.contains("new SecureRandom()"), "시드 생성은 SecretSeed 한 곳에서만");
        assertFalse(Pattern.compile("getLogger\\(\\)\\.[a-z]+\\([^;]*secret-seed\"\\)").matcher(src).find());
        assertFalse(Pattern.compile("(sendMessage|getLogger\\(\\)\\.[a-z]+)\\([^;]*\\bv\\b[^;]*;").matcher(src.substring(src.indexOf("private byte[] secret()"), src.indexOf("private void secondTick") > 0 ? src.indexOf("private void secondTick") : src.length())).find(),
                "secret() 안에서 시드 값(v)을 로그·메시지로 내보내면 안 된다");
    }

    @Test
    void defaultsAreShadowModeAndPairedAndThereIsNoPunishmentCode() throws Exception {
        String cfg = read("src/main/resources/config.yml");
        assertTrue(cfg.lines().anyMatch(l -> l.strip().equals("shadow-mode: true")));
        assertTrue(cfg.lines().anyMatch(l -> l.strip().equals("confirm-rule: PAIRED")));
        assertTrue(cfg.lines().anyMatch(l -> l.strip().equals("allow-spectator: false")));
        try (var files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String s = Files.readString(f);
                for (String bad : new String[] {".kickPlayer(", ".kick(", "banPlayer(", "BanList", "dispatchCommand(", ".ban("}) {
                    assertFalse(s.contains(bad), f + "에 처벌 동작처럼 보이는 호출이 있다: " + bad);
                }
            }
        }
    }

    @Test
    void versionIsOneZeroZeroAndPluginYmlTakesItFromTheBuild() throws Exception {
        assertTrue(read("build.gradle.kts").lines().anyMatch(l -> l.strip().equals("version = \"1.0.0\"")));
        assertTrue(read("src/main/resources/plugin.yml").lines().anyMatch(l -> l.strip().equals("version: ${version}")));
    }
}

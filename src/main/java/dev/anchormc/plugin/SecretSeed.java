package dev.anchormc.plugin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * 서버 비밀 시드(미끼 자리를 정하는 값). 기본 config.yml에는 값을 넣지 않고, 서버가 처음 켜질 때 암호학적 난수로 새로 만든다.
 * Bukkit과 무관해서 단위 테스트가 된다. 어떤 메시지·로그에도 값을 쓰지 않는다.
 */
final class SecretSeed {
    private SecretSeed() {
    }

    /** 32바이트(16진수 64자) 난수. 호출할 때마다 다르다. */
    static String generate() {
        return generate(new SecureRandom());
    }

    static String generate(SecureRandom rng) {
        byte[] fresh = new byte[32];
        rng.nextBytes(fresh);
        String hex = HexFormat.of().formatHex(fresh);
        java.util.Arrays.fill(fresh, (byte) 0);
        return hex;
    }

    /** 설정에 값이 없거나 공백뿐이면 새로 만들어야 한다. */
    static boolean needsGeneration(String configured) {
        return configured == null || configured.isBlank();
    }

    /**
     * 16진수 문자열(32자 이상, 짝수 길이)이면 그 바이트, 아니면 문자열 자체(UTF-8, 16바이트 이상)를 바이트로 쓴다.
     * @throws IllegalArgumentException 너무 짧을 때
     */
    static byte[] toBytes(String configured) {
        String v = configured.strip();
        if (v.length() >= 32 && v.length() % 2 == 0 && v.chars().allMatch(c -> Character.digit(c, 16) >= 0)) {
            return HexFormat.of().parseHex(v);
        }
        byte[] raw = v.getBytes(StandardCharsets.UTF_8);
        if (raw.length < 16) {
            throw new IllegalArgumentException("secret-seed는 16자 이상이어야 한다");
        }
        return raw;
    }
}

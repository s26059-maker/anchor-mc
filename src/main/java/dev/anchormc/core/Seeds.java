package dev.anchormc.core;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * 서버 비밀 시드에서 (플레이어, 월드, 청크, 슬롯) 같은 이름표별 난수열을 결정적으로 만든다(HMAC-SHA256, JDK 기본 제공).
 * 비밀 시드를 모르면 어느 청크에 미끼가 어디 있는지 예측할 수 없고, 같은 이름표는 언제나 같은 난수열이다.
 * 시드 바이트는 어디에도 출력하지 않는다(toString 없음).
 */
final class Seeds {
    private static final RandomGeneratorFactory<RandomGenerator> FACTORY = RandomGeneratorFactory.of("Xoshiro256PlusPlus");

    private final byte[] secret;
    private final ThreadLocal<Mac> mac;

    Seeds(byte[] secret) {
        if (secret.length < 16) {
            throw new IllegalArgumentException("비밀 시드는 16바이트 이상이어야 한다");
        }
        this.secret = secret.clone();
        this.mac = ThreadLocal.withInitial(() -> {
            try {
                Mac m = Mac.getInstance("HmacSHA256");
                m.init(new SecretKeySpec(this.secret, "HmacSHA256"));
                return m;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    long derive(String label) {
        byte[] h = mac.get().doFinal(label.getBytes(StandardCharsets.UTF_8));
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (h[i] & 0xffL);
        }
        return v;
    }

    RandomGenerator rng(String label) {
        return FACTORY.create(derive(label));
    }
}

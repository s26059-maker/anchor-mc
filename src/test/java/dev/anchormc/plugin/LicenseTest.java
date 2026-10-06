package dev.anchormc.plugin;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GPL-3.0 배포 표기: LICENSE는 공식 원문 그대로, 저작권자 표기는 plugin.yml과 README에 맞춰져 있다. */
class LicenseTest {
    /** gnu.org gpl-3.0.txt의 SHA-256(줄바꿈은 LF 기준). */
    private static final String GPL3_SHA256 = "3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986";

    private static String read(String p) throws Exception {
        return Files.readString(Path.of(p), StandardCharsets.UTF_8);
    }

    @Test
    void licenseFileIsTheOfficialGpl3TextUnmodified() throws Exception {
        String text = read("LICENSE").replace("\r\n", "\n");
        assertTrue(text.contains("GNU GENERAL PUBLIC LICENSE") && text.contains("Version 3, 29 June 2007"));
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        assertEquals(GPL3_SHA256, hash, "LICENSE가 공식 GPL-3.0 원문과 다르다(고치지 말고 원문을 쓴다)");
    }

    @Test
    void copyrightHolderAndLicenseAreStatedInPluginYmlAndReadme() throws Exception {
        String yml = read("src/main/resources/plugin.yml");
        assertTrue(yml.lines().anyMatch(l -> l.strip().equals("authors: [정성원]")), "plugin.yml authors");
        assertTrue(yml.contains("GPL-3.0") && yml.contains("Copyright (C) 2026 정성원"));
        String readme = read("README.md");
        assertTrue(readme.contains("Copyright (C) 2026 정성원"));
        assertTrue(readme.contains("GNU General Public License v3.0") && readme.contains("(LICENSE)"));
        assertTrue(readme.contains("어떠한 보증도 없습니다"), "GPL의 무보증 고지");
        assertFalse(readme.contains("<이름>") || yml.contains("<이름>"), "저작권자 자리표시자가 남았다");
    }
}

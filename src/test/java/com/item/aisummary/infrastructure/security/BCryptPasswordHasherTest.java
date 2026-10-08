package com.item.aisummary.infrastructure.security;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 验证既有 BCrypt 格式、随机盐和原样密码匹配，不接入任何登录流程。 */
class BCryptPasswordHasherTest {
    private final BCryptPasswordHasher hasher = new BCryptPasswordHasher();

    @Test
    void matchesExistingUnprefixedBcryptHashWithoutReencoding() {
        // Spring Security 官方密码存储示例的已知摘要，原密码为 password。
        String historicalHash = "$2a$10$dXJ3SW6G7P50lGmMkkmwe.20cQQubK3.HZWzG3YB1tlRy.fqvM/BG";
        assertThat(hasher.matches("password", historicalHash)).isTrue();
        assertThat(hasher.matches("different", historicalHash)).isFalse();
        assertThat(hasher.matches(null, historicalHash)).isFalse();
        assertThat(hasher.matches("password", null)).isFalse();
    }

    @Test
    void preservesWhitespaceAndUsesFreshSalts() {
        String first = hasher.encode(" secret ");
        String second = hasher.encode(" secret ");
        assertThat(first).isNotEqualTo(second);
        assertThat(hasher.matches(" secret ", first)).isTrue();
        assertThat(hasher.matches("secret", first)).isFalse();
    }

    @Test
    void rejectsEmptyAndOverlongUtf8Input() {
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.encode(null));
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.encode(" \t\n"));
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.encode("a".repeat(73)));
        assertThatIllegalArgumentException().isThrownBy(() -> hasher.encode("密".repeat(25)));
        assertThat(hasher.matches("密".repeat(24), hasher.encode("密".repeat(24)))).isTrue();
    }
}

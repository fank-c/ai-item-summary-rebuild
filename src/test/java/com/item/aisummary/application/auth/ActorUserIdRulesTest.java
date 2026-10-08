package com.item.aisummary.application.auth;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 按 Unicode 码点而非 UTF-16 代码单元限制身份，不裁剪或暴露输入。 */
class ActorUserIdRulesTest {
    @Test
    void limitsIdentityByCodePointsWithoutCountingSurrogatePairsTwice() {
        String supplementaryCharacter = new String(Character.toChars(0x1F600));
        for (String character : new String[] {"a", supplementaryCharacter}) {
            assertThatCode(() -> ActorUserIdRules.requireValid(character.repeat(64))).doesNotThrowAnyException();
            assertThatIllegalArgumentException().isThrownBy(() -> ActorUserIdRules.requireValid(character.repeat(65)))
                .withMessage("用户 ID 长度不能超过 64 个字符");
        }
    }

    @Test
    void rejectsMissingIdentityWithoutEchoingInput() {
        for (String actor : new String[] {null, "", " ", "\t\n", "\u2003"}) {
            assertThatIllegalArgumentException().isThrownBy(() -> ActorUserIdRules.requireValid(actor))
                .withMessage("用户 ID 不能为空");
        }
    }

    @Test
    void countsExplicitLeadingAndTrailingSpacesWithoutTrimming() {
        assertThatCode(() -> ActorUserIdRules.requireValid(" " + "A".repeat(62) + " ")).doesNotThrowAnyException();
        assertThatIllegalArgumentException().isThrownBy(() -> ActorUserIdRules.requireValid(" " + "A".repeat(63) + " "))
            .withMessage("用户 ID 长度不能超过 64 个字符");
    }
}

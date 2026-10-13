package com.item.aisummary.domain.knowledge.value;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * 来源位置值对象（SourceLocation）业务规则单元测试。
 *
 * <p>验证偏移成对（0 &lt;= start &lt; end）与页码成对（1 &lt;= start &lt;= end）规则，以及允许全为空白的合法性。
 */
class SourceLocationTest {
    @Test
    void acceptsAllUnknownOrCompleteIndependentPositionPairs() {
        assertThat(new SourceLocation(null, null, null, null))
                .isEqualTo(new SourceLocation(null, null, null, null));
        assertThat(new SourceLocation(1000, 1200, null, null).startOffset()).isEqualTo(1000);
        assertThat(new SourceLocation(null, null, 2, 3).pageStart()).isEqualTo(2);
        assertThat(new SourceLocation(1000, 1200, 2, 3).startOffset()).isEqualTo(1000);
    }

    @Test
    void rejectsIncompleteOrInvalidOffsetAndPagePairs() {
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(1, null, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(null, 2, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(2, 1, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(-1, 1, null, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(null, null, 0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(null, null, 2, null));
        assertThatIllegalArgumentException().isThrownBy(() -> new SourceLocation(null, null, 3, 2));
    }
}

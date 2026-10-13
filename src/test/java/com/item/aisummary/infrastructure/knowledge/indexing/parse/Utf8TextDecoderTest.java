package com.item.aisummary.infrastructure.knowledge.indexing.parse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.item.aisummary.application.knowledge.indexing.source.FileFormat;
import com.item.aisummary.application.knowledge.indexing.source.PreparedSource;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Utf8TextDecoderTest {

    @Test
    void decodesValidUtf8Content() {
        String original = "Hello 世界！Test UTF-8 decoding.";
        PreparedSource source = ParserTestSources.source(original, FileFormat.TXT);

        String decoded = Utf8TextDecoder.decode(source, FileFormat.TXT, 1024);

        assertThat(decoded).isEqualTo(original);
    }

    @Test
    void stripsOnlyFirstLeadingBom() {
        // 单个开头 BOM 移除
        PreparedSource singleBom = ParserTestSources.source("\uFEFFHello World", FileFormat.TXT);
        assertThat(Utf8TextDecoder.decode(singleBom, FileFormat.TXT, 1024)).isEqualTo("Hello World");

        // 连续两个开头 BOM 只移除第一个，保留第二个
        PreparedSource doubleBom = ParserTestSources.source("\uFEFF\uFEFFHello World", FileFormat.TXT);
        assertThat(Utf8TextDecoder.decode(doubleBom, FileFormat.TXT, 1024)).isEqualTo("\uFEFFHello World");

        // 正文中间的 BOM 原样保留
        PreparedSource middleBom = ParserTestSources.source("Hello\uFEFFWorld", FileFormat.TXT);
        assertThat(Utf8TextDecoder.decode(middleBom, FileFormat.TXT, 1024)).isEqualTo("Hello\uFEFFWorld");
    }

    @Test
    void normalizesCrlfAndCrToLfWithoutModifyingOtherCharacters() {
        String input = "  Line 1  \r\n  Line 2  \r  Line 3  \n  Line 4  ";
        PreparedSource source = ParserTestSources.source(input, FileFormat.TXT);

        String decoded = Utf8TextDecoder.decode(source, FileFormat.TXT, 1024);

        assertThat(decoded).isEqualTo("  Line 1  \n  Line 2  \n  Line 3  \n  Line 4  ");
    }

    @Test
    void decodesEmptySourceToEmptyString() {
        PreparedSource source = ParserTestSources.source(new byte[0], FileFormat.TXT);

        String decoded = Utf8TextDecoder.decode(source, FileFormat.TXT, 1024);

        assertThat(decoded).isEmpty();
    }

    @Test
    void rejectsTruncatedUtf8Bytes() {
        // 'a', 'b', 后接 3 字节 UTF-8 字符（如“中” 0xE4 0xB8 0xAD）的前 2 个字节，末尾截断
        byte[] truncated = new byte[]{'a', 'b', (byte) 0xE4, (byte) 0xB8};
        PreparedSource source = ParserTestSources.source(truncated, FileFormat.TXT);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败")
                .hasMessageNotContaining("ab");
    }

    @Test
    void rejectsGbkEncodedBytes() {
        byte[] gbkBytes = "这是GBK中文文本".getBytes(Charset.forName("GBK"));
        PreparedSource source = ParserTestSources.source(gbkBytes, FileFormat.TXT);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败")
                .hasMessageNotContaining("GBK");
    }

    @Test
    void rejectsUtf16EncodedBytes() {
        byte[] utf16WithBom = "Hello UTF-16".getBytes(StandardCharsets.UTF_16);
        PreparedSource sourceWithBom = ParserTestSources.source(utf16WithBom, FileFormat.TXT);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(sourceWithBom, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败")
                .hasMessageNotContaining("Hello");

        byte[] utf16Be = "Hello UTF-16BE".getBytes(StandardCharsets.UTF_16BE);
        PreparedSource sourceBe = ParserTestSources.source(utf16Be, FileFormat.TXT);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(sourceBe, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("文本内容不能包含 NUL 字符")
                .hasMessageNotContaining("Hello");
    }

    @Test
    void rejectsNulTextRatherThanTreatingValidUtf8BinaryAsText() {
        assertThatIllegalArgumentException().isThrownBy(() -> Utf8TextDecoder.decode(
                ParserTestSources.source("plain\0text", FileFormat.TXT), FileFormat.TXT, 1024))
                .withMessage("文本内容不能包含 NUL 字符");
    }

    @Test
    void rejectsIllegalUtf8Bytes() {
        byte[] illegal = new byte[]{(byte) 0xFF, (byte) 0xFE, 0x00};
        PreparedSource source = ParserTestSources.source(illegal, FileFormat.TXT);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败");
    }

    @Test
    void rejectsNullSourceAndNullExpectedFormat() {
        PreparedSource source = ParserTestSources.source("Hello", FileFormat.TXT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(null, FileFormat.TXT, 1024))
                .withMessage("源文件对象不能为空");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(source, null, 1024))
                .withMessage("期望文件格式不能为空");
    }

    @Test
    void rejectsSourceFormatMismatch() {
        PreparedSource mdSource = ParserTestSources.source("# Markdown title", FileFormat.MD);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(mdSource, FileFormat.TXT, 1024))
                .withMessage("源文件格式不匹配");
    }

    @Test
    void rejectsSourceExceedingBudget() {
        PreparedSource source = ParserTestSources.source("1234567890", FileFormat.TXT);

        // 大小预算小于源大小
        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, 5))
                .withMessage("源文件大小超过预算限制");
    }

    @Test
    void validatesMaxSourceSizeParameter() {
        PreparedSource source = ParserTestSources.source("Hello", FileFormat.TXT);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, 0))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, -1))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> Utf8TextDecoder.decode(source, FileFormat.TXT, Integer.MAX_VALUE))
                .withMessage("源文件大小上限必须为正且小于 Integer.MAX_VALUE 字节");
    }

    @Test
    void closesStreamInTryWithResourcesOnSuccess() throws Exception {
        byte[] bytes = "Hello World".getBytes(StandardCharsets.UTF_8);
        var trackingStream = new TrackingInputStream(bytes);

        PreparedSource mockSource = mock(PreparedSource.class);
        when(mockSource.format()).thenReturn(FileFormat.TXT);
        when(mockSource.size()).thenReturn((long) bytes.length);
        when(mockSource.openStream()).thenReturn(trackingStream);

        String result = Utf8TextDecoder.decode(mockSource, FileFormat.TXT, 1024);

        assertThat(result).isEqualTo("Hello World");
        assertThat(trackingStream.isClosed()).isTrue();
    }

    @Test
    void closesStreamInTryWithResourcesOnDecodeFailure() {
        byte[] badBytes = new byte[]{(byte) 0xFF, (byte) 0xFE};
        var trackingStream = new TrackingInputStream(badBytes);

        PreparedSource mockSource = mock(PreparedSource.class);
        when(mockSource.format()).thenReturn(FileFormat.TXT);
        when(mockSource.size()).thenReturn((long) badBytes.length);
        when(mockSource.openStream()).thenReturn(trackingStream);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(mockSource, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(trackingStream.isClosed()).isTrue();
    }

    @Test
    void closesStreamInTryWithResourcesWhenStreamExceedsBudget() {
        byte[] bytes = new byte[20];
        var trackingStream = new TrackingInputStream(bytes);

        PreparedSource mockSource = mock(PreparedSource.class);
        when(mockSource.format()).thenReturn(FileFormat.TXT);
        when(mockSource.size()).thenReturn(10L); // 声明 10 字节，但流中包含 20 字节
        when(mockSource.openStream()).thenReturn(trackingStream);

        assertThatThrownBy(() -> Utf8TextDecoder.decode(mockSource, FileFormat.TXT, 15))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件大小超过预算限制");

        assertThat(trackingStream.isClosed()).isTrue();
    }

    @Test
    void doesNotEchoSensitiveContentOrFileNameInExceptionMessages() {
        byte[] sensitiveBytes = "SECRET_PASSWORD_12345\uFFFD\uFFFF".getBytes(StandardCharsets.UTF_8);
        sensitiveBytes[sensitiveBytes.length - 1] = (byte) 0xFF; // 使其解码失败

        PreparedSource mockSource = mock(PreparedSource.class);
        when(mockSource.format()).thenReturn(FileFormat.TXT);
        when(mockSource.size()).thenReturn((long) sensitiveBytes.length);
        when(mockSource.originName()).thenReturn("super-secret-passwords.txt");
        when(mockSource.openStream()).thenReturn(new ByteArrayInputStream(sensitiveBytes));

        assertThatThrownBy(() -> Utf8TextDecoder.decode(mockSource, FileFormat.TXT, 1024))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("源文件 UTF-8 解码失败")
                .hasMessageNotContaining("SECRET_PASSWORD")
                .hasMessageNotContaining("super-secret-passwords");
    }

    private static class TrackingInputStream extends ByteArrayInputStream {
        private boolean closed;

        TrackingInputStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }

        boolean isClosed() {
            return closed;
        }
    }
}

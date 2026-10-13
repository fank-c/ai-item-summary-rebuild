package com.item.aisummary.application.knowledge.processing;

import com.item.aisummary.application.file.content.FileContentResource;
import com.item.aisummary.application.knowledge.exception.KnowledgeSourceException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class KnowledgeSourceReaderTest {
    @Test void readsBinaryBytesToEofBeforeParsingAndLeavesStreamOwnershipToCaller() throws Exception {
        byte[] bytes = {(byte) 0xff, 0, (byte) 0xfe};
        var stream = new TrackingStream(bytes);
        var reader = new KnowledgeSourceReader(3);
        PreparedSource source;
        try (var resource = resource(stream, 3, digest(bytes))) {
            source = reader.read(resource, FileFormat.PDF);
            assertThat(stream.closed).isFalse();
            assertThat(stream.eof).isTrue();
            assertThat(source.size()).isEqualTo(3);
            assertThat(source.openStream().readAllBytes()).containsExactly(bytes);
            assertThat(source.openStream().readAllBytes()).containsExactly(bytes);
            assertThat(source.sha256()).isEqualTo(digest(bytes));
            assertThat(source.toString()).doesNotContain("private-name", "ffffff");
        }
        assertThat(stream.closed).isTrue();
    }

    @Test void enforcesActualBytesAndMetadataSizeAndDigest() throws Exception {
        byte[] bytes = {1, 2, 3};
        var reader = new KnowledgeSourceReader(2);
        assertFailure("SOURCE_SIZE_EXCEEDED", () -> reader.read(resource(new ByteArrayInputStream(bytes), 2, digest(bytes)), FileFormat.TXT));
        var enough = new KnowledgeSourceReader(3);
        assertFailure("SOURCE_SIZE_MISMATCH", () -> enough.read(resource(new ByteArrayInputStream(bytes), 2, digest(bytes)), FileFormat.TXT));
        assertFailure("SOURCE_DIGEST_MISMATCH", () -> enough.read(resource(new ByteArrayInputStream(bytes), 3, "0".repeat(64)), FileFormat.TXT));
        assertFailure("SOURCE_DIGEST_MISSING", () -> enough.read(resource(new ByteArrayInputStream(bytes), 3, null), FileFormat.TXT));
    }

    @Test void convertsIoFailureWithoutExposingSupplierMessage() {
        InputStream broken = new InputStream() {
            @Override public int read() throws IOException { throw new IOException("private supplier body"); }
        };
        assertFailure("SOURCE_READ_FAILED", () -> new KnowledgeSourceReader(3).read(resource(broken, 3, "0".repeat(64)), FileFormat.TXT));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeSourceReader(0));
        assertThatIllegalArgumentException().isThrownBy(() -> new KnowledgeSourceReader(Integer.MAX_VALUE));
    }

    private static void assertFailure(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOf(KnowledgeSourceException.class)
                .hasMessageNotContaining("private").extracting("code").isEqualTo(code);
    }

    private static FileContentResource resource(InputStream stream, long size, String digest) {
        return new FileContentResource("file", "private-name.pdf", "application/pdf", size, digest, stream);
    }

    private static String digest(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static class TrackingStream extends ByteArrayInputStream {
        boolean closed;
        boolean eof;
        TrackingStream(byte[] bytes) { super(bytes); }
        @Override public synchronized int read(byte[] bytes, int off, int len) {
            int result = super.read(bytes, off, len);
            if (result == -1) eof = true;
            return result;
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}

package com.item.aisummary.application.file;

import com.item.aisummary.application.auth.AuthenticationRequiredException;
import com.item.aisummary.application.file.port.*;
import com.item.aisummary.domain.file.FileValidationException;
import com.item.aisummary.web.file.FileStorageTestConfiguration.MemoryStorage;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 防止相信声明长度、泄漏重开流、无身份访问和伪造跨系统事务成功。 */
class FileServiceTest {
    private final FileRepository repository = mock(FileRepository.class);
    private final MemoryStorage storage = new MemoryStorage();
    private final FileService service = new FileService(repository, storage,
        new FilePolicy(4, Set.of("txt"), "files", Duration.ofMinutes(15)));

    @Test void countsActualBytesAndRejectsUnderdeclaredOversizeBeforeUpload() {
        AtomicInteger closes = new AtomicInteger();
        assertThatThrownBy(() -> service.upload("owner", "minio", source(1, "12345", closes)))
            .isInstanceOf(FileValidationException.class);
        assertThat(storage.objects).isEmpty();
        assertThat(closes.get()).isEqualTo(1);
    }
    @Test void savesActualSizeAndClosesBothStreamsEvenWhenDatabaseFails() {
        AtomicInteger closes = new AtomicInteger();
        when(repository.insert(any())).thenThrow(new IllegalStateException("database failure"));
        assertThatThrownBy(() -> service.upload("owner", "minio", source(1, "abc", closes)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(storage.objects.values()).singleElement().satisfies(bytes -> assertThat(bytes).containsExactly(97, 98, 99));
        assertThat(closes.get()).isEqualTo(2);
    }
    @Test void actualSizeReplacesUnderdeclaredLengthInSavedMetadata() {
        when(repository.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AtomicInteger closes = new AtomicInteger();
        var file = service.upload("owner", "minio", source(1, "abc", closes));
        assertThat(file.size()).isEqualTo(3);
        assertThat(file.sha256()).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        assertThat(closes.get()).isEqualTo(2);
    }
    @Test void sourceReadFailureAndActuallyEmptyContentAreValidationErrors() {
        UploadSource broken = new UploadSource() {
            public String originalName() { return "a.txt"; }
            public String mime() { return null; }
            public long declaredSize() { return 1; }
            public InputStream openStream() throws IOException { throw new IOException("first open"); }
        };
        assertThatThrownBy(() -> service.upload("owner", "minio", broken)).isInstanceOf(FileValidationException.class);
        assertThatThrownBy(() -> service.upload("owner", "minio", source(1, "", new AtomicInteger())))
            .isInstanceOf(FileValidationException.class);
        assertThat(storage.objects).isEmpty();
    }
    @Test void secondOpenFailureIsStorageFailure() {
        UploadSource input = new UploadSource() {
            int opens;
            public String originalName() { return "a.txt"; }
            public String mime() { return null; }
            public long declaredSize() { return 3; }
            public InputStream openStream() throws IOException {
                if (++opens == 2) throw new IOException("second open");
                return new ByteArrayInputStream(new byte[] {97, 98, 99});
            }
        };
        assertThatThrownBy(() -> service.upload("owner", "minio", input)).isInstanceOf(ObjectStorageException.class);
        assertThat(storage.objects).isEmpty();
    }
    @Test void deletingObjectThenDatabaseFailureDoesNotRestoreObjectOrClaimSuccess() {
        when(repository.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var file = service.upload("owner", "minio", source(3, "abc", new AtomicInteger()));
        when(repository.findById(file.id())).thenReturn(java.util.Optional.of(file));
        when(repository.softDelete(eq(file.id()), eq("owner"), any())).thenReturn(0);
        assertThatThrownBy(() -> service.delete("owner", file.id())).isInstanceOf(IllegalStateException.class);
        assertThat(storage.objects).isEmpty();
        assertThat(service.requireOwnedProcessable("owner", file.id()).originName()).isEqualTo("a.txt");
    }
    @Test void everyEntrypointRequiresExplicitIdentity() {
        for (String actor : new String[] {null, "", " "}) {
            assertThatThrownBy(() -> service.upload(actor, "minio", null)).isInstanceOf(AuthenticationRequiredException.class);
            assertThatThrownBy(() -> service.createDownloadUrl(actor, "id")).isInstanceOf(AuthenticationRequiredException.class);
            assertThatThrownBy(() -> service.delete(actor, "id")).isInstanceOf(AuthenticationRequiredException.class);
            assertThatThrownBy(() -> service.requireOwnedProcessable(actor, "id")).isInstanceOf(AuthenticationRequiredException.class);
            assertThatThrownBy(() -> service.openForProcessing(actor, "id")).isInstanceOf(AuthenticationRequiredException.class);
        }
    }
    private static UploadSource source(long declared, String text, AtomicInteger closes) {
        return new UploadSource() {
            public String originalName() { return "a.txt"; }
            public String mime() { return "text/plain"; }
            public long declaredSize() { return declared; }
            public InputStream openStream() {
                return new ByteArrayInputStream(text.getBytes(java.nio.charset.StandardCharsets.UTF_8)) {
                    @Override public void close() { closes.incrementAndGet(); }
                };
            }
        };
    }
}

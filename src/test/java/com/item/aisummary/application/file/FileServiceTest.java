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
        verifyNoInteractions(repository);
        assertThat(storage.objects).isEmpty();
    }

    @Test void overlongIdentityIsRejectedBeforeAnyDependencyAccess() {
        ObjectStorageRouter router = mock(ObjectStorageRouter.class);
        UploadSource input = mock(UploadSource.class);
        FileService guarded = new FileService(repository, router,
            new FilePolicy(4, Set.of("txt"), "files", Duration.ofMinutes(15)));
        for (String actor : new String[] {"a".repeat(65), new String(Character.toChars(0x1F600)).repeat(65)}) {
            assertThatIllegalArgumentException().isThrownBy(() -> guarded.upload(actor, "minio", input))
                .withMessage("用户 ID 长度不能超过 64 个字符");
            assertThatIllegalArgumentException().isThrownBy(() -> guarded.createDownloadUrl(actor, "id"));
            assertThatIllegalArgumentException().isThrownBy(() -> guarded.delete(actor, "id"));
            assertThatIllegalArgumentException().isThrownBy(() -> guarded.requireOwnedProcessable(actor, "id"));
            assertThatIllegalArgumentException().isThrownBy(() -> guarded.openForProcessing(actor, "id"));
        }
        verifyNoInteractions(repository, router, input);
    }

    @Test void valid64CodePointIdentityIsSavedExactlyAsSupplied() {
        when(repository.insert(any())).thenAnswer(invocation -> invocation.getArgument(0));
        for (String actor : new String[] {"a".repeat(64), new String(Character.toChars(0x1F600)).repeat(64), " " + "A".repeat(62) + " "}) {
            AtomicInteger closes = new AtomicInteger();
            var file = service.upload(actor, "minio", source(1, "abc", closes));
            assertThat(file.createBy()).isEqualTo(actor);
            assertThat(file.updateBy()).isEqualTo(actor);
            assertThat(closes.get()).isEqualTo(2);
        }
        verify(repository, never()).findById(any());
    }

    @Test void invalidKeyIsRejectedBeforeOpeningSourceOrWritingContent() throws IOException {
        UploadSource input = mock(UploadSource.class);
        when(input.declaredSize()).thenReturn(1L);
        when(input.originalName()).thenReturn("文".repeat(251) + ".txt");
        when(input.mime()).thenReturn("text/plain");
        FileService guarded = new FileService(repository, storage,
            new FilePolicy(4, Set.of("txt"), "中".repeat(150), Duration.ofMinutes(15)));

        assertThatThrownBy(() -> guarded.upload("owner", "minio", input)).isInstanceOf(FileValidationException.class)
            .hasMessage("对象 Key 的 UTF-8 长度不能超过 1023 字节");
        verify(input, never()).openStream();
        verifyNoInteractions(repository);
        assertThat(storage.objects).isEmpty();
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

package com.item.aisummary.infrastructure.file.storage;

import com.item.aisummary.application.file.port.ObjectStorageException;
import com.aliyun.oss.OSS;
import com.aliyun.oss.model.ObjectMetadata;
import com.aliyun.oss.model.OSSObject;
import io.minio.*;
import io.minio.http.Method;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** SDK 为外部边界替身，验证本适配器发出的 Bucket、Key、MIME、大小、摘要和签名请求。 */
class StorageAdapterTest {
    @Test void minioUploadCarriesActualContentMetadataAndOnlyGetIsSigned() throws Exception {
        MinioClient sdk = mock(MinioClient.class);
        var adapter = new MinioObjectStorageClient(sdk);
        var stream = new ByteArrayInputStream(new byte[] {97, 98, 99});
        adapter.putObject("test-bucket", "files/a.txt", "text/plain", 3, "server-digest", stream);
        var upload = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(sdk).putObject(upload.capture());
        assertThat(upload.getValue().bucket()).isEqualTo("test-bucket");
        assertThat(upload.getValue().object()).isEqualTo("files/a.txt");
        assertThat(upload.getValue().objectSize()).isEqualTo(3);
        assertThat(upload.getValue().contentType()).isEqualTo("text/plain");
        assertThat(upload.getValue().userMetadata().get("x-amz-meta-sha256")).containsExactly("server-digest");
        assertThat(upload.getValue().stream().readAllBytes()).containsExactly(97, 98, 99);
        when(sdk.getPresignedObjectUrl(any())).thenReturn("https://example.test/signed");
        assertThat(adapter.presignGetObject("test-bucket", "files/a.txt", Duration.ofSeconds(37)))
            .isEqualTo("https://example.test/signed");
        var signing = ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(sdk).getPresignedObjectUrl(signing.capture());
        assertThat(signing.getValue().method()).isEqualTo(Method.GET);
        assertThat(signing.getValue().expiry()).isEqualTo(37);
        assertThat(signing.getValue().object()).isEqualTo("files/a.txt");
        adapter.deleteObject("test-bucket", "files/a.txt");
        var deletion = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(sdk).removeObject(deletion.capture());
        assertThat(deletion.getValue().object()).isEqualTo("files/a.txt");
    }
    @Test void minioConvertsAllSdkFailuresToStableStorageException() throws Exception {
        MinioClient sdk = mock(MinioClient.class);
        var adapter = new MinioObjectStorageClient(sdk);
        when(sdk.putObject(any())).thenThrow(new IOException("secret"));
        when(sdk.getObject(any())).thenThrow(new IOException("secret"));
        when(sdk.getPresignedObjectUrl(any())).thenThrow(new IOException("secret"));
        doThrow(new IOException("secret")).when(sdk).removeObject(any());
        assertThatThrownBy(() -> adapter.putObject("test-bucket", "key", "text/plain", 1, "sha", new ByteArrayInputStream(new byte[] {1})))
            .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> adapter.openObject("test-bucket", "key")).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> adapter.presignGetObject("test-bucket", "key", Duration.ofMinutes(15))).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> adapter.deleteObject("test-bucket", "key")).isInstanceOf(ObjectStorageException.class);
    }
    @Test void minioRealSdkSignsOfflineWithRequestedLifetime() throws Exception {
        try (var sdk = MinioClient.builder().endpoint("https://storage.example.test").region("us-east-1")
                .credentials("test-access", "test-secret").build()) {
            var adapter = new MinioObjectStorageClient(sdk);
            String url = adapter.presignGetObject("test-bucket", "files/a.txt", Duration.ofSeconds(37));
            assertThat(url).contains("X-Amz-Expires=37", "X-Amz-Signature=", "/test-bucket/files/a.txt");
        }
    }
    @Test void ossUploadAndSignatureUseServerMetadataAndConfiguredExpiry() throws Exception {
        OSS sdk = mock(OSS.class);
        var adapter = new AliyunOssObjectStorageClient(sdk);
        var stream = new ByteArrayInputStream(new byte[] {97, 98, 99});
        adapter.putObject("test-bucket", "files/a.txt", "text/plain", 3, "server-digest", stream);
        var metadata = ArgumentCaptor.forClass(ObjectMetadata.class);
        verify(sdk).putObject(eq("test-bucket"), eq("files/a.txt"), same(stream), metadata.capture());
        assertThat(metadata.getValue().getContentLength()).isEqualTo(3);
        assertThat(metadata.getValue().getContentType()).isEqualTo("text/plain");
        assertThat(metadata.getValue().getUserMetadata()).containsEntry("sha256", "server-digest");
        when(sdk.generatePresignedUrl(anyString(), anyString(), any(Date.class)))
            .thenReturn(URI.create("https://example.test/signed").toURL());
        Instant before = Instant.now();
        adapter.presignGetObject("test-bucket", "files/a.txt", Duration.ofSeconds(37));
        var expiry = ArgumentCaptor.forClass(Date.class);
        verify(sdk).generatePresignedUrl(eq("test-bucket"), eq("files/a.txt"), expiry.capture());
        assertThat(expiry.getValue().toInstant()).isBetween(before.plusSeconds(36), Instant.now().plusSeconds(38));
    }
    @Test void ossContentResourceClosesUnderlyingStreamOnce() throws Exception {
        OSS sdk = mock(OSS.class);
        var object = new OSSObject();
        AtomicInteger closed = new AtomicInteger();
        object.setObjectContent(new ByteArrayInputStream(new byte[] {97, 98, 99}) {
            @Override public void close() { closed.incrementAndGet(); }
        });
        when(sdk.getObject("test-bucket", "key")).thenReturn(object);
        var stream = new AliyunOssObjectStorageClient(sdk).openObject("test-bucket", "key");
        assertThat(stream.readAllBytes()).containsExactly(97, 98, 99);
        stream.close(); stream.close();
        assertThat(closed.get()).isEqualTo(1);
    }
    @Test void ossConvertsReadUploadSignatureAndDeleteFailures() {
        OSS sdk = mock(OSS.class);
        var adapter = new AliyunOssObjectStorageClient(sdk);
        when(sdk.putObject(anyString(), anyString(), any(java.io.InputStream.class), any(ObjectMetadata.class)))
            .thenThrow(new com.aliyun.oss.ClientException("secret"));
        when(sdk.getObject(anyString(), anyString())).thenThrow(new com.aliyun.oss.ClientException("secret"));
        when(sdk.generatePresignedUrl(anyString(), anyString(), any(Date.class))).thenThrow(new com.aliyun.oss.ClientException("secret"));
        doThrow(new com.aliyun.oss.ClientException("secret")).when(sdk).deleteObject(anyString(), anyString());
        assertThatThrownBy(() -> adapter.putObject("test-bucket", "key", "text/plain", 1, "sha", new ByteArrayInputStream(new byte[] {1})))
            .isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> adapter.openObject("test-bucket", "key")).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> adapter.presignGetObject("test-bucket", "key", Duration.ofMinutes(15))).isInstanceOf(ObjectStorageException.class);
        assertThatThrownBy(() -> adapter.deleteObject("test-bucket", "key")).isInstanceOf(ObjectStorageException.class);
    }
}

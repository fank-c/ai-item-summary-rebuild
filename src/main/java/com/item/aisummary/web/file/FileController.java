package com.item.aisummary.web.file;

import com.item.aisummary.application.file.FileDownloadUrl;
import com.item.aisummary.application.file.FileService;
import com.item.aisummary.infrastructure.auth.LoginUserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.net.URI;

/** 文件三项 HTTP 入口；身份从服务端上下文读取，不接受客户端所有者参数。 */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileService service;
    public FileController(FileService service) { this.service = service; }

    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<FileResponse> upload(@RequestParam(value = "storageType", required = false) String storageType,
                                               @RequestPart(value = "file", required = false) MultipartFile file,
                                               HttpServletRequest request) {
        String actor = LoginUserContext.require(request).id();
        var result = service.upload(actor, storageType, file == null ? null : new MultipartUploadSource(file));
        return ResponseEntity.created(URI.create("/api/files/" + result.id())).body(FileResponse.from(result));
    }

    @GetMapping("/{fileId}/download-url")
    public FileDownloadUrl download(@PathVariable String fileId, HttpServletRequest request) {
        return service.createDownloadUrl(LoginUserContext.require(request).id(), fileId);
    }

    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable String fileId, HttpServletRequest request) {
        service.delete(LoginUserContext.require(request).id(), fileId);
        return ResponseEntity.noContent().build();
    }
}

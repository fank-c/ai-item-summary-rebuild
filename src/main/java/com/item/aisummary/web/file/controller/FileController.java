package com.item.aisummary.web.file.controller;

import com.item.aisummary.application.file.result.FileDownloadUrl;
import com.item.aisummary.application.file.service.FileService;
import com.item.aisummary.infrastructure.auth.web.LoginUserContext;
import com.item.aisummary.web.file.response.FileResponse;
import com.item.aisummary.web.file.upload.MultipartUploadSource;
import java.net.URI;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 文件三项 HTTP 入口；身份从服务端上下文读取，不接受客户端所有者参数。 */
@RestController
@RequestMapping("/api/files")
public class FileController {
    private final FileService service;

    public FileController(FileService service) {
        this.service = service;
    }

    /**
     * 上传单个文件并登记其元数据。
     *
     * <p>从请求上下文中获取当前登录用户 ID 作为文件所有者；将文件流及元数据传递给应用服务。
     * 成功上传后返回 HTTP 201 Created，并在 Location 响应头中包含新建资源路径 /api/files/{fileId}。
     *
     * @param storageType 目标对象存储类型（如 "minio" 或 "aliyun"），可选
     * @param file 客户端上传的 multipart 格式文件
     * @param request 当前 HTTP 请求，用于读取登录用户上下文
     * @return 包含 201 状态码和文件公开元数据的响应实体
     */
    @PostMapping(consumes = "multipart/form-data")
    public ResponseEntity<FileResponse> upload(
            @RequestParam(value = "storageType", required = false) String storageType,
            @RequestPart(value = "file", required = false) MultipartFile file,
            HttpServletRequest request) {
        String actor = LoginUserContext.require(request).id();
        var source = file == null ? null : new MultipartUploadSource(file);
        var result = service.upload(actor, storageType, source);

        // Location 只标识上传资源；当前没有 GET /api/files/{fileId} 详情接口。
        return ResponseEntity.created(URI.create("/api/files/" + result.id()))
                .body(FileResponse.from(result));
    }

    /**
     * 获取指定文件的临时下载预签名 URL。
     *
     * <p>仅允许当前登录所有者获取其拥有且状态正常的文件下载凭证，地址具有固定有效期限。
     *
     * @param fileId 文件唯一标识，路径参数
     * @param request 当前 HTTP 请求，用于获取调用者身份
     * @return 包含临时下载签名地址及有效期限的结果对象
     */
    @GetMapping("/{fileId}/download-url")
    public FileDownloadUrl download(@PathVariable String fileId, HttpServletRequest request) {
        return service.createDownloadUrl(LoginUserContext.require(request).id(), fileId);
    }

    /**
     * 逻辑删除指定文件并移除对象存储中的物理对象。
     *
     * <p>仅文件所有者有权删除；先删除远端对象，再将数据库记录置为逻辑删除。成功后返回 HTTP 204 No Content。
     *
     * @param fileId 待删除文件唯一标识，路径参数
     * @param request 当前 HTTP 请求，用于校验调用者所有权
     * @return HTTP 204 空响应实体
     */
    @DeleteMapping("/{fileId}")
    public ResponseEntity<Void> delete(@PathVariable String fileId, HttpServletRequest request) {
        service.delete(LoginUserContext.require(request).id(), fileId);
        return ResponseEntity.noContent().build();
    }
}

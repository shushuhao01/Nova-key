package com.orionkey.controller;

import com.orionkey.common.ApiResponse;
import com.orionkey.constant.ErrorCode;
import com.orionkey.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of(
            ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp"
    );

    private static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
            "image/jpeg", "image/png", "image/gif", "image/webp", "image/bmp"
    );

    /**
     * 文件 Magic Bytes 前缀，用于验证文件真实类型（防止伪造 Content-Type）
     */
    private static final Map<String, byte[][]> MAGIC_BYTES = Map.of(
            ".jpg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            ".jpeg", new byte[][]{{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF}},
            ".png", new byte[][]{{(byte) 0x89, 0x50, 0x4E, 0x47}},
            ".gif", new byte[][]{{0x47, 0x49, 0x46, 0x38}},  // GIF8
            ".webp", new byte[][]{{0x52, 0x49, 0x46, 0x46}}, // RIFF
            ".bmp", new byte[][]{{0x42, 0x4D}}                // BM
    );

    @Value("${upload.path:./uploads}")
    private String uploadPath;

    @Value("${upload.url-prefix:/uploads}")
    private String urlPrefix;

    private Path resolvedUploadDir;

    /**
     * 支付证书存储目录：位于 upload.path 的同级 payment-certs 目录，
     * 不在 /uploads/** 静态映射范围内，防止私钥文件被 HTTP 公开访问。
     */
    private Path resolvedCertDir;

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(uploadPath);
        if (!dir.isAbsolute()) {
            dir = Paths.get(System.getProperty("user.dir")).resolve(uploadPath).normalize();
        }
        this.resolvedUploadDir = dir;
        if (!Files.exists(this.resolvedUploadDir)) {
            Files.createDirectories(this.resolvedUploadDir);
        }
        // 证书目录 = upload.path 的父目录下 payment-certs（如 /www/wwwroot/nova-key/payment-certs）
        Path parent = this.resolvedUploadDir.getParent();
        this.resolvedCertDir = parent != null
                ? parent.resolve("payment-certs")
                : this.resolvedUploadDir.resolveSibling("payment-certs");
        Files.createDirectories(this.resolvedCertDir);
        log.info("Upload directory resolved to: {}", this.resolvedUploadDir);
        log.info("Payment cert directory resolved to: {}", this.resolvedCertDir);
    }

    @PostMapping("/image")
    public ApiResponse<?> uploadImage(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }

        // Validate content type
        String contentType = file.getContentType();
        if (contentType == null || !ALLOWED_CONTENT_TYPES.contains(contentType.toLowerCase())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP");
        }

        String originalFilename = file.getOriginalFilename();
        String extension = "";
        if (originalFilename != null && originalFilename.contains(".")) {
            extension = originalFilename.substring(originalFilename.lastIndexOf(".")).toLowerCase();
        }

        // Validate file extension
        if (extension.isEmpty() || !ALLOWED_EXTENSIONS.contains(extension)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的文件扩展名，仅支持 jpg/png/gif/webp/bmp");
        }

        // Validate Magic Bytes (防止伪造 Content-Type 上传恶意文件)
        if (!verifyMagicBytes(file, extension)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容与扩展名不匹配，疑似伪造文件");
        }

        String filename = UUID.randomUUID() + extension;

        try {
            Path target = resolvedUploadDir.resolve(filename);
            file.transferTo(target.toFile());
            log.info("File uploaded: {}", target);

            String url = urlPrefix + "/" + filename;
            return ApiResponse.success(Map.of("url", url));
        } catch (IOException e) {
            log.error("File upload failed", e);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "文件上传失败");
        }
    }

    /**
     * 上传微信支付证书文件（apiclient_cert.pem / apiclient_key.pem）。
     *
     * 存储位置：{upload.path 父目录}/payment-certs/（如 /www/wwwroot/nova-key/payment-certs），
     * 该目录不在 /uploads/** 静态映射内，私钥文件不会被 HTTP 公开下载。
     *
     * @return { path: 服务器绝对路径, filename: 原文件名 }
     */
    @PostMapping("/cert")
    public ApiResponse<?> uploadCert(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件不能为空");
        }
        String original = file.getOriginalFilename();
        String filename = original != null ? original.replaceAll(".*[/\\\\]", "") : "";
        String lower = filename.toLowerCase();
        if (!(lower.endsWith(".pem") || lower.endsWith(".key"))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅支持 .pem / .key 格式的证书文件");
        }
        if (!filename.matches("[A-Za-z0-9._-]+")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件名不合法");
        }
        // 校验 PEM 格式头，防止上传任意类型文件
        try (InputStream is = file.getInputStream()) {
            byte[] head = is.readNBytes(64);
            String headStr = new String(head, StandardCharsets.US_ASCII);
            if (!headStr.contains("-----BEGIN")) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不是 PEM 格式（缺少 -----BEGIN 头）");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }

        try {
            Path target = resolvedCertDir.resolve(filename);
            // 重复上传同名证书时覆盖旧文件（如重新下载证书后更新）
            file.transferTo(target.toFile());
            restrictCertPermissions(target);
            log.info("Cert uploaded: {}", target);
            return ApiResponse.success(Map.of(
                    "path", target.toString(),
                    "filename", filename));
        } catch (IOException e) {
            log.error("Cert upload failed", e);
            throw new BusinessException(ErrorCode.SERVER_ERROR, "证书上传失败");
        }
    }

    /** 私钥文件权限收紧为仅 owner 可读写（Linux 下生效，防止同机其他用户读取） */
    private void restrictCertPermissions(Path target) {
        try {
            Files.setPosixFilePermissions(target,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException | IOException e) {
            log.warn("Unable to restrict permissions for {}: {}", target, e.getMessage());
        }
    }

    /**
     * 校验文件头部 Magic Bytes 是否与声明的扩展名匹配
     */
    private boolean verifyMagicBytes(MultipartFile file, String extension) {
        byte[][] expected = MAGIC_BYTES.get(extension);
        if (expected == null) return true; // 无规则的扩展名跳过

        try (InputStream is = file.getInputStream()) {
            byte[] header = new byte[8];
            int read = is.read(header);
            if (read < 2) return false;

            for (byte[] magic : expected) {
                if (read >= magic.length && startsWith(header, magic)) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            log.warn("Failed to read file header for magic bytes check", e);
            return false;
        }
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) return false;
        }
        return true;
    }
}

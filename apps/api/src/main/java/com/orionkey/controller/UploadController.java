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
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/upload")
public class UploadController {

    /**
     * 文件真实类型检测（Magic Bytes）。
     * 不再信任用户扩展名/Content-Type，一律按文件内容识别，防止伪造文件，
     * 同时识别 iPhone 默认的 HEIC/HEIF 格式并给出友好提示。
     */
    private static String detectImageType(byte[] h) {
        if (h.length >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return "jpeg";
        }
        if (h.length >= 4 && (h[0] & 0xFF) == 0x89 && h[1] == 0x50 && h[2] == 0x4E && h[3] == 0x47) {
            return "png";
        }
        if (h.length >= 4 && h[0] == 0x47 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x38) {
            return "gif";
        }
        if (h.length >= 12 && h[0] == 0x52 && h[1] == 0x49 && h[2] == 0x46 && h[3] == 0x46
                && h[8] == 0x57 && h[9] == 0x45 && h[10] == 0x42 && h[11] == 0x50) {
            return "webp";
        }
        if (h.length >= 2 && h[0] == 0x42 && h[1] == 0x4D) {
            return "bmp";
        }
        // ISO-BMFF 容器（HEIC/HEIF/AVIF）：第 5-8 字节为 "ftyp"，第 9-12 字节为品牌
        if (h.length >= 12 && h[4] == 0x66 && h[5] == 0x74 && h[6] == 0x79 && h[7] == 0x70) {
            String brand = new String(h, 8, 4, StandardCharsets.US_ASCII);
            if (brand.equals("heic") || brand.equals("heix") || brand.equals("hevc")
                    || brand.equals("heif") || brand.equals("mif1") || brand.equals("msf1")
                    || brand.equals("avif") || brand.equals("avis")) {
                return "heic";
            }
        }
        return "unknown";
    }

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

        // Content-Type 仅做粗校验（允许 image/*），最终以文件内容识别为准
        String contentType = file.getContentType();
        if (contentType != null && !contentType.toLowerCase().startsWith("image/")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的图片格式，仅支持 JPG/PNG/GIF/WebP/BMP");
        }

        // 按文件内容识别真实类型（不信任扩展名与 Content-Type，防止伪造文件）
        byte[] header;
        try (InputStream is = file.getInputStream()) {
            header = is.readNBytes(16);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "读取文件失败");
        }
        String realType = detectImageType(header);
        if ("unknown".equals(realType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "文件内容不是有效的图片格式，请上传 JPG/PNG/GIF/WebP/BMP 图片");
        }
        if ("heic".equals(realType)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST,
                    "图片为 HEIC/HEIF 格式（iPhone 默认拍摄格式），当前不支持直接上传，"
                            + "请先将图片转换为 JPG/PNG 后再上传（iPhone 设置 → 相机 → 格式 → 兼容性最佳可改为 JPEG）");
        }

        // 以真实类型扩展名保存，避免内容与扩展名不一致导致展示异常
        String filename = UUID.randomUUID() + "." + realType;

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
}

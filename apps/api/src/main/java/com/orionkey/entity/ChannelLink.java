package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 渠道链接（引流归因短链）。
 * <p>
 * 用于在官网/第三方站点放置的推广链接，用户点击后经 /c/{code} 归一化写入 ch_ref Cookie，
 * 后续访问/下单携带 channel_code 完成渠道归因。
 * <p>
 * 表结构由 Hibernate ddl-auto=update 自动创建/更新。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "channel_links", indexes = {
        @Index(name = "idx_channel_links_code", columnList = "code", unique = true),
        @Index(name = "idx_channel_links_channel", columnList = "channel")
})
public class ChannelLink extends BaseEntity {

    /** 唯一短码，用于生成 /c/{code} */
    @Column(nullable = false, length = 32)
    private String code;

    /** 备注名称（便于识别该链接用途） */
    @Column(length = 128)
    private String name;

    /** 渠道分组（如：官网、知乎、微博） */
    @Column(length = 64)
    private String channel;

    /** 落地页路径，默认 / */
    @Column(name = "target_path", length = 255)
    private String targetPath;

    /** 备注说明 */
    @Column(length = 512)
    private String remark;

    /** 是否启用 */
    @Column(nullable = false)
    private boolean enabled = true;

    /** 累计点击数 */
    @Column(name = "click_count", nullable = false)
    private long clickCount = 0L;

    /** 累计独立点击（按 IP 去重近似） */
    @Column(name = "unique_click_count", nullable = false)
    private long uniqueClickCount = 0L;
}

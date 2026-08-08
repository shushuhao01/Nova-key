package com.orionkey.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * 系统角色（RBAC）：配置权限码集合，内部员工（User.role=STAFF）通过 role_id 绑定角色。
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "system_roles")
public class Role extends BaseEntity {

    /** 角色编码（唯一，如 SUPER_ADMIN / CUSTOMER_SERVICE） */
    @Column(unique = true, nullable = false)
    private String code;

    /** 角色名称（如 超级管理员 / 客服） */
    @Column(nullable = false)
    private String name;

    /** 角色描述 */
    private String description;

    /** 权限码集合 JSON：["BACKEND_ACCESS","ORDER_MANAGE",...] */
    @Column(columnDefinition = "TEXT")
    private String permissions;

    /** 是否内置角色（1=内置，不可删除/改权限；0=自定义） */
    private int isSystem = 0;
}

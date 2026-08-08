package com.orionkey.constant;

public enum UserRole {
    /** 普通注册用户（前台客户） */
    USER,
    /** 内部员工（客服等，通过 system_roles 配置后台权限） */
    STAFF,
    /** 超级管理员（内置，拥有全部后台权限） */
    ADMIN
}

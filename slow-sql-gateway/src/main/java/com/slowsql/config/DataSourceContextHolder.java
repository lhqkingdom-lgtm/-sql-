package com.slowsql.config;

/**
 * ThreadLocal 持有当前请求的目标实例 ID。
 * Controller 在请求入口 set，finally 中 clear。
 */
public class DataSourceContextHolder {

    private static final ThreadLocal<String> CONTEXT = new ThreadLocal<>();

    private DataSourceContextHolder() {}

    public static void set(String instanceId) {
        CONTEXT.set(instanceId);
    }

    public static String get() {
        return CONTEXT.get();
    }

    public static void clear() {
        CONTEXT.remove();
    }
}

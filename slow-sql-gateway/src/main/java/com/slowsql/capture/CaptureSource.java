package com.slowsql.capture;

import java.util.List;

/** 采集源接口——所有慢SQL采集入口必须实现 */
public interface CaptureSource {
    String name();
    boolean isConfigured();
    List<SlowSqlEvent> collect();
}

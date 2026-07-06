package com.slowsql.config;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * DataSourceManager 单元测试——连真实 localhost MySQL 验证连接池管理逻辑。
 */
class DataSourceManagerTest {

    private static final String HOST = "localhost";
    private static final int PORT = 3306;
    private static final String USER = "root";
    private static final String PASS = "123456";
    private static final String DB = "test_sql";

    private SqlMonitorProperties properties;
    private DataSourceManager manager;

    @BeforeEach
    void setUp() {
        properties = new SqlMonitorProperties();
    }

    // ===== 正常场景 =====

    @Test
    void shouldCreatePoolsForEachInstance() {
        // 2个实例，不同 instanceId，但同一个 MySQL（host:port 相同应复用池）
        SqlMonitorProperties.InstanceConfig inst1 = createInstance("tc-dev-mysql", DB);
        SqlMonitorProperties.InstanceConfig inst2 = createInstance("pay-dev-mysql", DB);
        properties.setInstances(List.of(inst1, inst2));

        manager = new DataSourceManager(properties);
        manager.init();

        assertNotNull(manager.getTemplate("tc-dev-mysql"));
        assertNotNull(manager.getTemplate("pay-dev-mysql"));
        // 同 host:port 应复用连接池
        assertSame(
                ((HikariDataSource) manager.getTemplate("tc-dev-mysql").getDataSource()),
                ((HikariDataSource) manager.getTemplate("pay-dev-mysql").getDataSource()));
    }

    @Test
    void shouldCreateMonitoringPool() {
        SqlMonitorProperties.InstanceConfig inst = createInstance("tc-dev-mysql", DB);
        properties.setInstances(List.of(inst));

        manager = new DataSourceManager(properties);
        manager.init();

        JdbcTemplate monJt = manager.getMonitoringTemplate("tc-dev-mysql");
        assertNotNull(monJt);
        HikariDataSource monDs = (HikariDataSource) monJt.getDataSource();
        assertEquals(3, monDs.getMaximumPoolSize());  // 监测池 max 3
    }

    @Test
    void shouldValidateConnectionSuccessfully() {
        SqlMonitorProperties.InstanceConfig inst = createInstance("tc-dev-mysql", DB);
        properties.setInstances(List.of(inst));

        manager = new DataSourceManager(properties);
        manager.init();

        assertTrue(manager.validateConnection("tc-dev-mysql"));
    }

    // ===== 边界场景 =====

    @Test
    void shouldHandleEmptyInstanceList() {
        properties.setInstances(List.of());

        manager = new DataSourceManager(properties);
        manager.init();

        assertTrue(manager.getReadyInstanceIds().isEmpty());
    }

    @Test
    void shouldThrowForUnknownInstance() {
        properties.setInstances(List.of());

        manager = new DataSourceManager(properties);
        manager.init();

        assertThrows(IllegalArgumentException.class, () ->
                manager.getTemplate("nonexistent"));
    }

    @Test
    void shouldSkipFailedInstanceWithoutCrashing() {
        // 配一个不存在的host，再加一个正常host
        SqlMonitorProperties.InstanceConfig bad = createInstanceRaw("bad-mysql", "192.0.2.1", 3306, USER, PASS, DB);
        SqlMonitorProperties.InstanceConfig good = createInstance("good-mysql", DB);
        properties.setInstances(List.of(bad, good));

        manager = new DataSourceManager(properties);
        manager.init();

        // 好的连接池正常
        assertNotNull(manager.getTemplate("good-mysql"));
    }

    @Test
    void shouldFailValidationForDisconnectedInstance() {
        SqlMonitorProperties.InstanceConfig bad = createInstanceRaw("bad-mysql", "192.0.2.1", 3306, USER, PASS, DB);
        properties.setInstances(List.of(bad));

        manager = new DataSourceManager(properties);
        manager.init();

        // 池创建失败 → getTemplate 抛异常
        assertThrows(IllegalArgumentException.class, () ->
                manager.getTemplate("bad-mysql"));
    }

    // ===== 项目关联 =====

    @Test
    void shouldFindProjectCodeByInstanceId() {
        SqlMonitorProperties.ProjectConfig project = new SqlMonitorProperties.ProjectConfig();
        project.setCode("tongcheng-club");
        project.setInstanceIds(List.of("tc-dev-mysql", "tc-prod-mysql"));
        properties.setProjects(List.of(project));

        SqlMonitorProperties.InstanceConfig inst = createInstance("tc-dev-mysql", DB);
        properties.setInstances(List.of(inst));

        manager = new DataSourceManager(properties);
        manager.init();

        assertEquals("tongcheng-club", manager.findProjectCode("tc-dev-mysql"));
        assertNull(manager.findProjectCode("nonexistent"));
    }

    // ===== 辅助方法 =====

    private SqlMonitorProperties.InstanceConfig createInstance(String id, String db) {
        return createInstanceRaw(id, HOST, PORT, USER, PASS, db);
    }

    private SqlMonitorProperties.InstanceConfig createInstanceRaw(String id, String host, int port,
                                                                    String user, String pass, String db) {
        SqlMonitorProperties.InstanceConfig inst = new SqlMonitorProperties.InstanceConfig();
        inst.setId(id);
        inst.setHost(host);
        inst.setPort(port);
        inst.setUsername(user);
        inst.setPassword(pass);
        inst.setDefaultDatabase(db);
        return inst;
    }
}

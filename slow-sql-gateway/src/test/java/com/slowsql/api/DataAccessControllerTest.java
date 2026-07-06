package com.slowsql.api;

import com.slowsql.config.DataSourceManager;
import com.slowsql.config.DdlCache;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DataAccessControllerTest {

    private static final String TOKEN = "test-token";
    private static final String INSTANCE = "tc-dev-mysql";
    private static final String BASE = "/api/data/" + INSTANCE;

    private MockMvc mvc;
    private DataSourceManager dataSourceManager;
    private DdlCache ddlCache;
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        dataSourceManager = mock(DataSourceManager.class);
        ddlCache = mock(DdlCache.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        when(dataSourceManager.getTemplate(anyString())).thenReturn(jdbcTemplate);

        DataAccessController controller = new DataAccessController(dataSourceManager, ddlCache, TOKEN);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .defaultResponseCharacterEncoding(StandardCharsets.UTF_8)
                .build();
    }

    // ===== 1. GET /ddl =====

    @Test
    void getDdl_shouldReturnDdl() throws Exception {
        when(ddlCache.get(INSTANCE, "orders")).thenReturn(null);
        when(jdbcTemplate.queryForMap("SHOW CREATE TABLE orders"))
                .thenReturn(Map.of("Create Table", "CREATE TABLE orders (id bigint)"));

        mvc.perform(get(BASE + "/ddl?table=orders").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("CREATE TABLE")));
    }

    @Test
    void getDdl_shouldReturnServedByCache() throws Exception {
        when(ddlCache.get(INSTANCE, "orders")).thenReturn("CREATE TABLE orders (id bigint) -- served-by-cache");

        mvc.perform(get(BASE + "/ddl?table=orders").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("served-by-cache")));
        verify(jdbcTemplate, never()).queryForMap(anyString());
    }

    @Test
    void getDdl_shouldRejectIllegalTableName() throws Exception {
        mvc.perform(get(BASE + "/ddl?table=orders%3BDROP").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("错误")));
    }

    @Test
    void getDdl_shouldRejectMissingTableName() throws Exception {
        mvc.perform(get(BASE + "/ddl").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("错误")));
    }

    // ===== 2. POST /explain =====

    @Test
    void explain_shouldReturnPlan() throws Exception {
        when(jdbcTemplate.queryForObject(eq("EXPLAIN FORMAT=JSON SELECT * FROM orders"), eq(String.class)))
                .thenReturn("{\"query_block\":{}}");

        mvc.perform(post(BASE + "/explain").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"SELECT * FROM orders\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("query_block")));
    }

    @Test
    void explain_shouldBlockDangerousSql() throws Exception {
        mvc.perform(post(BASE + "/explain").header("X-Internal-Token", TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sql\":\"DELETE FROM orders\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("错误")));
    }

    // ===== 3. GET /stats =====

    @Test
    void stats_shouldReturnStats() throws Exception {
        when(jdbcTemplate.queryForMap("SHOW TABLE STATUS LIKE 'orders'"))
                .thenReturn(Map.of("Rows", 50000));
        when(jdbcTemplate.queryForList("SHOW INDEX FROM orders"))
                .thenReturn(List.of(Map.of("Key_name", "PRIMARY", "Column_name", "id", "Cardinality", 50000)));

        mvc.perform(get(BASE + "/stats/orders").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("50000")));
    }

    // ===== 4. GET /locks =====

    @Test
    void locks_shouldReturnLockInfo() throws Exception {
        when(jdbcTemplate.queryForList(anyString())).thenReturn(List.of());

        mvc.perform(get(BASE + "/locks").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk());
    }

    // ===== 5. GET /innodb =====

    @Test
    void innodb_shouldReturnStatus() throws Exception {
        when(jdbcTemplate.queryForMap("SHOW ENGINE INNODB STATUS"))
                .thenReturn(Map.of("Status", "InnoDB Status Info..."));

        mvc.perform(get(BASE + "/innodb").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("InnoDB")));
    }

    // ===== 6. GET /variable =====

    @Test
    void variable_shouldReturnAllowedVar() throws Exception {
        when(jdbcTemplate.queryForMap("SHOW GLOBAL VARIABLES LIKE 'innodb_buffer_pool_size'"))
                .thenReturn(Map.of("Value", "134217728"));

        mvc.perform(get(BASE + "/variable?name=innodb_buffer_pool_size").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk());
    }

    @Test
    void variable_shouldRejectDisallowedVar() throws Exception {
        mvc.perform(get(BASE + "/variable?name=datadir").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("错误")));
    }

    // ===== 安全守卫 =====

    @Test
    void shouldReturn403ForWrongToken() throws Exception {
        mvc.perform(get(BASE + "/locks").header("X-Internal-Token", "wrong-token"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturn403ForMissingToken() throws Exception {
        mvc.perform(get(BASE + "/locks"))
                .andExpect(status().isForbidden());
    }

    @Test
    void shouldReturnErrorForUnknownInstance() throws Exception {
        when(dataSourceManager.getTemplate("unknown")).thenThrow(new IllegalArgumentException("not found"));

        mvc.perform(get("/api/data/unknown/locks").header("X-Internal-Token", TOKEN))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("错误")));
    }
}

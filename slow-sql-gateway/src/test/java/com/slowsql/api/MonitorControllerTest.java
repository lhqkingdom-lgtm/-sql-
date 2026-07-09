package com.slowsql.api;

import com.slowsql.capture.CapturedSql;
import com.slowsql.capture.CapturedSqlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MonitorControllerTest {

    private MockMvc mvc;
    private CapturedSqlRepository repository;

    @BeforeEach
    void setUp() {
        repository = mock(CapturedSqlRepository.class);
        MonitorController controller = new MonitorController(repository);
        mvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // ===== 记录列表 =====

    @Test
    void records_shouldReturnList() throws Exception {
        CapturedSql c = new CapturedSql();
        c.setId(1L);
        c.setSqlText("SELECT * FROM orders");
        c.setQueryTimeSec(3.5);
        c.setInstanceId("tc-dev-mysql");
        c.setOccurrenceCount(10);
        c.setSeverity("P2");
        when(repository.findAll(50)).thenReturn(List.of(c));

        mvc.perform(get("/api/monitor/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1))
                .andExpect(jsonPath("$[0].sqlText").value("SELECT * FROM orders"))
                .andExpect(jsonPath("$[0].queryTimeSec").value(3.5))
                .andExpect(jsonPath("$[0].instanceId").value("tc-dev-mysql"))
                .andExpect(jsonPath("$[0].severity").value("P2"));
    }

    @Test
    void records_shouldRespectLimitParam() throws Exception {
        when(repository.findAll(10)).thenReturn(List.of());

        mvc.perform(get("/api/monitor/records?limit=10"))
                .andExpect(status().isOk());
    }

    @Test
    void records_shouldUseDefaultLimit() throws Exception {
        when(repository.findAll(50)).thenReturn(List.of());

        mvc.perform(get("/api/monitor/records"))
                .andExpect(status().isOk());

        verify(repository).findAll(50);
    }

    @Test
    void records_shouldMarkDiagnosed() throws Exception {
        CapturedSql c = new CapturedSql();
        c.setId(1L);
        c.setSqlText("SELECT 1");
        c.setDiagnosisReport("## 报告");
        when(repository.findAll(50)).thenReturn(List.of(c));

        mvc.perform(get("/api/monitor/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].diagnosed").value(true));
    }

    @Test
    void records_shouldMarkNotDiagnosed() throws Exception {
        CapturedSql c = new CapturedSql();
        c.setId(1L);
        c.setSqlText("SELECT 1");
        c.setDiagnosisReport(null);
        when(repository.findAll(50)).thenReturn(List.of(c));

        mvc.perform(get("/api/monitor/records"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].diagnosed").value(false));
    }

    // ===== 删除 =====

    @Test
    void delete_shouldDeleteRecord() throws Exception {
        when(repository.deleteById(1L)).thenReturn(1);

        mvc.perform(post("/api/monitor/records/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void delete_shouldHandleMissingId() throws Exception {
        mvc.perform(post("/api/monitor/records/delete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    // ===== 清空 =====

    @Test
    void clear_shouldDeleteAllRecords() throws Exception {
        when(repository.deleteAll()).thenReturn(1);

        mvc.perform(post("/api/monitor/records/clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}

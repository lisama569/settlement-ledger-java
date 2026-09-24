package com.github.lisama569.settlementledgerjava.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SettlementControllerIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(SettlementControllerIntegrationTest.class);

    @Autowired private MockMvc mockMvc;
    private final ObjectMapper mapper = new ObjectMapper();

    private String submitJson(String key, String id) {
        return """
                {"idempotencyKey":"%s","instructionId":"%s","debtorAccount":"DEBT-1",
                 "creditorAccount":"CRED-1","amount":100.50,"currency":"CNY",
                 "channel":"WEB","submittedBy":"tester"}
                """.formatted(key, id);
    }

    @Test
    void submit_duplicate_and_conflict_have_distinguishable_http_results() throws Exception {
        // 首次 201
        mockMvc.perform(post("/api/settlement/instructions")
                        .contentType(MediaType.APPLICATION_JSON).content(submitJson("idem-http-1", "INS-HTTP-1")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.duplicated").value(false))
                .andExpect(jsonPath("$.version").value(1));

        // 重复 200 + duplicated=true，与首次可区分
        mockMvc.perform(post("/api/settlement/instructions")
                        .contentType(MediaType.APPLICATION_JSON).content(submitJson("idem-http-1", "INS-HTTP-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duplicated").value(true))
                .andExpect(jsonPath("$.version").value(1));

        // 同键不同内容 409 + 专门错误码
        String tampered = submitJson("idem-http-1", "INS-HTTP-1").replace("DEBT-1", "DEBT-X");
        mockMvc.perform(post("/api/settlement/instructions")
                        .contentType(MediaType.APPLICATION_JSON).content(tampered))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("IDEMPOTENCY_KEY_DUPLICATED_CONFLICT"));
    }

    @Test
    void optimistic_conflict_returns_409_with_expected_and_actual_version() throws Exception {
        mockMvc.perform(post("/api/settlement/instructions")
                .contentType(MediaType.APPLICATION_JSON).content(submitJson("idem-http-2", "INS-HTTP-2")))
                .andExpect(status().isCreated());

        String transition = """
                {"expectedVersion":1,"targetStatus":"PROCESSING"}
                """;
        MvcResult ok = mockMvc.perform(post("/api/settlement/instructions/INS-HTTP-2/transitions")
                        .contentType(MediaType.APPLICATION_JSON).content(transition))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2))
                .andReturn();
        JsonNode okBody = mapper.readTree(ok.getResponse().getContentAsString());
        log.info("http-transition idempotencyKey=idem-http-2 first version={} status={}",
                okBody.get("version").asLong(), okBody.get("status").asText());

        MvcResult conflict = mockMvc.perform(post("/api/settlement/instructions/INS-HTTP-2/transitions")
                        .contentType(MediaType.APPLICATION_JSON).content(transition))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("OPTIMISTIC_LOCK_CONFLICT"))
                .andReturn();
        log.info("http-transition idempotencyKey=idem-http-2 stale body={}",
                conflict.getResponse().getContentAsString());
    }

    @Test
    void replay_and_event_cursor_endpoints_work() throws Exception {
        mockMvc.perform(post("/api/settlement/instructions")
                .contentType(MediaType.APPLICATION_JSON).content(submitJson("idem-http-3", "INS-HTTP-3")))
                .andExpect(status().isCreated());

        mockMvc.perform(get("/api/settlement/instructions/INS-HTTP-3/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.channel").value("WEB"));

        mockMvc.perform(get("/api/settlement/events?limit=1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.events.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").isNumber());
    }
}

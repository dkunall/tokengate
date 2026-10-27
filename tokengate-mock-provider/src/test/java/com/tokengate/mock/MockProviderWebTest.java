package com.tokengate.mock;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {"mock.seed=42", "mock.upstreams.alpha.latency-ms=0", "mock.upstreams.alpha.jitter-ms=0",
        "mock.upstreams.alpha.chunk-delay-ms=0"})
@AutoConfigureMockMvc
class MockProviderWebTest {

    private static final String CHAT = """
            {"model":"alpha-small","messages":[{"role":"system","content":"be brief"},{"role":"user","content":"hi <EMAIL_0a1b2c3d>"}]%s}
            """;

    @Autowired
    MockMvc mvc;

    @AfterEach
    void reset() throws Exception {
        mvc.perform(post("/control/reset")).andExpect(status().isOk());
    }

    @Test
    void answersInOpenAiFormatAndRecordsThePrompt() throws Exception {
        call("/alpha/v1/chat/completions", CHAT.formatted(""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.object").value("chat.completion"))
                .andExpect(jsonPath("$.choices[0].message.content").value("[alpha/alpha-small] You asked: hi <EMAIL_0a1b2c3d>"))
                .andExpect(jsonPath("$.usage.total_tokens").isNumber());

        mvc.perform(get("/control/alpha/requests"))
                .andExpect(jsonPath("$[0].contents[1]").value("user: hi <EMAIL_0a1b2c3d>"));
    }

    @Test
    void outageAndInjectedErrorsUseProviderStatusCodes() throws Exception {
        mvc.perform(put("/control/alpha/config").contentType(MediaType.APPLICATION_JSON).content("{\"outage\":true}"))
                .andExpect(jsonPath("$.behaviour.outage").value(true))
                .andExpect(jsonPath("$.behaviour.latencyMs").value(0));
        call("/alpha/v1/chat/completions", CHAT.formatted(""))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error.type").value("server_error"));

        mvc.perform(put("/control/alpha/config").contentType(MediaType.APPLICATION_JSON)
                .content("{\"outage\":false,\"errorRate\":1.0,\"errorStatus\":429}"));
        call("/alpha/v1/chat/completions", CHAT.formatted(""))
                .andExpect(status().isTooManyRequests());
        mvc.perform(get("/control/alpha/config")).andExpect(jsonPath("$.failures").value(2));
    }

    @Test
    void rejectsInvalidConfig() throws Exception {
        mvc.perform(put("/control/alpha/config").contentType(MediaType.APPLICATION_JSON).content("{\"errorRate\":2}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/control/nope/config")).andExpect(status().isNotFound());
    }

    @Test
    void streamsChunksThenUsageThenDone() throws Exception {
        String body = stream(CHAT.formatted(",\"stream\":true,\"stream_options\":{\"include_usage\":true}"));
        assertThat(body).startsWith("data: {").contains("\"role\":\"assistant\"").contains("\"finish_reason\":\"stop\"")
                .contains("\"usage\":{\"prompt_tokens\"").endsWith("data: [DONE]\n\n");
    }

    @Test
    void midStreamFailureCutsTheBodyWithoutDone() throws Exception {
        mvc.perform(put("/control/alpha/config").contentType(MediaType.APPLICATION_JSON).content("{\"failMidStreamAfterChunks\":2}"));
        String body = stream(CHAT.formatted(",\"stream\":true"));
        assertThat(body.split("\n\n")).hasSize(2);
        assertThat(body).doesNotContain("[DONE]").doesNotContain("\"finish_reason\":\"stop\"");
    }

    @Test
    void embeddingsReturnOneVectorPerInput() throws Exception {
        call("/beta/v1/embeddings", "{\"model\":\"beta-embed\",\"input\":[\"a\",\"b\"]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[1].embedding.length()").value(384));
    }

    private String stream(String json) throws Exception {
        return call("/alpha/v1/chat/completions", json).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    private ResultActions call(String path, String json) throws Exception {
        MvcResult started = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(request().asyncStarted()).andReturn();
        return mvc.perform(asyncDispatch(started));
    }
}

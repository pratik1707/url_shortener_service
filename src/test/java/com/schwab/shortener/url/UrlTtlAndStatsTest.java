package com.schwab.shortener.url;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Expiry behaviour and hit counting. Kept separate from the main redirect tests
 * because these are about what happens over time rather than on a single request.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlTtlAndStatsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    // ------------------------------------------------------------------- ttl

    @Test
    @DisplayName("a link with no ttl never expires")
    void noTtlMeansNoExpiry() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/forever"));
        mockMvc.perform(get("/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.expired").value(false));
    }

    @Test
    @DisplayName("a ttl is reported back as an absolute expiry time")
    void ttlIsReportedAsAnAbsoluteTime() throws Exception {
        MvcResult result = createUrl(Map.of("url", "https://example.com/soon", "ttlSeconds", 3600));
        Map<?, ?> body = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertNotNull(body.get("expiresAt"), "the caller should be told when it lapses");
        assertNotNull(body.get("createdAt"));
    }

    @Test
    @DisplayName("a link works right up until its ttl passes, then reports gone")
    void linkWorksUntilTtlPassesThenReportsGone() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/brief", "ttlSeconds", 1));

        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        Thread.sleep(1200);
        mockMvc.perform(get("/" + code)).andExpect(status().isGone());
        // and it stays gone, rather than flapping
        mockMvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("an expired link is still visible in stats")
    void expiredLinkIsStillVisibleInStats() throws Exception {
        // The row is kept rather than deleted, so we can still answer questions about
        // a link after it lapses.
        String code = shorten(Map.of("url", "https://example.com/gone", "ttlSeconds", 1));
        Thread.sleep(1200);

        mockMvc.perform(get("/urls/" + code + "/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expired").value(true))
                .andExpect(jsonPath("$.longUrl").value("https://example.com/gone"));
    }

    @Test
    @DisplayName("a zero or negative ttl is rejected rather than creating a dead link")
    void rejectsNonPositiveTtl() throws Exception {
        mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("url", "https://example.com/z", "ttlSeconds", 0))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("a ttl works on a custom alias too")
    void ttlAppliesToAliasesAsWell() throws Exception {
        shorten(Map.of("url", "https://example.com/aliased", "alias", "briefly", "ttlSeconds", 1));

        mockMvc.perform(get("/briefly")).andExpect(status().isFound());
        Thread.sleep(1200);
        mockMvc.perform(get("/briefly")).andExpect(status().isGone());
    }

    // ----------------------------------------------------------------- stats

    @Test
    @DisplayName("a new link starts with no hits")
    void newLinkStartsWithNoHits() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/fresh"));
        mockMvc.perform(get("/urls/" + code + "/stats"))
                .andExpect(jsonPath("$.hitCount").value(0))
                .andExpect(jsonPath("$.lastAccessedAt").doesNotExist());
    }

    @Test
    @DisplayName("every redirect adds one to the hit count")
    void everyRedirectCountsAsAHit() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/counted"));
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        }
        mockMvc.perform(get("/urls/" + code + "/stats"))
                .andExpect(jsonPath("$.hitCount").value(3));
    }

    @Test
    @DisplayName("following a link records when it was last used")
    void followingALinkRecordsWhenItWasLastUsed() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/seen"));
        mockMvc.perform(get("/" + code));

        MvcResult result = mockMvc.perform(get("/urls/" + code + "/stats"))
                .andExpect(status().isOk()).andReturn();
        Map<?, ?> stats = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        assertNotNull(stats.get("lastAccessedAt"));
    }

    @Test
    @DisplayName("a failed redirect is not counted as a hit")
    void expiredHitsAreNotCounted() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/uncounted", "ttlSeconds", 1));
        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        Thread.sleep(1200);
        mockMvc.perform(get("/" + code)).andExpect(status().isGone());

        // Still 1: the request after expiry served nobody, so it is not traffic.
        mockMvc.perform(get("/urls/" + code + "/stats"))
                .andExpect(jsonPath("$.hitCount").value(1));
    }

    @Test
    @DisplayName("stats for a code that does not exist give 404")
    void statsForUnknownCodeGiveNotFound() throws Exception {
        mockMvc.perform(get("/urls/zzzzzz/stats")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("stats say whether the code was chosen by the caller")
    void statsSayWhetherTheCodeWasChosen() throws Exception {
        shorten(Map.of("url", "https://example.com/mine", "alias", "chosen1"));
        mockMvc.perform(get("/urls/chosen1/stats"))
                .andExpect(jsonPath("$.customAlias").value(true));

        String generated = shorten(Map.of("url", "https://example.com/auto"));
        mockMvc.perform(get("/urls/" + generated + "/stats"))
                .andExpect(jsonPath("$.customAlias").value(false));
    }

    // --------------------------------------------------------------- helpers

    private MvcResult createUrl(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
    }

    private String shorten(Map<String, Object> body) throws Exception {
        MvcResult result = createUrl(body);
        Map<?, ?> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) response.get("shortCode");
    }
}

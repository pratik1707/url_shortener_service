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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full HTTP round trips against the real application context and database.
 * These are the tests that prove a short URL actually works end to end.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class UrlEndToEndTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("shortening a url then following the code redirects to the original")
    void shortenThenFollowRedirectsToTheOriginal() throws Exception {
        String target = "https://www.example.com/some/very/long/path?with=query";
        String code = shorten(Map.of("url", target));

        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", target));
    }

    @Test
    @DisplayName("an unknown code gives 404, not a redirect to nowhere")
    void unknownCodeGivesNotFound() throws Exception {
        mockMvc.perform(get("/zzzzzz"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("the console page is served, not treated as a short code")
    void consolePageIsNotTreatedAsACode() throws Exception {
        mockMvc.perform(get("/index.html"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a caller-chosen alias is used as the code")
    void callerChosenAliasIsUsed() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/docs", "alias", "mydocs1"));
        assertEquals("mydocs1", code);

        mockMvc.perform(get("/mydocs1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/docs"));
    }

    @Test
    @DisplayName("asking for an alias someone already has is a conflict, not a silent rename")
    void duplicateAliasIsAConflict() throws Exception {
        shorten(Map.of("url", "https://example.com/first", "alias", "taken01"));

        mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("url", "https://example.com/second", "alias", "taken01"))))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("an alias the same length as a generated code is rejected")
    void aliasCannotBeSixCharacters() throws Exception {
        // Generated codes are exactly 6 characters. A 6-character alias would let a
        // caller squat on a code the counter has not reached yet, and the counter path
        // does not check before writing.
        mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("url", "https://example.com/x", "alias", "sixchr"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("an expired link reports gone, which is different from never existing")
    void expiredLinkReportsGone() throws Exception {
        // One second of life, then we wait it out. 410 tells the caller the link was
        // real and has lapsed; 404 would wrongly suggest it never existed.
        String code = shorten(Map.of("url", "https://example.com/ephemeral", "ttlSeconds", 1));

        mockMvc.perform(get("/" + code)).andExpect(status().isFound());
        Thread.sleep(1200);
        mockMvc.perform(get("/" + code)).andExpect(status().isGone());
    }

    @Test
    @DisplayName("the same url submitted twice gets two different codes")
    void sameUrlTwiceGetsTwoCodes() throws Exception {
        String first = shorten(Map.of("url", "https://example.com/same"));
        String second = shorten(Map.of("url", "https://example.com/same"));
        assertNotEquals(first, second);
    }

    @Test
    @DisplayName("a url that is not http or https is rejected before anything is stored")
    void rejectsNonHttpUrls() throws Exception {
        mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", "javascript:alert(1)"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("the redirect tells browsers not to cache it")
    void redirectIsNotCacheable() throws Exception {
        String code = shorten(Map.of("url", "https://example.com/nocache"));
        mockMvc.perform(get("/" + code))
                .andExpect(status().isFound())
                .andExpect(header().string("Cache-Control", "no-store"));
    }

    @Test
    @DisplayName("the response says which strategy minted the code")
    void responseReportsTheStrategy() throws Exception {
        mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("url", "https://example.com/s"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.strategy").value("counter-base62-permuted"));
    }

    private String shorten(Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andReturn();
        Map<?, ?> response = objectMapper.readValue(result.getResponse().getContentAsString(), Map.class);
        return (String) response.get("shortCode");
    }
}

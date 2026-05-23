package com.isakatirci.MVP.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TestController.class)
@Import(TestController.UrlService.class)
class TestControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Should return country search Thymeleaf page")
    void shouldReturnCountrySearchPage() throws Exception {
        mockMvc.perform(get("/country-search"))
                .andExpect(status().isOk())
                .andExpect(view().name("country-search"))
                .andExpect(model().attributeExists("countries"));
    }

    @Test
    @DisplayName("Should redirect for a valid shortCode TR")
    void shouldRedirectForValidShortCode() throws Exception {
        mockMvc.perform(get("/TR"))
                .andExpect(status().isMovedPermanently())
                .andExpect(header().string("Location", "https://en.wikipedia.org/wiki/Turkey"))
                .andExpect(header().string("Cache-Control", "public, max-age=86400"));
    }

    @Test
    @DisplayName("Should return 404 for an invalid shortCode")
    void shouldReturn404ForInvalidShortCode() throws Exception {
        mockMvc.perform(get("/XX"))
                .andExpect(status().isNotFound());
    }
}

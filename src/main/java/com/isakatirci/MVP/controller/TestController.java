package com.isakatirci.MVP.controller;

import com.isakatirci.MVP.service.UrlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;

@Controller
@RequiredArgsConstructor
@Slf4j
public class TestController {

    private final UrlService urlService;

    @GetMapping("/country-search")
    public String countrySearchPage(Model model) {
        model.addAttribute("countries", UrlService.COUNTRIES);
        return "country-search";
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        log.info("Redirecting for shortCode: {}", shortCode);
        String originalUrl = urlService.resolve(shortCode);
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header("Cache-Control", "public, max-age=86400")
                .location(URI.create(originalUrl))
                .build();
    }
}

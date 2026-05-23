package com.isakatirci.MVP.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

import java.net.URI;
import java.util.List;

@Controller
@RequiredArgsConstructor
@Slf4j
public class TestController {

    private final UrlService urlService;

    public record Country(String name, String shortCode, String redirectUrl) {}

    @GetMapping("/country-search")
    public String countrySearchPage(Model model) {
        model.addAttribute("countries", UrlService.COUNTRIES);
        return "country-search";
    }

    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        log.info("Redirecting for shortCode: {}", shortCode);
        String originalUrl = urlService.resolve(shortCode);
        if (originalUrl == null || originalUrl.isEmpty()) {
            log.warn("ShortCode not resolved: {}", shortCode);
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.status(HttpStatus.MOVED_PERMANENTLY)
                .header("Cache-Control", "public, max-age=86400")
                .location(URI.create(originalUrl))
                .build();
    }

    @org.springframework.stereotype.Service
    public static class UrlService {
        public static final List<Country> COUNTRIES = List.of(
            new Country("Turkey", "TR", "https://en.wikipedia.org/wiki/Turkey"),
            new Country("United States", "US", "https://en.wikipedia.org/wiki/United_States"),
            new Country("Germany", "DE", "https://en.wikipedia.org/wiki/Germany"),
            new Country("United Kingdom", "GB", "https://en.wikipedia.org/wiki/United_Kingdom"),
            new Country("France", "FR", "https://en.wikipedia.org/wiki/France"),
            new Country("Japan", "JP", "https://en.wikipedia.org/wiki/Japan"),
            new Country("Canada", "CA", "https://en.wikipedia.org/wiki/Canada"),
            new Country("Australia", "AU", "https://en.wikipedia.org/wiki/Australia"),
            new Country("Italy", "IT", "https://en.wikipedia.org/wiki/Italy"),
            new Country("Spain", "ES", "https://en.wikipedia.org/wiki/Spain"),
            new Country("Netherlands", "NL", "https://en.wikipedia.org/wiki/Netherlands"),
            new Country("Switzerland", "CH", "https://en.wikipedia.org/wiki/Switzerland"),
            new Country("Sweden", "SE", "https://en.wikipedia.org/wiki/Sweden"),
            new Country("Norway", "NO", "https://en.wikipedia.org/wiki/Norway"),
            new Country("Denmark", "DK", "https://en.wikipedia.org/wiki/Denmark"),
            new Country("Finland", "FI", "https://en.wikipedia.org/wiki/Finland")
        );

        public String resolve(String shortCode) {
            return COUNTRIES.stream()
                .filter(c -> c.shortCode().equalsIgnoreCase(shortCode))
                .map(Country::redirectUrl)
                .findFirst()
                .orElse(null);
        }
    }

}

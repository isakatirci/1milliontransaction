package com.isakatirci.MVP.service;

import com.isakatirci.MVP.exception.NotFoundException;
import com.isakatirci.MVP.repository.UrlRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class UrlService {

    private final UrlRepository urlRepository;

    public record Country(String name, String shortCode, String redirectUrl) {
    }

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

    @Cacheable(value = "urls", key = "#shortCode")
    public String resolve(String shortCode) {
        log.info("Resolving shortCode from database: {}", shortCode);
        return urlRepository.findByShortCode(shortCode.toUpperCase())
                .orElseThrow(() -> new NotFoundException("URL not found"))
                .getOriginalUrl();
    }
}

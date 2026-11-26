package com.glucospectra.backend.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows the Vercel-hosted frontend to POST captures to this backend
 * cross-origin. Without this, the browser blocks the request before the
 * Authorization header is even sent.
 *
 * Patterns cover the two Vercel domains the dashboard is served from:
 *   - production:        https://glucose-dashboard-mu.vercel.app
 *   - preview / git PRs: https://glucose-dashboard-*.vercel.app
 *
 * localhost:5173 and localhost:4173 are allowed so you can also point the
 * Vite dev server + preview at the live backend during development.
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns(
                        "https://glucose-dashboard-*.vercel.app",
                        "http://localhost:5173",
                        "http://localhost:4173"
                )
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("Content-Type")
                .allowCredentials(false)
                .maxAge(3600);
    }
}

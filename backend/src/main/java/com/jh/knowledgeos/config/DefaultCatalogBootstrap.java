package com.jh.knowledgeos.config;

import com.jh.knowledgeos.common.DefaultCatalogLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Loads the default catalogs once at startup.
 *
 * <p>Doing it here rather than lazily on first use is deliberate: the log line then shows
 * up among the startup output, so "which copy did it actually read" is answerable by
 * looking at the boot log instead of waiting for someone to open the right page.
 *
 * <p>Never throws. A missing directory, a read-only mount or a malformed file all end with
 * the built-in defaults, and a platform that refuses to start because a default config
 * file is unreadable would be worse than one running on the values it has always had.
 */
@Component
@Order(0)
public class DefaultCatalogBootstrap implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DefaultCatalogBootstrap.class);

    @Override
    public void run(ApplicationArguments args) {
        try {
            DefaultCatalogLoader.prepare();
        } catch (RuntimeException exc) {
            log.warn("default catalogs not loaded, built-in constants stay in effect: {}", exc.toString());
        }
    }
}

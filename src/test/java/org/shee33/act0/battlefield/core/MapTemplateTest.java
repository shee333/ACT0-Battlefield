package org.shee33.act0.battlefield.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MapTemplateTest {

    private static final Path VALID_PATH = Path.of("data/maps/conviction");
    private static final Instant NOW = Instant.parse("2026-01-15T10:00:00Z");

    @Test
    void testValidTemplate() {
        MapTemplate template = new MapTemplate("conviction_64", VALID_PATH, NOW);
        assertDoesNotThrow(() -> {
            // already constructed; re-check via accessor
            assertTrue(template.name().equals("conviction_64"));
        });
    }

    @Test
    void testBlankName() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapTemplate("", VALID_PATH, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new MapTemplate("   ", VALID_PATH, NOW));
    }

    @Test
    void testInvalidNameChars() {
        assertThrows(IllegalArgumentException.class,
                () -> new MapTemplate("has space", VALID_PATH, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new MapTemplate("special!char", VALID_PATH, NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new MapTemplate("中文名", VALID_PATH, NOW));
    }

    @Test
    void testNullFields() {
        assertThrows(NullPointerException.class,
                () -> new MapTemplate(null, VALID_PATH, NOW));
        assertThrows(NullPointerException.class,
                () -> new MapTemplate("valid_name", null, NOW));
        assertThrows(NullPointerException.class,
                () -> new MapTemplate("valid_name", VALID_PATH, null));
    }

    @Test
    void testToString() {
        MapTemplate template = new MapTemplate("siege_of_shanghai", VALID_PATH, NOW);
        String s = template.toString();
        assertTrue(s.contains("siege_of_shanghai"),
                "toString should contain name, got: " + s);
    }
}
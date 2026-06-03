package com.deploymate.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class TagGeneratorServiceTest {

    private TagGeneratorService svc;

    @BeforeEach
    void setUp() {
        svc = new TagGeneratorService();
    }

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
        // RC increment
        "v1.0.0rc1, v1.0.0rc2",
        "v1.0.0rc9, v1.0.0rc10",
        "v2.3.1rc15, v2.3.1rc16",
        "v1.0.0RC1, v1.0.0rc2",    // case-insensitive
        // Staging with number
        "v1.0.0-staging1, v1.0.0-staging2",
        "v1.0.0staging3, v1.0.0staging4",
        // Clean semver → bump patch + rc1
        "v1.0.0, v1.0.1rc1",
        "v2.3.4, v2.3.5rc1",
        "1.2.3, 1.2.4rc1",          // no v prefix
    })
    void computeNextTag_appliesCorrectRule(String lastTag, String expected) {
        assertThat(svc.computeNextTag(lastTag)).isEqualTo(expected);
    }

    @Test
    void computeNextTag_stagingWithoutNumber_appendsTwo() {
        assertThat(svc.computeNextTag("v1.0.0-staging")).isEqualTo("v1.0.0-staging2");
    }

    @Test
    void computeNextTag_fallback_appendsRc1() {
        assertThat(svc.computeNextTag("my-custom-tag")).isEqualTo("my-custom-tagrc1");
    }

    @Test
    void computeNextTag_null_returnsDefault() {
        assertThat(svc.computeNextTag(null)).isEqualTo("v1.0.0rc1");
    }

    @Test
    void computeNextTag_blank_returnsDefault() {
        assertThat(svc.computeNextTag("  ")).isEqualTo("v1.0.0rc1");
    }
}

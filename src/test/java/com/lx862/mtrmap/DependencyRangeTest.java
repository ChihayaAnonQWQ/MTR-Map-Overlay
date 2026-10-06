package com.lx862.mtrmap;

import org.apache.maven.artifact.versioning.DefaultArtifactVersion;
import org.apache.maven.artifact.versioning.VersionRange;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the dependency ranges of the built {@code mods.toml}.
 *
 * <p>Forge refuses to load a mod whose <em>optional</em> dependency is present
 * but outside its declared range. The upstream MTR 4 branch pinned Xaero's World
 * Map to {@code [1.45.0,)}, which hard-blocked every 1.44.x user even though the
 * integration works there - so the ranges for the optional map integrations are
 * asserted here against the versions they were verified with, using Maven's
 * {@code VersionRange} (the same class Forge uses).</p>
 */
class DependencyRangeTest {

    /** Reads the processed mods.toml from the classpath (placeholders already expanded). */
    private static Map<String, String> dependencyRanges() throws IOException {
        final Map<String, String> ranges = new LinkedHashMap<>();
        try (InputStream in = DependencyRangeTest.class.getResourceAsStream("/META-INF/mods.toml")) {
            assertNotNull(in, "processed META-INF/mods.toml must be on the test classpath");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String pendingModId = null;
                String line;
                while ((line = reader.readLine()) != null) {
                    final String trimmed = line.trim();
                    if (trimmed.startsWith("modId = ")) {
                        pendingModId = unquote(trimmed.substring("modId = ".length()));
                    } else if (trimmed.startsWith("versionRange = ") && pendingModId != null) {
                        ranges.put(pendingModId, unquote(trimmed.substring("versionRange = ".length())));
                        pendingModId = null;
                    }
                }
            }
        }
        return ranges;
    }

    private static String unquote(String value) {
        final String trimmed = value.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static void assertAccepts(String range, String version) throws Exception {
        assertTrue(VersionRange.createFromVersionSpec(range)
                        .containsVersion(new DefaultArtifactVersion(version)),
                "range " + range + " must accept " + version);
    }

    private static void assertRejects(String range, String version) throws Exception {
        assertFalse(VersionRange.createFromVersionSpec(range)
                        .containsVersion(new DefaultArtifactVersion(version)),
                "range " + range + " must reject " + version);
    }

    @Test
    void xaeroWorldMapRangeCoversEveryVerifiedVersion() throws Exception {
        final String range = dependencyRanges().get("xaeroworldmap");
        assertNotNull(range, "xaeroworldmap dependency missing from mods.toml");
        // Verified API-identical (GuiMap fields, MapProcessor -> MapWorld ->
        // MapDimension chain): 1.40.11, 1.44.2, 1.45.0. 1.44.2 is what the
        // reported load failure ran, and 1.45.0 is what upstream compiled against.
        assertAccepts(range, "1.40.11");
        assertAccepts(range, "1.44.2");
        assertAccepts(range, "1.45.0");
        assertAccepts(range, "1.47.0");
        assertRejects(range, "1.39.9");
    }

    @Test
    void optionalJourneyMapRangeToleratesJourneyMap6ButNot5() throws Exception {
        final String range = dependencyRanges().get("journeymap");
        assertNotNull(range, "journeymap dependency missing from mods.toml");
        assertAccepts(range, "1.20.1-6.0.6");
        // JourneyMap 5 has no v2 API; the integration disables itself at runtime,
        // so the range must not turn that into a mod-load failure.
        assertRejects(range, "1.20.1-5.9.18");
    }

    @Test
    void mtrRangeAcceptsMtr3AndRejectsMtr4() throws Exception {
        final String range = dependencyRanges().get("mtr");
        assertNotNull(range, "mtr dependency missing from mods.toml");
        assertAccepts(range, "1.20.1-3.2.2-hotfix-2");
        // Third-party MTR 3 forks keep the 3.x data model and are accepted.
        assertAccepts(range, "1.20.1-3.6.3");
        // MTR 4 moved to org.mtr.core and must never be accepted.
        assertRejects(range, "FORGE-4.0.5+1.20.1");
        assertRejects(range, "1.20.1-4.0.0");
    }

    @Test
    void minecraftRangeIsPinnedToTheSupportedVersion() throws Exception {
        final String range = dependencyRanges().get("minecraft");
        assertNotNull(range, "minecraft dependency missing from mods.toml");
        assertAccepts(range, "1.20.1");
        assertRejects(range, "1.20.4");
    }
}

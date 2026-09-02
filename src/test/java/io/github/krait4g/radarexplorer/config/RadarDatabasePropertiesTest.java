package io.github.krait4g.radarexplorer.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RadarDatabasePropertiesTest {

    @Test
    void defaultsToAnEmbeddedSyntheticDemoWithoutStoredSecrets() {
        RadarDatabaseProperties properties = new RadarDatabaseProperties();

        assertThat(properties.getJdbcUrl()).startsWith("jdbc:h2:mem:");
        assertThat(properties.getUsername()).isEqualTo("sa");
        assertThat(properties.getPassword()).isEmpty();
        assertThat(properties.isDemoEnabled()).isTrue();
        assertThat(properties.isDemo()).isTrue();
        assertThat(properties.resolvedDisplayLabel()).isEqualTo("Synthetic demo");
    }

    @Test
    void externalJdbcUrlNeverQualifiesAsDemo() {
        RadarDatabaseProperties properties = new RadarDatabaseProperties();
        properties.setJdbcUrl("jdbc:postgresql://db.example.invalid:5432/radar_demo");
        properties.setDisplayLabel("External read-only database");

        assertThat(properties.isEmbeddedDemoDatabase()).isFalse();
        assertThat(properties.isDemo()).isFalse();
        assertThat(properties.resolvedDisplayLabel()).isEqualTo("External read-only database");
    }
}

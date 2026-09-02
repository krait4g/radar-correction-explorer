package io.github.krait4g.radarexplorer.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "radar.db")
public class RadarDatabaseProperties {

    @NotBlank
    private String jdbcUrl = "jdbc:h2:mem:radar_demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";

    @NotBlank
    private String username = "sa";

    private String password = "";
    private String displayLabel = "Synthetic demo";
    private boolean demoEnabled = true;

    public String getJdbcUrl() {
        return jdbcUrl;
    }

    public void setJdbcUrl(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getDisplayLabel() {
        return displayLabel;
    }

    public void setDisplayLabel(String displayLabel) {
        this.displayLabel = displayLabel;
    }

    public boolean isDemoEnabled() {
        return demoEnabled;
    }

    public void setDemoEnabled(boolean demoEnabled) {
        this.demoEnabled = demoEnabled;
    }

    public boolean isEmbeddedDemoDatabase() {
        return jdbcUrl != null && jdbcUrl.trim().toLowerCase(java.util.Locale.ROOT).startsWith("jdbc:h2:mem:");
    }

    public boolean isDemo() {
        return demoEnabled && isEmbeddedDemoDatabase();
    }

    public String resolvedDisplayLabel() {
        return displayLabel == null || displayLabel.isBlank()
                ? (isDemo() ? "Synthetic demo" : "Configured datasource")
                : displayLabel.trim();
    }
}

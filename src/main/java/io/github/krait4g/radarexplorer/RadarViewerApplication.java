package io.github.krait4g.radarexplorer;

import io.github.krait4g.radarexplorer.config.ViewerProperties;
import io.github.krait4g.radarexplorer.config.RadarDatabaseProperties;
import io.github.krait4g.radarexplorer.config.LocalOnlyServerGuard;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({ViewerProperties.class, RadarDatabaseProperties.class})
public class RadarViewerApplication {

    public static void main(String[] args) {
        SpringApplication application = new SpringApplication(RadarViewerApplication.class);
        application.addInitializers(new LocalOnlyServerGuard());
        application.run(args);
    }
}

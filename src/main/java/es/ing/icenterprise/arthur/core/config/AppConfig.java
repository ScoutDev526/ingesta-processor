package es.ing.icenterprise.arthur.core.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(SharepointProperties.class)
public class AppConfig {
    // Bean definitions if needed beyond component scanning
}

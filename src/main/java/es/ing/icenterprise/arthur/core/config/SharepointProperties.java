package es.ing.icenterprise.arthur.core.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ingesta.sharepoint")
public record SharepointProperties(
        String tenantId,
        String clientId,
        String clientSecret
) {}

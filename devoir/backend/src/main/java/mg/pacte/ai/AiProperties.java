package mg.pacte.ai;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("pacte.ai")
public record AiProperties(String apiKey, String model, URI endpoint, int timeoutSeconds) {
    public boolean configured() { return apiKey != null && !apiKey.isBlank(); }
}

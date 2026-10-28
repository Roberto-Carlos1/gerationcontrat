package mg.pacte.ai;

import java.net.URI;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

@ConfigurationProperties("pacte.ai")
public record AiProperties(String provider, String apiKey, String model, URI endpoint, int timeoutSeconds) {
    @ConstructorBinding
    public AiProperties { }

    public AiProperties(String apiKey, String model, URI endpoint, int timeoutSeconds) {
        this("openai", apiKey, model, endpoint, timeoutSeconds);
    }
    public boolean local() { return "ollama".equalsIgnoreCase(provider); }
    public String providerName() { return local() ? "Ollama (local)" : "OpenAI"; }
    public boolean configured() { return local() || (apiKey != null && !apiKey.isBlank()); }
}

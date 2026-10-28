package mg.pacte.config;

import java.time.Clock;
import java.time.ZoneId;
import mg.pacte.service.RegistryService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AppConfiguration {
    @Bean
    Clock clock(@Value("${pacte.timezone}") String timezone) { return Clock.system(ZoneId.of(timezone)); }

    @Bean
    ApplicationRunner initialize(RegistryService service, @Value("${pacte.demo-enabled}") boolean enabled) {
        return args -> service.initialize(enabled);
    }
}

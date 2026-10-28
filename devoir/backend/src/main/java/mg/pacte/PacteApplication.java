package mg.pacte;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@org.springframework.scheduling.annotation.EnableScheduling
@SpringBootApplication
public class PacteApplication {
    public static void main(String[] args) {
        SpringApplication.run(PacteApplication.class, args);
    }
}

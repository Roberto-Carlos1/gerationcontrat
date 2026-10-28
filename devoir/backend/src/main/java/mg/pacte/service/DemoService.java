package mg.pacte.service;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import mg.pacte.model.Registry.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

@Service
public class DemoService {
    private final JsonMapper mapper;
    private final Clock clock;
    public DemoService(JsonMapper mapper, Clock clock) { this.mapper = mapper; this.clock = clock; }

    public Data data() {
        try (var source = new ClassPathResource("seed.json").getInputStream()) {
            Data seed = mapper.readValue(source, Data.class);
            int[][] offsets = {{-300,35},{-330,50},{-200,120},{-350,20},{-150,180}};
            LocalDate today = LocalDate.now(clock);
            var contracts = new ArrayList<Contract>();
            for (int i=0; i<seed.contracts().size(); i++) {
                var c = seed.contracts().get(i);
                contracts.add(new Contract(c.id(),c.name(),c.supplier(),c.category(),c.amount(),c.frequency(),
                        today.plusDays(offsets[i][0]),today.plusDays(offsets[i][1]),c.notice(),c.renew(),
                        c.owner(),c.status(),c.penalty(),c.notes()));
            }
            return new Data(contracts, seed.cases(), seed.done(), seed.analysis());
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de lire les données de démonstration.", e);
        }
    }
}

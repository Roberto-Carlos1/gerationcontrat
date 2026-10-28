package mg.pacte.service;

import java.time.Clock;
import java.time.LocalDate;
import mg.pacte.model.Registry.*;
import mg.pacte.repository.RegistryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class RegistryService {
    private final RegistryRepository repository;
    private final RegistryValidator validator;
    private final DemoService demo;
    private final Clock clock;

    public RegistryService(RegistryRepository repository, RegistryValidator validator, DemoService demo, Clock clock) {
        this.repository=repository; this.validator=validator; this.demo=demo; this.clock=clock;
    }

    @Transactional
    public void initialize(boolean demoEnabled) {
        repository.lockRevision();
        if (repository.initialized()) return;
        if (demoEnabled) {
            Data data=demo.data();
            validator.validate(data);
            repository.replace(data);
        }
        repository.markInitialized();
    }

    @Transactional
    public Snapshot read() {
        long revision=repository.lockRevision();
        return new Snapshot(revision, normalize(repository.read()));
    }

    @Transactional
    public Snapshot replace(Snapshot request) {
        validator.validate(request.data());
        long revision=repository.lockRevision();
        if (revision != request.revision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Les données ont changé dans une autre fenêtre. Elles ont été rechargées ; recommencez votre modification.");
        }
        Data data=normalize(request.data());
        repository.replace(data);
        return new Snapshot(revision+1, data);
    }

    private Data normalize(Data data) {
        LocalDate today=LocalDate.now(clock);
        var contracts=data.contracts().stream().map(c ->
                c.status().equals("Actif") && c.end()!=null && c.end().isBefore(today) ? c.withStatus("Terminé") : c).toList();
        return new Data(contracts,data.cases(),data.done(),data.analysis());
    }
}

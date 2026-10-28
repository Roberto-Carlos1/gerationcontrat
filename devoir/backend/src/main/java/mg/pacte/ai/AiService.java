package mg.pacte.ai;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.Semaphore;
import mg.pacte.model.Registry.Analysis;
import mg.pacte.service.RegistryService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AiService {
    private final RegistryService registry;
    private final OpenAiClient client;
    private final AiProperties properties;
    private final Clock clock;
    private final Semaphore slots=new Semaphore(2);
    private long windowStart;
    private int requests;

    public AiService(RegistryService registry,OpenAiClient client,AiProperties properties,Clock clock) {
        this.registry=registry;this.client=client;this.properties=properties;this.clock=clock;
    }

    public AiModels.Status status() {
        return new AiModels.Status(properties.configured(),properties.providerName(),properties.model(),40000);
    }

    public Analysis analyze(AiModels.Request request) {
        if(!properties.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"L’IA n’est pas configurée : renseignez OPENAI_API_KEY ou activez Ollama sur le serveur.");
        // Le verrou du registre est relâché avant l'appel externe.
        var contract=registry.read().data().contracts().stream().filter(c->c.id().equals(request.contractId())).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"Contrat introuvable. Rechargez la page."));
        if(!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Deux analyses sont déjà en cours. Réessayez dans un instant.");
        try {
            acquireQuota();
            var output=client.analyze(request.text());
            var details=new AiModels.Details(properties.providerName(),properties.model(),output.summary(),output.riskLevel(),output.risks(),output.recommendations());
            return new Analysis(contract.name(),ZonedDateTime.now(clock).format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")),
                    output.groups(),false,details);
        } finally { slots.release(); }
    }

    private synchronized void acquireQuota() {
        long now=clock.millis();
        if(now-windowStart>=60000) { windowStart=now;requests=0; }
        if(requests>=6) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Limite locale de six analyses par minute atteinte. Patientez une minute.");
        requests++;
    }
}

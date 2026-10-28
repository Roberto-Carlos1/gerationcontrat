package mg.pacte.ai;

import jakarta.validation.Valid;
import mg.pacte.model.Registry.Analysis;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api/ai",produces=MediaType.APPLICATION_JSON_VALUE)
public class AiController {
    private final AiService service;
    public AiController(AiService service) { this.service=service; }

    @GetMapping("/status") public AiModels.Status status() { return service.status(); }
    @PostMapping(value="/analyze",consumes=MediaType.APPLICATION_JSON_VALUE)
    public Analysis analyze(@Valid @RequestBody AiModels.Request request) { return service.analyze(request); }
}

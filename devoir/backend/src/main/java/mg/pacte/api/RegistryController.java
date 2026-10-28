package mg.pacte.api;

import jakarta.validation.Valid;
import mg.pacte.model.Registry.*;
import mg.pacte.service.DemoService;
import mg.pacte.service.RegistryService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping(value="/api", produces=MediaType.APPLICATION_JSON_VALUE)
public class RegistryController {
    private final RegistryService service;
    private final DemoService demo;
    public RegistryController(RegistryService service, DemoService demo) { this.service=service; this.demo=demo; }

    @GetMapping("/state")
    public Snapshot read() { return service.read(); }

    @PutMapping(value="/state", consumes=MediaType.APPLICATION_JSON_VALUE)
    public Snapshot replace(@Valid @RequestBody Snapshot snapshot) { return service.replace(snapshot); }

    @GetMapping("/demo")
    public Data demo() { return demo.data(); }
}

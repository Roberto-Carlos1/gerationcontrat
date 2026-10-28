package mg.pacte.backup;
import mg.pacte.model.Registry.Snapshot;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api/backups/full")
public class BackupController {
    private final BackupService service;
    public BackupController(BackupService service){this.service=service;}
    @GetMapping public ResponseEntity<byte[]> export(){return ResponseEntity.ok().contentType(MediaType.parseMediaType("application/zip"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\"pacte-complet.zip\"").body(service.export());}
    @PostMapping(consumes="application/zip") public Snapshot restore(@RequestParam long revision,@RequestBody byte[] bytes){return service.restore(bytes,revision);}
}

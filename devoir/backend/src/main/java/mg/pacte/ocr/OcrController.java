package mg.pacte.ocr;
import java.util.Map;
import java.util.UUID;
import mg.pacte.document.DocumentService;
import org.springframework.web.bind.annotation.*;
@RestController
@RequestMapping("/api")
public class OcrController {
    private final OcrService service;
    public OcrController(OcrService service){this.service=service;}
    @GetMapping("/ocr/status") public Map<String,Object> status(){return service.status();}
    @PostMapping("/documents/{id}/ocr") public DocumentService.Imported recognize(@PathVariable UUID id){return service.recognize(id);}
}

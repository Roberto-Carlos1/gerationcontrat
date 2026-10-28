package mg.pacte.document;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class DocumentController {
    private final DocumentService service;
    public DocumentController(DocumentService service) { this.service=service; }

    @PostMapping(value="/contracts/{contract}/documents",consumes=MediaType.APPLICATION_PDF_VALUE,produces=MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocumentService.Imported upload(@PathVariable String contract,@RequestParam String filename,@RequestBody byte[] content) {
        return service.upload(contract,filename,content);
    }
    @GetMapping("/contracts/{contract}/documents")
    public List<DocumentRepository.Metadata> list(@PathVariable String contract) { return service.list(contract); }

    @GetMapping("/documents/{id}/text")
    public Map<String,Object> text(@PathVariable UUID id) {
        var document=service.get(id);return Map.of("document",document.metadata(),"text",document.text());
    }
    @GetMapping("/documents/{id}")
    public ResponseEntity<byte[]> download(@PathVariable UUID id) {
        var document=service.get(id);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,ContentDisposition.attachment().filename(document.metadata().filename(),StandardCharsets.UTF_8).build().toString())
                .body(document.content());
    }
}

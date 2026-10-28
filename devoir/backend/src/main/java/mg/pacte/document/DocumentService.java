package mg.pacte.document;

import java.time.Clock;
import java.util.List;
import java.util.UUID;
import mg.pacte.repository.RegistryRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

@Service
public class DocumentService {
    public record Imported(DocumentRepository.Metadata document,String text,String warning) {}
    private final DocumentRepository documents;
    private final RegistryRepository registry;
    private final PdfExtractor extractor;
    private final TransactionTemplate transactions;
    private final Clock clock;
    public DocumentService(DocumentRepository documents,RegistryRepository registry,PdfExtractor extractor,TransactionTemplate transactions,Clock clock) {
        this.documents=documents;this.registry=registry;this.extractor=extractor;this.transactions=transactions;this.clock=clock;
    }
    public Imported upload(String contract,String name,byte[] bytes) {
        checkContract(contract);
        if(name==null || name.isBlank() || name.length()>200 || name.chars().anyMatch(Character::isISOControl)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Nom de fichier invalide (200 caractères maximum).");
        }
        String cleaned=name.replace('\\','/');
        String filename=cleaned.substring(cleaned.lastIndexOf('/')+1);
        if(!filename.toLowerCase(java.util.Locale.ROOT).endsWith(".pdf")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Choisissez un fichier .pdf.");
        var extracted=extractor.extract(bytes);
        return transactions.execute(status->{
            registry.lockRevision();checkContract(contract);
            var metadata=documents.insert(contract,filename,bytes,extracted,clock.instant());documents.incrementRevision();
            String warning=extracted.text().isBlank()?"PDF conservé, mais aucun texte extractible. Ce document nécessite un OCR avant analyse.":
                    extracted.text().length()>40000?"Le PDF dépasse 40 000 caractères. Sélectionnez un passage avant l’analyse IA.":"";
            return new Imported(metadata,extracted.text(),warning);
        });
    }
    public List<DocumentRepository.Metadata> list(String contract) { checkContract(contract);return documents.list(contract); }
    public DocumentRepository.Stored get(UUID id) {
        var document=documents.get(id);
        if(document==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Document introuvable.");
        return document;
    }
    private void checkContract(String contract) {
        if(!documents.contractExists(contract)) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Contrat introuvable.");
    }
}

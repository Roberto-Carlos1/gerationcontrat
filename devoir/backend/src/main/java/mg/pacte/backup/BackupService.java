package mg.pacte.backup;

import java.io.*;
import java.time.Instant;
import java.util.*;
import java.util.zip.*;
import mg.pacte.document.DocumentRepository;
import mg.pacte.model.Registry.*;
import mg.pacte.repository.RegistryRepository;
import mg.pacte.service.RegistryValidator;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

@Service
public class BackupService {
    public static final int MAX=50_000_000;
    public record FileInfo(UUID id,String contractId,String filename,int pages,String text,Instant uploadedAt) {}
    public record Manifest(String format,Data data,List<FileInfo> documents) {}
    private final RegistryRepository registry;
    private final DocumentRepository documents;
    private final RegistryValidator validator;
    private final JsonMapper mapper;
    public BackupService(RegistryRepository registry,DocumentRepository documents,RegistryValidator validator,JsonMapper mapper) {
        this.registry=registry;this.documents=documents;this.validator=validator;this.mapper=mapper;
    }
    @Transactional
    public byte[] export() {
        registry.lockRevision();
        if(!documents.backupFits(MAX))throw error(413,"Sauvegarde limitée à 50 Mo et 1 000 PDF. Utilisez pg_dump pour une base plus grande.");
        var stored=documents.all();
        if(stored.size()>1000)throw error(413,"Sauvegarde limitée à 1 000 documents.");
        var infos=stored.stream().map(d->new FileInfo(d.metadata().id(),d.metadata().contractId(),d.metadata().filename(),d.metadata().pages(),d.text(),d.metadata().uploadedAt())).toList();
        try(var bytes=new ByteArrayOutputStream();var zip=new ZipOutputStream(bytes)) {
            byte[] manifest=mapper.writeValueAsBytes(new Manifest("pacte-full-v1",registry.read(),infos));
            long total=manifest.length;
            for(var d:stored)total+=d.content().length;
            if(total>MAX)throw error(413,"Sauvegarde limitée à 50 Mo décompressés. Utilisez pg_dump pour une base plus grande.");
            add(zip,"pacte.json",manifest);
            for(var d:stored)add(zip,"documents/"+d.metadata().id()+".pdf",d.content());
            zip.finish();
            if(bytes.size()>MAX)throw error(413,"Archive trop volumineuse.");
            return bytes.toByteArray();
        }catch(IOException e){throw error(500,"Création de la sauvegarde impossible.");}
    }
    @Transactional
    public Snapshot restore(byte[] bytes,long revision) {
        try {
            Map<String,byte[]> entries=new HashMap<>();long total=0;
            try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    if(entries.size()>=1001 || entry.isDirectory() || (!entry.getName().equals("pacte.json")&&!entry.getName().matches("documents/[a-fA-F0-9-]{36}\\.pdf")))throw error(400,"Structure d’archive invalide.");
                    byte[] content=zip.readNBytes((int)(MAX-total)+1);total+=content.length;
                    if(total>MAX || entries.putIfAbsent(entry.getName(),content)!=null)throw error(400,"Archive trop volumineuse ou entrées dupliquées.");
                }
            }
            if(!entries.containsKey("pacte.json"))throw error(400,"Manifest de sauvegarde absent.");
            var manifest=mapper.readValue(entries.remove("pacte.json"),Manifest.class);
            if(manifest==null || !"pacte-full-v1".equals(manifest.format()) || manifest.documents()==null || manifest.documents().size()>1000)throw error(400,"Format de sauvegarde incompatible.");
            validator.validate(manifest.data());
            Set<String> ids=new HashSet<>();for(var c:manifest.data().contracts())ids.add(c.id());
            List<DocumentRepository.Stored> files=new ArrayList<>();Set<UUID> seen=new HashSet<>();
            for(var info:manifest.documents()) {
                if(info==null || info.id()==null || !seen.add(info.id()) || !ids.contains(info.contractId()) || info.filename()==null || info.filename().isBlank() || info.filename().length()>200 || info.filename().chars().anyMatch(Character::isISOControl)
                        || info.filename().contains("/") || info.filename().contains("\\") || info.text()==null || info.text().length()>200000 || info.uploadedAt()==null || info.pages()<1 || info.pages()>100)throw error(400,"Métadonnées de document invalides.");
                byte[] pdf=entries.remove("documents/"+info.id()+".pdf");
                if(pdf==null || pdf.length<5 || pdf.length>10_000_000 || !new String(pdf,0,5,java.nio.charset.StandardCharsets.US_ASCII).equals("%PDF-"))throw error(400,"Fichier PDF absent ou invalide.");
                try(var checked=org.apache.pdfbox.Loader.loadPDF(pdf)) {
                    if(checked.isEncrypted() || checked.getNumberOfPages()!=info.pages())throw error(400,"PDF protégé ou nombre de pages incohérent dans l’archive.");
                }
                var metadata=new DocumentRepository.Metadata(info.id(),info.contractId(),info.filename(),pdf.length,info.pages(),info.text().length(),info.uploadedAt());
                files.add(new DocumentRepository.Stored(metadata,pdf,info.text()));
            }
            if(!entries.isEmpty())throw error(400,"L’archive contient des fichiers non référencés.");
            long current=registry.lockRevision();
            if(current!=revision)throw error(409,"Les données ont changé. Rechargez puis recommencez la restauration.");
            documents.deleteAll();registry.replace(manifest.data());for(var file:files)documents.restore(file);
            return new Snapshot(current+1,registry.read());
        }catch(IOException|tools.jackson.core.JacksonException|IllegalArgumentException e){throw error(400,"Archive ZIP invalide. Aucune donnée modifiée.");}
    }
    private void add(ZipOutputStream zip,String name,byte[] bytes)throws IOException{zip.putNextEntry(new ZipEntry(name));zip.write(bytes);zip.closeEntry();}
    private ResponseStatusException error(int status,String message){return new ResponseStatusException(HttpStatus.valueOf(status),message);}
}

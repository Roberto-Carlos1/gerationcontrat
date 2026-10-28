package mg.pacte.ocr;

import java.io.IOException;
import java.nio.file.*;
import java.time.Clock;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import mg.pacte.document.*;
import mg.pacte.repository.RegistryRepository;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.rendering.*;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@Service
public class OcrService {
    private final String command,languages;
    private final DocumentService documents;
    private final DocumentRepository repository;
    private final RegistryRepository registry;
    private final TransactionTemplate transactions;
    private final Semaphore slot=new Semaphore(1);
    public OcrService(@Value("${pacte.ocr.command}") String command,@Value("${pacte.ocr.languages}") String languages,
            DocumentService documents,DocumentRepository repository,RegistryRepository registry,TransactionTemplate transactions) {
        this.command=command;this.languages=languages;this.documents=documents;this.repository=repository;this.registry=registry;this.transactions=transactions;
    }
    public Map<String,Object> status() { return Map.of("available",available(),"languages",languages,"maxScannedPages",20); }
    public boolean available() {
        Process process=null;
        try { process=new ProcessBuilder(command,"--version").redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(2,TimeUnit.SECONDS)&&process.exitValue()==0;
        } catch(IOException e) { return false; } catch(InterruptedException e) { Thread.currentThread().interrupt();return false; }
        finally { if(process!=null&&process.isAlive())process.destroyForcibly(); }
    }
    public DocumentService.Imported recognize(UUID id) {
        if(!slot.tryAcquire()) throw error(429,"Un OCR est déjà en cours. Réessayez dans un instant.");
        Path folder=null;
        try {
            if(!available())throw error(503,"OCR indisponible : installez Tesseract avec les langues française et anglaise sur le serveur.");
            var stored=documents.get(id);folder=Files.createTempDirectory("pacte-ocr-");
            String text=extract(stored.content(),folder);
            return transactions.execute(tx->{
                registry.lockRevision();var current=documents.get(id);
                repository.updateText(id,text);repository.incrementRevision();
                var m=current.metadata();
                return new DocumentService.Imported(new DocumentRepository.Metadata(m.id(),m.contractId(),m.filename(),m.size(),m.pages(),text.length(),m.uploadedAt()),
                        text,text.isBlank()?"Aucun texte reconnu. Vérifiez la qualité ou l’orientation du scan.":"Texte reconnu par OCR : relisez-le avant analyse, des erreurs de reconnaissance sont possibles.");
            });
        } catch(IOException e) { throw error(422,"Impossible de lire le PDF ou d’exécuter l’OCR."); }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw error(503,"OCR interrompu."); }
        finally {
            if(folder!=null)try(var paths=Files.walk(folder)){paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.deleteIfExists(p);}catch(IOException ignored){}});}catch(IOException ignored){}
            slot.release();
        }
    }
    private String extract(byte[] bytes,Path folder) throws IOException,InterruptedException {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(90);
        StringBuilder result=new StringBuilder();int scanned=0;
        try(var pdf=Loader.loadPDF(bytes)) {
            var renderer=new PDFRenderer(pdf);
            for(int i=0;i<pdf.getNumberOfPages();i++) {
                var stripper=new PDFTextStripper();stripper.setStartPage(i+1);stripper.setEndPage(i+1);
                String text=stripper.getText(pdf).strip();
                if(text.isBlank()) {
                    if(++scanned>20)throw error(422,"OCR limité à 20 pages scannées par document. Découpez le PDF.");
                    var box=pdf.getPage(i).getCropBox();
                    if((box.getWidth()/72.0*200)*(box.getHeight()/72.0*200)>12_000_000)throw error(422,"Une page est trop grande pour l’OCR.");
                    if(System.nanoTime()>=deadline)throw error(504,"Délai OCR dépassé. Découpez le PDF.");
                    Path image=folder.resolve("page.png"),output=folder.resolve("recognized");
                    var rendered=renderer.renderImageWithDPI(i,200,ImageType.GRAY);
                    try{ImageIO.write(rendered,"png",image.toFile());}finally{rendered.flush();}
                    var builder=new ProcessBuilder(command,image.toString(),output.toString(),"-l",languages,"--psm","3");
                    builder.environment().put("OMP_THREAD_LIMIT","1");
                    Process process=builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(folder.resolve("ocr.log").toFile()).start();
                    try {
                        long remaining=Math.max(1,Math.min(TimeUnit.SECONDS.toNanos(30),deadline-System.nanoTime()));
                        if(!process.waitFor(remaining,TimeUnit.NANOSECONDS))throw error(504,"Délai OCR dépassé. Réessayez avec moins de pages.");
                        if(process.exitValue()!=0)throw error(503,"Tesseract a échoué. Vérifiez les langues OCR installées sur le serveur.");
                        Path recognized=folder.resolve("recognized.txt");
                        if(Files.size(recognized)>800_000)throw error(422,"Texte OCR trop volumineux.");
                        text=Files.readString(recognized).strip();
                    } finally {if(process.isAlive())process.destroyForcibly();}
                }
                if(result.length()+text.length()+2>200_000)throw error(422,"Texte OCR limité à 200 000 caractères.");
                result.append(text).append("\n\n");
            }
        }
        return result.toString().strip();
    }
    private ResponseStatusException error(int code,String message){return new ResponseStatusException(HttpStatus.valueOf(code),message);}
}

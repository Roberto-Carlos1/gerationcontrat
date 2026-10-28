package mg.pacte.document;

import java.io.IOException;
import java.io.StringWriter;
import java.util.concurrent.Semaphore;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class PdfExtractor {
    public static final int MAX_BYTES=10_000_000;
    private static final int MAX_TEXT=200_000;
    private final Semaphore slots=new Semaphore(2);
    public record Extracted(String text,int pages) {}

    public Extracted extract(byte[] bytes) {
        if(bytes.length>MAX_BYTES) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"PDF trop volumineux : 10 Mo maximum.");
        if(bytes.length<5 || bytes[0]!='%' || bytes[1]!='P' || bytes[2]!='D' || bytes[3]!='F' || bytes[4]!='-') {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Le fichier n’est pas un PDF valide.");
        }
        if(!slots.tryAcquire()) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"Deux imports PDF sont déjà en cours. Réessayez.");
        try(var document=Loader.loadPDF(bytes)) {
            if(document.isEncrypted() || !document.getCurrentAccessPermission().canExtractContent()) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF protégé : importez une copie déverrouillée.");
            }
            if(document.getNumberOfPages()<1 || document.getNumberOfPages()>100) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"Le PDF doit contenir entre 1 et 100 pages.");
            }
            var text=new StringWriter() {
                @Override public void write(char[] chars,int off,int len) {
                    if(getBuffer().length()+len>MAX_TEXT) throw new TextLimit();
                    super.write(chars,off,len);
                }
                @Override public void write(String value,int off,int len) {
                    if(getBuffer().length()+len>MAX_TEXT) throw new TextLimit();
                    super.write(value,off,len);
                }
                @Override public void write(String value) {
                    write(value,0,value.length());
                }
                @Override public void write(int value) {
                    if(getBuffer().length()>=MAX_TEXT) throw new TextLimit();
                    super.write(value);
                }
            };
            var stripper=new PDFTextStripper();stripper.setSortByPosition(true);stripper.writeText(document,text);
            return new Extracted(text.toString().strip(),document.getNumberOfPages());
        } catch(InvalidPasswordException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF protégé par un mot de passe : importez une copie déverrouillée.");
        } catch(TextLimit e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF trop long : plus de 200 000 caractères de texte.");
        } catch(IOException e) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,"PDF illisible ou endommagé.");
        } finally { slots.release(); }
    }
    private static class TextLimit extends RuntimeException {}
}

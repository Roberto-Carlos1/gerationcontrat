package mg.pacte;

import mg.pacte.document.PdfExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

class PdfExtractorTest {
    private final PdfExtractor extractor=new PdfExtractor();
    @Test void extractsFrenchText() throws Exception {
        var result=extractor.extract(PdfFixtures.pdf("Préavis de 30 jours.",false,1));
        assertEquals(1,result.pages());assertTrue(result.text().contains("Préavis de 30 jours."));
    }
    @Test void reportsNoTextWithoutInventingOcr() throws Exception {
        assertTrue(extractor.extract(PdfFixtures.pdf("",false,1)).text().isEmpty());
    }
    @Test void rejectsEncryptedPdf() throws Exception {
        byte[] pdf=PdfFixtures.pdf("Document",true,1);
        assertEquals(422,assertThrows(ResponseStatusException.class,()->extractor.extract(pdf)).getStatusCode().value());
    }
    @Test void rejectsWrongFormatAndTooManyPages() throws Exception {
        assertEquals(422,assertThrows(ResponseStatusException.class,()->extractor.extract("not a pdf".getBytes())).getStatusCode().value());
        byte[] pdf=PdfFixtures.pdf("",false,101);
        assertEquals(422,assertThrows(ResponseStatusException.class,()->extractor.extract(pdf)).getStatusCode().value());
    }
    @Test void rejectsOversizedInput() {
        assertEquals(413,assertThrows(ResponseStatusException.class,()->extractor.extract(new byte[10000001])).getStatusCode().value());
    }
}

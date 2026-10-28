package mg.pacte;

import java.net.URI;
import java.net.http.*;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="pacte.mail.enabled=false")
@EnabledIfEnvironmentVariable(named="PACTE_TEST_DATABASE_URL",matches=".+")
class RegistryIntegrationTest {
    @LocalServerPort int port;
    @Autowired JsonMapper mapper;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private final HttpClient client=HttpClient.newHttpClient();

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("pacte.ai.api-key",()->"");
        registry.add("pacte.ocr.command",()->System.getenv().getOrDefault("PACTE_TEST_TESSERACT","tesseract"));
        registry.add("spring.datasource.url",()->System.getenv("PACTE_TEST_DATABASE_URL"));
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("PACTE_TEST_DATABASE_USER","pacte"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("PACTE_TEST_DATABASE_PASSWORD",""));
    }

    private HttpResponse<String> request(String method,String path,String body,String origin) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(origin!=null) builder.header("Origin",origin);
        if(body!=null) builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private ObjectNode state() throws Exception { return (ObjectNode)mapper.readTree(request("GET","/api/state",null,null).body()); }
    private HttpResponse<String> save(JsonNode state) throws Exception { return request("PUT","/api/state",mapper.writeValueAsString(state),null); }
    private ObjectNode first(ObjectNode snapshot) { return (ObjectNode)snapshot.path("data").path("contracts").get(0); }

    @BeforeEach void reset() throws Exception {
        jdbc.update("DELETE FROM documents");
        var current=state();
        current.set("data",mapper.readTree(request("GET","/api/demo",null,null).body()));
        assertEquals(200,save(current).statusCode());
    }

    @Test void dynamicContractsPersistAndRejectHiddenFields() throws Exception {
        for (var entry : mg.pacte.service.RegistryValidator.SPECIFIC.entrySet()) {
            var current=state();
            var c=first(current).deepCopy();
            c.put("id","CT-new-"+entry.getKey());
            ((tools.jackson.databind.node.ArrayNode)current.path("data").path("contracts")).insert(0,c);
            c.putNull("amount");c.putNull("end");
            var d=mapper.createObjectNode();
            d.put("reference","REF-"+entry.getKey()).put("type",entry.getKey())
                .put("representative", "Représentant").put("department","Achats")
                .put("purpose","Objet de test").putNull("signed");
            d.put("currency","EUR").put("payment","Sur facture").put("clauses","Confidentialité");
            var specific=mapper.createObjectNode().put(entry.getValue().getFirst(),"Obligation test");
            d.set("specific",specific);c.set("details",d);
            var result=save(current);assertEquals(200,result.statusCode(),result.body());
            var after=state();assertEquals(d,first(after).path("details"));
            assertTrue(first(after).path("amount").isNull());assertTrue(first(after).path("end").isNull());
            assertEquals(entry.getKey(),jdbc.queryForObject("SELECT details->>'type' FROM contracts WHERE id=?",String.class,c.path("id").asString()));
            first(after).put("name","Contrat modifié");
            assertEquals(200,save(after).statusCode());assertEquals("Contrat modifié",first(state()).path("name").asString());
            var before=state();var invalid=before.deepCopy();
            ((ObjectNode)first(invalid).path("details").path("specific")).put("hiddenField","Interdit");
            assertEquals(400,save(invalid).statusCode());assertEquals(before,state());
            invalid=before.deepCopy();((ObjectNode)first(invalid).path("details").path("specific")).remove(entry.getValue().getFirst());
            assertEquals(400,save(invalid).statusCode());assertEquals(before,state());
        }
    }

    @Test void migrationCanRunAgainWithoutChangingExistingRegistry() throws Exception {
        var before=state();
        var source=new org.springframework.core.io.ClassPathResource("schema.sql");
        try(var connection=java.util.Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection,source);
        }
        assertEquals(before,state());
    }

    @Test void persistsAndRejectsStaleRevision() throws Exception {
        var current=state();first(current).put("supplier","Fournisseur PostgreSQL");
        var saved=save(current);assertEquals(200,saved.statusCode(),saved.body());
        assertEquals("Fournisseur PostgreSQL",first(state()).path("supplier").asString());
        assertEquals(409,save(current).statusCode());
    }
    @Test void invalidImportDoesNotChangeDatabase() throws Exception {
        var before=state();var invalid=before.deepCopy();first(invalid).put("amount",-1);
        assertEquals(400,save(invalid).statusCode());assertEquals(before,state());
        invalid=before.deepCopy();first(invalid).put("end","2026-02-30");
        assertEquals(400,save(invalid).statusCode());assertEquals(before,state());
        invalid=before.deepCopy();first(invalid).put("notice",2.5);
        assertEquals(400,save(invalid).statusCode());assertEquals(before,state());
        invalid=before.deepCopy();first(invalid).put("unexpectedField","x");
        assertEquals(400,save(invalid).statusCode());assertEquals(before,state());
    }
    @Test void expiresContractsAndPreservesAnalysisHistory() throws Exception {
        var current=state();first(current).put("start","2020-01-01").put("end","2021-01-01");
        var disputes=(ObjectNode)current.path("data").path("cases").get(0);
        disputes.put("stage",3);((tools.jackson.databind.node.ArrayNode)disputes.path("history")).add("Clôturé : accord fournisseur.");
        var analyses=(tools.jackson.databind.node.ArrayNode)current.path("data").path("analysis");
        analyses.add(mapper.readTree("""
            {"contract":"Contrat test","date":"27/10/2026","validated":true,
             "groups":[{"label":"Préavis","excerpts":["Préavis de 30 jours.","Seconde clause."]}]}
            """));
        assertEquals(200,save(current).statusCode());
        var after=state();
        assertEquals("Terminé",first(after).path("status").asString());
        assertEquals(current.path("data").path("analysis"),after.path("data").path("analysis"));
        assertEquals(current.path("data").path("cases"),after.path("data").path("cases"));
    }
    @Test void concurrentWritesCannotOverwriteEachOther() throws Exception {
        var original=state();
        try(var executor=Executors.newFixedThreadPool(2)) {
            Callable<Integer> write=()->save(original).statusCode();
            var results=executor.invokeAll(List.of(write,write));
            var statuses=List.of(results.get(0).get(),results.get(1).get());
            assertTrue(statuses.contains(200));assertTrue(statuses.contains(409));
        }
    }
    @Test void guardsRequestSizeOriginAndPrivateFiles() throws Exception {
        assertEquals(403,request("PUT","/api/state",mapper.writeValueAsString(state()),"https://other.example").statusCode());
        assertEquals(413,request("PUT","/api/state"," ".repeat(2_000_001),null).statusCode());
        for(String path:List.of("/","/app.js","/styles.css")) assertEquals(200,request("GET",path,null,null).statusCode());
        for(String path:List.of("/application.properties","/schema.sql","/seed.json","/data/pacte.sqlite3"))
            assertEquals(404,request("GET",path,null,null).statusCode());
    }

    @Test void aiWithoutKeyReportsUnavailableWithoutFallback() throws Exception {
        var status=request("GET","/api/ai/status",null,null);
        assertEquals(200,status.statusCode());
        assertFalse(mapper.readTree(status.body()).path("configured").asBoolean());
        assertFalse(status.body().contains("apiKey"));
        var analysis=request("POST","/api/ai/analyze","{\"contractId\":\"CT-001\",\"text\":\"Préavis de 30 jours.\"}",null);
        assertEquals(503,analysis.statusCode());
        assertTrue(analysis.body().contains("OPENAI_API_KEY"));
        assertEquals(403,request("POST","/api/ai/analyze","{}","https://other.example").statusCode());
        assertEquals(400,request("POST","/api/ai/analyze","{\"contractId\":\"CT-001\",\"text\":\"\"}",null).statusCode());
    }

    private HttpResponse<String> uploadPdf(byte[] pdf,String filename) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/contracts/CT-001/documents?filename="+java.net.URLEncoder.encode(filename,java.nio.charset.StandardCharsets.UTF_8)))
                .header("Content-Type","application/pdf").POST(HttpRequest.BodyPublishers.ofByteArray(pdf)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @Test void importsPdfAndPreservesItWhenContractIsEditedOrReordered() throws Exception {
        byte[] pdf=PdfFixtures.pdf("Préavis de 30 jours.",false,1);
        var uploaded=uploadPdf(pdf,"contrat.pdf");assertEquals(201,uploaded.statusCode(),uploaded.body());
        var document=mapper.readTree(uploaded.body());
        assertTrue(document.path("text").asString().contains("Préavis de 30 jours."));
        String id=document.path("document").path("id").asString();
        var download=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+id)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(pdf,download.body());assertTrue(download.headers().firstValue("Content-Disposition").orElse("").contains("attachment"));
        var current=state();first(current).put("supplier","Modifié après import PDF");
        // Inverser deux contrats vérifie également la gestion de la position unique.
        var contracts=(tools.jackson.databind.node.ArrayNode)current.path("data").path("contracts");
        var a=contracts.get(0);var b=contracts.get(1);contracts.set(0,b);contracts.set(1,a);
        assertEquals(200,save(current).statusCode());
        assertEquals(1,mapper.readTree(request("GET","/api/contracts/CT-001/documents",null,null).body()).size());
        var text=mapper.readTree(request("GET","/api/documents/"+id+"/text",null,null).body());
        assertTrue(text.path("text").asString().contains("Préavis de 30 jours."));
    }
    @Test void rejectsInvalidPdfWithoutDatabaseChanges() throws Exception {
        var before=state();assertEquals(422,uploadPdf("not-pdf".getBytes(),"fake.pdf").statusCode());
        assertEquals(before,state());
        assertEquals(0,mapper.readTree(request("GET","/api/contracts/CT-001/documents",null,null).body()).size());
    }
    @Test void preservesScannedPdfAndReportsMissingText() throws Exception {
        var response=uploadPdf(PdfFixtures.pdf("",false,1),"scan.pdf");
        assertEquals(201,response.statusCode());
        var result=mapper.readTree(response.body());assertTrue(result.path("text").asString().isEmpty());
        assertTrue(result.path("warning").asString().contains("OCR"));
    }

    @Test void completeBackupRestoresDataAndOriginalPdfs() throws Exception {
        byte[] pdf=PdfFixtures.pdf("Contrat de test complet.",false,1);
        var uploaded=mapper.readTree(uploadPdf(pdf,"complet.pdf").body());
        String id=uploaded.path("document").path("id").asString();
        var backup=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/backups/full")).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200,backup.statusCode());
        var empty=state();empty.set("data",mapper.readTree("{\"contracts\":[],\"cases\":[],\"analysis\":[],\"done\":[]}"));
        assertEquals(200,save(empty).statusCode());assertEquals(0,state().path("data").path("contracts").size());
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/backups/full?revision="+state().path("revision").asLong()))
                .header("Content-Type","application/zip").POST(HttpRequest.BodyPublishers.ofByteArray(backup.body())).build();
        var restored=client.send(request,HttpResponse.BodyHandlers.ofString());assertEquals(200,restored.statusCode(),restored.body());
        assertEquals(5,state().path("data").path("contracts").size());
        var downloaded=client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/documents/"+id)).GET().build(),HttpResponse.BodyHandlers.ofByteArray());
        assertArrayEquals(pdf,downloaded.body());
        assertEquals(409,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());
    }
    @Test void invalidCompleteBackupDoesNotModifyData() throws Exception {
        var before=state();
        var request=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+"/api/backups/full?revision="+before.path("revision").asLong()))
                .header("Content-Type","application/zip").POST(HttpRequest.BodyPublishers.ofString("not-zip")).build();
        assertEquals(400,client.send(request,HttpResponse.BodyHandlers.ofString()).statusCode());assertEquals(before,state());
    }
    @Test void serviceStatusesAndMailPreviewAreReadOnly() throws Exception {
        var before=state();
        assertEquals(200,request("GET","/api/ocr/status",null,null).statusCode());
        var status=mapper.readTree(request("GET","/api/reminders/status",null,null).body());assertFalse(status.path("enabled").asBoolean());
        var preview=mapper.readTree(request("GET","/api/reminders/preview",null,null).body());assertTrue(preview.isArray());
        assertEquals(before,state());
    }
    @Test @EnabledIfEnvironmentVariable(named="PACTE_TEST_TESSERACT",matches=".+")
    void realOcrRecognizesAnImageOnlyPdf() throws Exception {
        byte[] pdf=PdfFixtures.scanned();
        var imported=mapper.readTree(uploadPdf(pdf,"scan-ocr.pdf").body());String id=imported.path("document").path("id").asString();
        assertEquals("",imported.path("text").asString());
        var response=request("POST","/api/documents/"+id+"/ocr",null,null);
        assertEquals(200,response.statusCode(),response.body());
        String text=mapper.readTree(response.body()).path("text").asString();assertTrue(text.contains("CONTRAT"),text);
        assertTrue(mapper.readTree(request("GET","/api/documents/"+id+"/text",null,null).body()).path("text").asString().contains("CONTRAT"));
    }
}

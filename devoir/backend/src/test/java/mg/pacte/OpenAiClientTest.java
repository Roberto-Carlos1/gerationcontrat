package mg.pacte;

import com.sun.net.httpserver.HttpServer;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import mg.pacte.ai.*;
import org.junit.jupiter.api.*;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class OpenAiClientTest {
    private final JsonMapper mapper=JsonMapper.builder().build();
    private HttpServer server;
    private ValidatorFactory validators;
    private OpenAiClient client;
    private final AtomicReference<JsonNode> received=new AtomicReference<>();
    private final AtomicReference<String> authorization=new AtomicReference<>();
    private int providerStatus=200;
    private String providerBody;

    static Map<String,Object> output(String excerpt) {
        return Map.of("summary","Contrat de services avec un préavis.","riskLevel","Modéré",
                "groups",List.of(
                        Map.of("label","Renouvellement","excerpts",List.of()),
                        Map.of("label","Préavis / résiliation","excerpts",List.of(excerpt)),
                        Map.of("label","Pénalités","excerpts",List.of()),
                        Map.of("label","Durée / échéance","excerpts",List.of()),
                        Map.of("label","Litiges","excerpts",List.of())),
                "risks",List.of(Map.of("title","Montant absent","severity","Indéterminé","explanation","Le montant n’est pas indiqué dans le texte fourni ; vérifier le contrat complet.","excerpt","")),
                "recommendations",List.of("Vérifier le montant dans le contrat complet."));
    }

    private String envelope(Map<String,Object> output) {
        return mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",
                List.of(Map.of("type","output_text","text",mapper.writeValueAsString(output)))))));
    }

    @BeforeEach void setup() throws Exception {
        validators=Validation.buildDefaultValidatorFactory();
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/responses",exchange->{
            received.set(mapper.readTree(exchange.getRequestBody().readAllBytes()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=providerBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(providerStatus,bytes.length);
            try(var response=exchange.getResponseBody()) { response.write(bytes); }
        });
        server.start();
        providerBody=envelope(output("Préavis de 30 jours."));
        client=new OpenAiClient(new AiProperties("test-key","gpt-4.1-mini",URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/v1/responses"),5),mapper,validators.getValidator());
    }
    @AfterEach void cleanup() { server.stop(0);validators.close(); }

    @Test void sendsStructuredResponsesRequestAndReturnsAnalysis() {
        var result=client.analyze("Préavis de 30 jours.");
        assertEquals("Modéré",result.riskLevel());
        assertEquals("Bearer test-key",authorization.get());
        assertFalse(received.get().path("store").asBoolean());
        assertEquals("gpt-4.1-mini",received.get().path("model").asString());
        assertEquals("json_schema",received.get().path("text").path("format").path("type").asString());
        assertTrue(received.get().path("text").path("format").path("strict").asBoolean());
        assertEquals("Préavis de 30 jours.",received.get().path("input").get(0).path("content").get(0).path("text").asString());
    }
    @Test void rejectsFabricatedExcerpts() {
        providerBody=envelope(output("Préavis de 90 jours."));
        assertEquals(502,assertThrows(ResponseStatusException.class,()->client.analyze("Préavis de 30 jours.")).getStatusCode().value());
    }
    @Test void acceptsWhitespaceDifferencesInQuotes() {
        assertDoesNotThrow(()->client.analyze("Préavis de\n30 jours."));
    }
    @Test void reportsProviderQuotaAndHidesProviderErrorBody() {
        providerStatus=429;providerBody="Sensitive provider diagnostic";
        var error=assertThrows(ResponseStatusException.class,()->client.analyze("Texte"));
        assertEquals(429,error.getStatusCode().value());
        assertFalse(error.getReason().contains("Sensitive"));
    }
    @Test void handlesRefusalWithoutInventingAnalysis() {
        providerBody=mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",List.of(Map.of("type","refusal","refusal","Non"))))));
        assertEquals(422,assertThrows(ResponseStatusException.class,()->client.analyze("Texte")).getStatusCode().value());
    }
    @Test void rejectsIncompleteOrMalformedOutput() {
        providerBody="{\"status\":\"incomplete\"}";
        assertEquals(502,assertThrows(ResponseStatusException.class,()->client.analyze("Texte")).getStatusCode().value());
        providerBody="not-json";
        assertEquals(502,assertThrows(ResponseStatusException.class,()->client.analyze("Texte")).getStatusCode().value());
    }
    @Test void unconfiguredClientMakesNoRequest() throws Exception {
        var disabled=new OpenAiClient(new AiProperties("","gpt-4.1-mini",URI.create("http://127.0.0.1:1"),5),mapper,validators.getValidator());
        assertEquals(503,assertThrows(ResponseStatusException.class,()->disabled.analyze("Texte")).getStatusCode().value());
        assertNull(received.get());
    }
}

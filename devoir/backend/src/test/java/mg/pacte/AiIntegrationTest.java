package mg.pacte;

import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.*;
import static org.junit.jupiter.api.Assertions.*;

/** Faux fournisseur HTTP, vraie application et vraie base PostgreSQL : aucun appel payant. */
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties="pacte.mail.enabled=false")
@EnabledIfEnvironmentVariable(named="PACTE_TEST_DATABASE_URL",matches=".+")
class AiIntegrationTest {
    private static HttpServer provider;
    private static final AtomicInteger calls=new AtomicInteger();
    @LocalServerPort int port;
    @Autowired JsonMapper mapper;
    private final HttpClient http=HttpClient.newHttpClient();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) throws Exception {
        var json=JsonMapper.builder().build();
        provider=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        provider.createContext("/v1/responses",exchange->{
            exchange.getRequestBody().readAllBytes();calls.incrementAndGet();
            var message=Map.of("type","message","content",List.of(Map.of("type","output_text","text",json.writeValueAsString(OpenAiClientTest.output("Préavis de 30 jours.")))));
            byte[] bytes=json.writeValueAsString(Map.of("status","completed","output",List.of(message))).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        provider.start();
        registry.add("pacte.ai.api-key",()->"fake-test-key");
        registry.add("pacte.ai.endpoint",()->"http://127.0.0.1:"+provider.getAddress().getPort()+"/v1/responses");
        registry.add("spring.datasource.url",()->System.getenv("PACTE_TEST_DATABASE_URL"));
        registry.add("spring.datasource.username",()->System.getenv().getOrDefault("PACTE_TEST_DATABASE_USER","pacte"));
        registry.add("spring.datasource.password",()->System.getenv().getOrDefault("PACTE_TEST_DATABASE_PASSWORD",""));
    }
    @AfterAll static void cleanup(){if(provider!=null)provider.stop(0);}

    private HttpResponse<String> call(String method,String path,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create("http://127.0.0.1:"+port+path));
        if(body!=null)builder.header("Content-Type","application/json");
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    @BeforeEach void reset() throws Exception {
        var state=(ObjectNode)mapper.readTree(call("GET","/api/state",null).body());
        state.set("data",mapper.readTree(call("GET","/api/demo",null).body()));
        assertEquals(200,call("PUT","/api/state",mapper.writeValueAsString(state)).statusCode());
        calls.set(0);
    }

    @Test void analyzesThroughHttpThenPersistsDetailsInPostgres() throws Exception {
        var status=mapper.readTree(call("GET","/api/ai/status",null).body());
        assertTrue(status.path("configured").asBoolean());
        var response=call("POST","/api/ai/analyze","{\"contractId\":\"CT-001\",\"text\":\"Préavis de 30 jours.\"}");
        assertEquals(200,response.statusCode(),response.body());
        var analysis=(ObjectNode)mapper.readTree(response.body());
        assertEquals("OpenAI",analysis.path("ai").path("provider").asString());
        assertFalse(analysis.path("validated").asBoolean());
        assertFalse(response.body().contains("fake-test-key"));
        assertEquals(1,calls.get());
        var state=(ObjectNode)mapper.readTree(call("GET","/api/state",null).body());
        assertEquals(0,state.path("data").path("analysis").size(),"L’utilisateur doit valider avant enregistrement");
        analysis.put("validated",true);
        ((ArrayNode)state.path("data").path("analysis")).add(analysis);
        assertEquals(200,call("PUT","/api/state",mapper.writeValueAsString(state)).statusCode());
        var reloaded=mapper.readTree(call("GET","/api/state",null).body());
        assertEquals(analysis,reloaded.path("data").path("analysis").get(0));
    }
    @Test void rejectsUnknownContractAndOversizedTextBeforeProviderCall() throws Exception {
        assertEquals(404,call("POST","/api/ai/analyze","{\"contractId\":\"CT-missing\",\"text\":\"Préavis de 30 jours.\"}").statusCode());
        String text=mapper.writeValueAsString(Map.of("contractId","CT-001","text","a".repeat(40001)));
        assertEquals(400,call("POST","/api/ai/analyze",text).statusCode());
        assertEquals(0,calls.get());
    }
}

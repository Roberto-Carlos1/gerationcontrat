package mg.pacte;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import mg.pacte.reminder.ReminderService;
import mg.pacte.service.RegistryService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.javamail.*;
import org.springframework.test.context.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties="pacte.mail.enabled=false")
@EnabledIfEnvironmentVariable(named="PACTE_TEST_DATABASE_URL",matches=".+")
class ReminderIntegrationTest {
    @Autowired RegistryService registry;
    @Autowired JdbcTemplate jdbc;
    @DynamicPropertySource static void database(DynamicPropertyRegistry r){
        r.add("spring.datasource.url",()->System.getenv("PACTE_TEST_DATABASE_URL"));
        r.add("spring.datasource.username",()->System.getenv().getOrDefault("PACTE_TEST_DATABASE_USER","pacte"));
        r.add("spring.datasource.password",()->System.getenv().getOrDefault("PACTE_TEST_DATABASE_PASSWORD",""));
    }
    @Test void sendsToLocalSmtpOncePerMilestone()throws Exception{
        jdbc.update("DELETE FROM reminder_deliveries");
        // Date choisie pour placer un contrat actif dans sa période J-30 ou J-10.
        var contract=registry.read().data().contracts().stream().filter(c->c.status().equals("Actif")).findFirst().orElseThrow();
        LocalDate reference=contract.renew().equals("Oui")?contract.end().minusDays(contract.notice()):contract.end();
        var clock=Clock.fixed(reference.minusDays(5).atStartOfDay(ZoneId.of("Indian/Antananarivo")).toInstant(),ZoneId.of("Indian/Antananarivo"));
        AtomicInteger messages=new AtomicInteger();
        try(var socket=new ServerSocket(0,10,InetAddress.getLoopbackAddress());var executor=Executors.newSingleThreadExecutor()){
            socket.setSoTimeout(10000);
            var listener=executor.submit(()->{
                try{
                    while(!Thread.currentThread().isInterrupted())try(var connection=socket.accept()){
                        connection.setSoTimeout(5000);
                        var reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));
                        var writer=new PrintWriter(connection.getOutputStream(),true,StandardCharsets.US_ASCII);writer.print("220 local test SMTP\r\n");writer.flush();
                        boolean data=false;String line;
                        while((line=reader.readLine())!=null){
                            if(data){if(line.equals(".")){messages.incrementAndGet();data=false;writer.print("250 accepted\r\n");writer.flush();}continue;}
                            if(line.startsWith("EHLO")||line.startsWith("HELO"))writer.print("250 local\r\n");
                            else if(line.equals("DATA")){data=true;writer.print("354 send data\r\n");}
                            else if(line.equals("QUIT")){writer.print("221 bye\r\n");writer.flush();break;}
                            else writer.print("250 ok\r\n");writer.flush();
                        }
                    }
                }catch(IOException ignored){}
            });
            var sender=new JavaMailSenderImpl();sender.setHost("127.0.0.1");sender.setPort(socket.getLocalPort());
            sender.getJavaMailProperties().put("mail.smtp.timeout","3000");sender.getJavaMailProperties().put("mail.smtp.connectiontimeout","3000");
            var beans=new StaticListableBeanFactory(Map.of("mail",sender));
            var service=new ReminderService(registry,jdbc,beans.getBeanProvider(JavaMailSender.class),clock,true,"pacte@example.invalid","test@example.invalid","127.0.0.1");
            service.sendDue();int sent=messages.get();assertTrue(sent>0);service.sendDue();assertEquals(sent,messages.get());
            assertEquals(sent,jdbc.queryForObject("SELECT COUNT(*) FROM reminder_deliveries WHERE sent_at IS NOT NULL",Integer.class));
            socket.close();listener.get(5,TimeUnit.SECONDS);
        }
    }
}

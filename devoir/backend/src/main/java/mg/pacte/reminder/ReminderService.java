package mg.pacte.reminder;

import java.time.*;
import java.util.*;
import mg.pacte.model.Registry.Contract;
import mg.pacte.service.RegistryService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

@Service
public class ReminderService {
    public record Reminder(String key,String contractId,String subject,String message,String date,int remaining) {}
    private final RegistryService registry;
    private final JdbcTemplate jdbc;
    private final ObjectProvider<JavaMailSender> mail;
    private final Clock clock;
    private final boolean enabled;
    private final String from,to,host;
    private volatile String lastError="";
    public ReminderService(RegistryService registry,JdbcTemplate jdbc,ObjectProvider<JavaMailSender> mail,Clock clock,
            @Value("${pacte.mail.enabled}") boolean enabled,@Value("${pacte.mail.from}") String from,@Value("${pacte.mail.to}") String to,@Value("${spring.mail.host}") String host) {
        this.registry=registry;this.jdbc=jdbc;this.mail=mail;this.clock=clock;this.enabled=enabled;this.from=from;this.to=to;this.host=host;
    }
    public Map<String,Object> status(){return Map.of("enabled",enabled,"configured",configured(),"lastError",lastError,"history",jdbc.queryForList("SELECT contract_id,reference_date,milestone,sent_at FROM reminder_deliveries WHERE sent_at IS NOT NULL ORDER BY sent_at DESC LIMIT 20"));}
    private boolean configured(){return !host.isBlank()&&!from.isBlank()&&!to.isBlank()&&mail.getIfAvailable()!=null;}
    public List<Reminder> preview(){return due(registry.read().data().contracts(),LocalDate.now(clock));}
    public static List<Reminder> due(List<Contract> contracts,LocalDate today) {
        List<Reminder> list=new ArrayList<>();
        for(var c:contracts) {
            if(!c.status().equals("Actif")||c.end()==null||c.end().isBefore(today))continue;
            LocalDate reference=c.renew().equals("Oui")?c.end().minusDays(c.notice()):c.end();
            long days=java.time.temporal.ChronoUnit.DAYS.between(today,reference);
            if(days<0 || days>30)continue;
            String milestone=days<=10?"10":"30";
            list.add(new Reminder(c.id()+":"+reference+":"+milestone,c.id(),"Pacte : échéance de "+c.supplier()+" dans "+days+" jour(s)",
                    "Contrat : "+c.name()+"\nFournisseur : "+c.supplier()+"\nResponsable : "+c.owner()+"\nDate à respecter : "+reference+"\nType : "+(c.renew().equals("Oui")?"limite de préavis":"fin du contrat")+"\nDélai : "+days+" jour(s)\nConsultez Pacte pour traiter cette échéance.",reference.toString(),(int)days));
        }
        return list;
    }
    @Scheduled(fixedDelayString="${pacte.mail.interval-ms:3600000}",initialDelayString="${pacte.mail.initial-delay-ms:60000}")
    public void sendDue() {
        if(!enabled)return;
        if(!configured()){lastError="SMTP, expéditeur ou destinataire non configuré.";return;}
        try {
            for(var r:preview()) {
                String milestone=r.key().substring(r.key().lastIndexOf(':')+1);
                // Une réservation persistante évite les doubles envois entre deux processus.
                int claimed=jdbc.update("""
                    INSERT INTO reminder_deliveries(delivery_key,contract_id,reference_date,milestone,claimed_at)
                    VALUES(?,?,?,?,CURRENT_TIMESTAMP)
                    ON CONFLICT(delivery_key) DO UPDATE SET claimed_at=CURRENT_TIMESTAMP
                    WHERE reminder_deliveries.sent_at IS NULL AND reminder_deliveries.claimed_at<CURRENT_TIMESTAMP-INTERVAL '15 minutes'
                    """,r.key(),r.contractId(),LocalDate.parse(r.date()),milestone);
                if(claimed==0)continue;
                try {
                    var message=new SimpleMailMessage();message.setFrom(from);message.setTo(to.split(","));message.setSubject(r.subject());message.setText(r.message());
                    mail.getObject().send(message);jdbc.update("UPDATE reminder_deliveries SET sent_at=CURRENT_TIMESTAMP WHERE delivery_key=?",r.key());lastError="";
                }catch(MailException e){jdbc.update("DELETE FROM reminder_deliveries WHERE delivery_key=? AND sent_at IS NULL",r.key());lastError="Envoi SMTP échoué. Vérifiez les paramètres et réessayez au prochain cycle.";}
            }
        }catch(org.springframework.dao.DataAccessException e){lastError="Vérification des rappels impossible : base indisponible.";}
    }
}

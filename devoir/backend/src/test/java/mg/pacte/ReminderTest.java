package mg.pacte;
import java.time.LocalDate;
import java.math.BigDecimal;
import java.util.List;
import mg.pacte.model.Registry.Contract;
import mg.pacte.reminder.ReminderService;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReminderTest {
    private Contract contract(LocalDate end,String renew,int notice,String status){return new Contract("CT-test","Test","Fournisseur","Services",BigDecimal.TEN,"Mensuel",end.minusYears(1),end,notice,renew,"Carlos",status,"","");}
    @Test void usesNoticeDateForAutomaticRenewal(){
        var today=LocalDate.of(2026,10,27);var due=ReminderService.due(List.of(contract(today.plusDays(35),"Oui",30,"Actif")),today);
        assertEquals(1,due.size());assertEquals(5,due.getFirst().remaining());assertTrue(due.getFirst().key().endsWith(":10"));
    }
    @Test void ignoresInactiveExpiredAndDistantContracts(){
        var today=LocalDate.of(2026,10,27);
        assertTrue(ReminderService.due(List.of(contract(today.minusDays(1),"Non",0,"Actif"),contract(today.plusDays(40),"Non",0,"Actif"),contract(today.plusDays(5),"Non",0,"Résilié")),today).isEmpty());
    }
}

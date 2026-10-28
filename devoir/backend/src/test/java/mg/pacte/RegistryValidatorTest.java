package mg.pacte;

import jakarta.validation.Validation;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import mg.pacte.model.Registry.*;
import mg.pacte.service.RegistryValidator;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegistryValidatorTest {
    private final RegistryValidator validator = new RegistryValidator(Validation.buildDefaultValidatorFactory().getValidator());

    private Contract contract() {
        return new Contract("CT-test","Contrat","Fournisseur","Services",new BigDecimal("250000"),"Mensuel",
                LocalDate.of(2026,1,1),LocalDate.of(2027,1,1),30,"Oui","Carlos","Actif","","Note");
    }
    private Data data(List<Contract> contracts,List<Dispute> disputes,List<String> done) {
        return new Data(contracts,disputes,done,List.of());
    }

    @Test void acceptsValidBackup() {
        assertDoesNotThrow(() -> validator.validate(data(List.of(contract()),List.of(),List.of("CT-test:2026-12-02:30"))));
    }
    @Test void rejectsDuplicateContracts() {
        assertThrows(IllegalArgumentException.class,() -> validator.validate(data(List.of(contract(),contract()),List.of(),List.of())));
    }
    @Test void rejectsOrphanDispute() {
        var dispute=new Dispute("LT-test","CT-missing","Retard","Description",BigDecimal.ZERO,"Carlos",0,List.of("Signalé"));
        assertThrows(IllegalArgumentException.class,() -> validator.validate(data(List.of(contract()),List.of(dispute),List.of())));
    }
    @Test void rejectsNullNestedFields() {
        assertThrows(IllegalArgumentException.class,() -> validator.validate(new Data(List.of(contract()),List.of(),List.of(),
                List.of(new Analysis("Contrat","27/10/2026",List.of(new ClauseGroup("Préavis",null)),true)))));
    }
    @Test void rejectsInvalidAcknowledgementDate() {
        assertThrows(RuntimeException.class,() -> validator.validate(data(List.of(contract()),List.of(),List.of("CT-test:2026-02-30:10"))));
    }
}

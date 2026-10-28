package mg.pacte.service;

import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import mg.pacte.model.Registry.Data;
import org.springframework.stereotype.Component;

@Component
public class RegistryValidator {
    public static final Map<String, java.util.List<String>> SPECIFIC=Map.of(
        "distribution", java.util.List.of("territory","exclusivity","products","targets","storage","promotion"),
        "commercial", java.util.List.of("transaction","products","quantities","unitPrices","discounts","delivery"),
        "partenariat", java.util.List.of("objectives","contributions","responsibilities","sharing","exit"),
        "approvisionnement", java.util.List.of("products","minimum","frequency","delivery","unitPrices","compliance"),
        "services", java.util.List.of("description","deliverables","schedule","acceptance","maintenance","support","clientName","clientAddress","clientRepresentative","providerAddress","discussionDate","clientObligations","place"),
        "autre", java.util.List.of("customType","obligations","conditions"));
    private final Validator validator;
    public RegistryValidator(Validator validator) { this.validator = validator; }

    public void validate(Data data) {
        if (data == null || !validator.validate(data).isEmpty()) {
            throw new IllegalArgumentException("Données invalides : vérifiez les champs, montants et statuts.");
        }
        Set<String> contracts = new HashSet<>();
        Set<String> references = new HashSet<>();
        for (var c : data.contracts()) {
            if (c.details()!=null) {
                var d=c.details();
                if (!references.add(d.reference().strip().toLowerCase(java.util.Locale.ROOT)))
                    throw new IllegalArgumentException("Référence de contrat dupliquée.");
                var allowed=SPECIFIC.get(d.type());
                if (allowed==null || !allowed.containsAll(d.specific().keySet()))
                    throw new IllegalArgumentException("Champs incompatibles avec le type de contrat.");
                String required=allowed.getFirst();
                if (d.specific().getOrDefault(required, "").isBlank())
                    throw new IllegalArgumentException("Complétez les obligations propres au type de contrat.");
                if (d.signed()!=null && (d.signed().getYear()<1 || d.signed().getYear()>9999))
                    throw new IllegalArgumentException("Date de signature invalide.");
            }
            if (!contracts.add(c.id())) throw new IllegalArgumentException("Identifiant de contrat dupliqué.");
            if ((c.end()!=null && (c.end().isBefore(c.start()) || c.end().getYear()>9999)) || c.start().getYear() < 1 || c.start().getYear()>9999) {
                throw new IllegalArgumentException("Dates du contrat incohérentes.");
            }
        }
        Set<String> disputes = new HashSet<>();
        for (var c : data.cases()) {
            if (!disputes.add(c.id()) || !contracts.contains(c.contract())) {
                throw new IllegalArgumentException("Litige dupliqué ou contrat associé inexistant.");
            }
        }
        Set<String> acknowledgements = new HashSet<>();
        for (String key : data.done()) {
            String[] parts = key.split(":");
            if (!acknowledgements.add(key) || !contracts.contains(parts[0])) {
                throw new IllegalArgumentException("Accusé de lecture dupliqué ou contrat inexistant.");
            }
            LocalDate.parse(parts[1]);
        }
    }
}

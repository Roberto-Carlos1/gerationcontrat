package mg.pacte.repository;

import java.util.ArrayList;
import java.util.List;
import mg.pacte.model.Registry.*;
import mg.pacte.ai.AiModels;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class RegistryRepository {
    private final JdbcTemplate jdbc;
    private final JsonMapper mapper;

    public RegistryRepository(JdbcTemplate jdbc, JsonMapper mapper) { this.jdbc = jdbc; this.mapper=mapper; }

    /** Le verrou protège aussi la cohérence des lectures sur plusieurs tables. */
    public long lockRevision() {
        return jdbc.queryForObject("SELECT revision FROM registry_metadata WHERE id=1 FOR UPDATE", Long.class);
    }

    public boolean initialized() {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT initialized FROM registry_metadata WHERE id=1", Boolean.class));
    }

    public void markInitialized() {
        jdbc.update("UPDATE registry_metadata SET initialized=TRUE WHERE id=1");
    }

    public Data read() {
        var contracts = jdbc.query("SELECT * FROM contracts ORDER BY position", (rs, n) -> new Contract(
                rs.getString("id"), rs.getString("name"), rs.getString("supplier"), rs.getString("category"),
                rs.getBigDecimal("amount"), rs.getString("frequency"), rs.getDate("start_date").toLocalDate(),
                rs.getDate("end_date")==null?null:rs.getDate("end_date").toLocalDate(), rs.getInt("notice"), rs.getString("renew"),
                rs.getString("owner"), rs.getString("status"), rs.getString("penalty"), rs.getString("notes"),
                rs.getString("details")==null?null:mapper.readValue(rs.getString("details"),Details.class)));
        var disputes = jdbc.query("SELECT * FROM disputes ORDER BY position", (rs, n) -> new Dispute(
                rs.getString("id"), rs.getString("contract_id"), rs.getString("title"), rs.getString("description"),
                rs.getBigDecimal("amount"), rs.getString("owner"), rs.getInt("stage"),
                jdbc.queryForList("SELECT description FROM dispute_history WHERE dispute_id=? ORDER BY position", String.class, rs.getString("id"))));
        var analyses = jdbc.query("SELECT * FROM analyses ORDER BY id", (rs, n) -> {
            int id = rs.getInt("id");
            var groups = jdbc.query("SELECT * FROM analysis_groups WHERE analysis_id=? ORDER BY position", (g, gn) ->
                    new ClauseGroup(g.getString("label"), jdbc.queryForList(
                            "SELECT excerpt FROM analysis_excerpts WHERE analysis_id=? AND group_position=? ORDER BY position",
                            String.class, id, g.getInt("position"))), id);
            return new Analysis(rs.getString("contract_name"), rs.getString("created_label"), groups, rs.getBoolean("validated"),
                    rs.getString("ai_details")==null?null:mapper.readValue(rs.getString("ai_details"),AiModels.Details.class));
        });
        return new Data(contracts, disputes, jdbc.queryForList("SELECT value FROM acknowledgements ORDER BY value", String.class), analyses);
    }

    /** Remplacement atomique de l'instantané, compatible avec l'import JSON existant. */
    public void replace(Data data) {
        jdbc.update("DELETE FROM acknowledgements");
        jdbc.update("DELETE FROM disputes");
        // Les contrats sont mis à jour pour conserver leurs PDF associés.
        jdbc.update("UPDATE contracts SET position=-position-1");
        jdbc.update("DELETE FROM analyses");
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < data.contracts().size(); i++) {
            var c = data.contracts().get(i);
            rows.add(new Object[]{c.id(), i, c.name(), c.supplier(), c.category(), c.amount(), c.frequency(),
                    c.start(), c.end(), c.notice(), c.renew(), c.owner(), c.status(), c.penalty(), c.notes(), c.details()==null?null:mapper.writeValueAsString(c.details())});
        }
        jdbc.batchUpdate("""
                INSERT INTO contracts (id,position,name,supplier,category,amount,frequency,start_date,end_date,notice,renew,owner,status,penalty,notes,details) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CAST(? AS jsonb))
                ON CONFLICT(id) DO UPDATE SET position=EXCLUDED.position,name=EXCLUDED.name,supplier=EXCLUDED.supplier,
                category=EXCLUDED.category,amount=EXCLUDED.amount,frequency=EXCLUDED.frequency,
                start_date=EXCLUDED.start_date,end_date=EXCLUDED.end_date,notice=EXCLUDED.notice,
                renew=EXCLUDED.renew,owner=EXCLUDED.owner,status=EXCLUDED.status,penalty=EXCLUDED.penalty,notes=EXCLUDED.notes,details=EXCLUDED.details
                """, rows);
        jdbc.update("DELETE FROM contracts WHERE position<0");
        rows.clear();
        List<Object[]> history = new ArrayList<>();
        for (int i = 0; i < data.cases().size(); i++) {
            var c = data.cases().get(i);
            rows.add(new Object[]{c.id(), i, c.contract(), c.title(), c.description(), c.amount(), c.owner(), c.stage()});
            for (int j = 0; j < c.history().size(); j++) history.add(new Object[]{c.id(), j, c.history().get(j)});
        }
        jdbc.batchUpdate("INSERT INTO disputes VALUES (?,?,?,?,?,?,?,?)", rows);
        jdbc.batchUpdate("INSERT INTO dispute_history VALUES (?,?,?)", history);
        List<Object[]> analyses = new ArrayList<>(), groups = new ArrayList<>(), excerpts = new ArrayList<>();
        for (int i = 0; i < data.analysis().size(); i++) {
            var a = data.analysis().get(i);
            analyses.add(new Object[]{i, a.contract(), a.date(), a.validated(), a.ai()==null?null:mapper.writeValueAsString(a.ai())});
            for (int j = 0; j < a.groups().size(); j++) {
                var g = a.groups().get(j);
                groups.add(new Object[]{i, j, g.label()});
                for (int k = 0; k < g.excerpts().size(); k++) excerpts.add(new Object[]{i,j,k,g.excerpts().get(k)});
            }
        }
        jdbc.batchUpdate("INSERT INTO analyses (id,contract_name,created_label,validated,ai_details) VALUES (?,?,?,?,CAST(? AS jsonb))", analyses);
        jdbc.batchUpdate("INSERT INTO analysis_groups VALUES (?,?,?)", groups);
        jdbc.batchUpdate("INSERT INTO analysis_excerpts VALUES (?,?,?,?)", excerpts);
        rows.clear();
        for (String key : data.done()) rows.add(new Object[]{key, key.substring(0, key.indexOf(':'))});
        jdbc.batchUpdate("INSERT INTO acknowledgements VALUES (?,?)", rows);
        jdbc.update("UPDATE registry_metadata SET revision=revision+1 WHERE id=1");
    }
}

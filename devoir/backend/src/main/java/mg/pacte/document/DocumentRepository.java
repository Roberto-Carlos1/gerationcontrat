package mg.pacte.document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class DocumentRepository {
    public record Metadata(UUID id,String contractId,String filename,long size,int pages,int characters,Instant uploadedAt) {}
    public record Stored(Metadata metadata,byte[] content,String text) {}
    private final JdbcTemplate jdbc;
    public DocumentRepository(JdbcTemplate jdbc) { this.jdbc=jdbc; }

    public boolean contractExists(String id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM contracts WHERE id=?)",Boolean.class,id));
    }
    public List<Metadata> list(String contract) {
        return jdbc.query("SELECT id,contract_id,filename,byte_size,pages,length(extracted_text) AS characters,uploaded_at FROM documents WHERE contract_id=? ORDER BY uploaded_at DESC,id",
                (rs,n)->new Metadata(rs.getObject("id",UUID.class),rs.getString("contract_id"),rs.getString("filename"),
                        rs.getLong("byte_size"),rs.getInt("pages"),rs.getInt("characters"),rs.getTimestamp("uploaded_at").toInstant()),contract);
    }
    public Metadata insert(String contract,String filename,byte[] bytes,PdfExtractor.Extracted extracted,Instant now) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO documents(id,contract_id,filename,byte_size,pages,content,extracted_text,uploaded_at) VALUES(?,?,?,?,?,?,?,?)",
                id,contract,filename,bytes.length,extracted.pages(),bytes,extracted.text(),java.sql.Timestamp.from(now));
        return new Metadata(id,contract,filename,bytes.length,extracted.pages(),extracted.text().length(),now);
    }
    public Stored get(UUID id) {
        var rows=jdbc.query("SELECT * FROM documents WHERE id=?",(rs,n)-> {
            var metadata=new Metadata(id,rs.getString("contract_id"),rs.getString("filename"),rs.getLong("byte_size"),
                    rs.getInt("pages"),rs.getString("extracted_text").length(),rs.getTimestamp("uploaded_at").toInstant());
            return new Stored(metadata,rs.getBytes("content"),rs.getString("extracted_text"));
        },id);
        return rows.isEmpty()?null:rows.getFirst();
    }
    public void incrementRevision() { jdbc.update("UPDATE registry_metadata SET revision=revision+1 WHERE id=1"); }

    public void updateText(UUID id,String text) { jdbc.update("UPDATE documents SET extracted_text=? WHERE id=?",text,id); }
    public boolean backupFits(long maximum) {return Boolean.TRUE.equals(jdbc.queryForObject("SELECT COUNT(*)<=1000 AND COALESCE(SUM(byte_size+octet_length(extracted_text)),0)<=? FROM documents",Boolean.class,maximum));}
    public List<Stored> all() {return jdbc.queryForList("SELECT id FROM documents ORDER BY id",UUID.class).stream().map(this::get).toList();}
    public void deleteAll() {jdbc.update("DELETE FROM documents");}
    public void restore(Stored stored) {
        var m=stored.metadata();
        jdbc.update("INSERT INTO documents VALUES(?,?,?,?,?,?,?,?)",m.id(),m.contractId(),m.filename(),m.size(),m.pages(),stored.content(),stored.text(),java.sql.Timestamp.from(m.uploadedAt()));
    }
}

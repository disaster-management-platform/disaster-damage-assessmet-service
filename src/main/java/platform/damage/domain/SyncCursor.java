package platform.damage.domain;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="sync_cursors",schema="assessments")
public class SyncCursor {
    @Id public UUID id;
    @Column(nullable=false,length=200) public String actor;
    @Column(nullable=false,length=200) public String organization;
    @Column(nullable=false,length=64) public String scope;
    public Integer disasterId;
    @Column(columnDefinition="text") public String lowerSnapshot;
    @Column(columnDefinition="text") public String upperSnapshot;
    public UUID lastEvaluation;
    @Column(nullable=false) public OffsetDateTime expiresAt;
}

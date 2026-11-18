package platform.damage.domain;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="evaluation_history",schema="assessments")
public class EvaluationHistory {
    @Id @Column(updatable=false) public UUID id;
    @Column(nullable=false,updatable=false) public UUID evaluationId;
    @Column(nullable=false,updatable=false) public long revision;
    @Column(nullable=false,updatable=false) public long version;
    @Column(length=200,updatable=false) public String actor;
    @Column(nullable=false,length=200,updatable=false) public String organization;
    @Column(nullable=false,updatable=false) public OffsetDateTime recordedAt;
    @Column(nullable=false,length=40,updatable=false) public String action;
    @Column(length=2000,updatable=false) public String reason;
    @Column(nullable=false,columnDefinition="text",updatable=false) public String snapshot;
}

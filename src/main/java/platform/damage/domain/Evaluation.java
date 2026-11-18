package platform.damage.domain;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.math.BigDecimal;
import jakarta.persistence.*;
@Entity @Table(name="evaluations", schema="assessments")
public class Evaluation {
    @Id public UUID evaluationId;
    @Column(nullable=false,unique=true,updatable=false) public UUID clientRequestId;
    public Integer disasterId;
    @Column(nullable=false,length=200,updatable=false) public String assetId;
    @Column(nullable=false,length=200) public String assetType;
    @Column(nullable=false,length=120) public String assetTypeId;
    @Column(nullable=false,length=120) public String damageCategoryId;
    @Column(nullable=false) public OffsetDateTime evaluatedAt;
    @Column(nullable=false,length=200,updatable=false) public String evaluator;
    @Column(nullable=false,length=200,updatable=false) public String organization;
    @Column(nullable=false,length=200) public String location;
    public Double latitude;
    public Double longitude;
    @Column(columnDefinition="text") public String geometry;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public DamageLevel damageLevel;
    @Column(nullable=false,length=2000) public String description;
    @Column(nullable=false,columnDefinition="text") public String evidence;
    @Column(nullable=false,precision=19,scale=2) public BigDecimal budget;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) public EvaluationStatus status = EvaluationStatus.Draft;
    @Column(nullable=false,updatable=false) public long revision = 1;
    @Version public long version;
    @Column(nullable=false,updatable=false) public OffsetDateTime createdAt;
    @Column(nullable=false) public OffsetDateTime updatedAt;
    @Column(nullable=false,columnDefinition="text",updatable=false) public String creationCommand;
    @Column(nullable=false,columnDefinition="text",updatable=false) public String creationResult;
}

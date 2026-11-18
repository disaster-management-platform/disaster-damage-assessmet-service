package platform.damage.domain;
import jakarta.persistence.*;
import java.time.OffsetDateTime;
import java.util.UUID;
@Entity @Table(name="evidence",schema="assessments")
public class Evidence {
    public enum Status { PendingUpload,Verified,Rejected,Expired }
    @Id public UUID evidenceId;
    @Column(nullable=false,updatable=false) public UUID evaluationId;
    @Column(nullable=false,updatable=false) public long revision;
    @Column(nullable=false,updatable=false) public UUID clientRequestId;
    @Column(nullable=false,length=200,updatable=false) public String organizationId;
    @Column(nullable=false,length=200,updatable=false) public String uploadedBy;
    @Column(nullable=false,length=200,updatable=false) public String originalFileName;
    @Column(nullable=false,length=100,updatable=false) public String contentType;
    @Column(nullable=false,updatable=false) public long size;
    @Column(nullable=false,length=64,updatable=false) public String sha256;
    @Column(nullable=false,length=300,updatable=false) public String storageObjectKey;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20) public Status status;
    @Column(nullable=false,updatable=false) public OffsetDateTime createdAt;
    public OffsetDateTime confirmedAt;
    @Column(nullable=false) public OffsetDateTime expiresAt;
    public OffsetDateTime temporaryDeletedAt;
    public UUID leaseId;
    public OffsetDateTime leaseUntil;
    @Version public long lockVersion;
}

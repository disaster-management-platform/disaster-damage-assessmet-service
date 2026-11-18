package platform.damage.business;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.Semaphore;
@ApplicationScoped
public class EvidenceCapacity {
    private final long maximumBytes=number("EVIDENCE_MAX_BYTES",6*1024*1024,1,6*1024*1024);
    private final int maximumEvidence=number("EVIDENCE_MAX_PER_EVALUATION",20,1,50);
    private final int maximumPending=number("EVIDENCE_MAX_PENDING_ORGANIZATION",100,1,1000);
    private final Semaphore verifications=new Semaphore(number("EVIDENCE_VERIFY_CONCURRENCY",2,1,4));
    public long maximumBytes() { return maximumBytes; }
    public int maximumEvidence() { return maximumEvidence; }
    public int maximumPending() { return maximumPending; }
    public boolean acquire() { return verifications.tryAcquire(); }
    public void release() { verifications.release(); }
    private int number(String name,int fallback,int minimum,int maximum) { String value=System.getenv(name);int n=value==null?fallback:Integer.parseInt(value);if(n<minimum||n>maximum)throw new IllegalArgumentException(name);return n; }
}

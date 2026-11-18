package platform.damage.persistence;
import jakarta.ejb.*;
import jakarta.persistence.*;
import jakarta.ws.rs.WebApplicationException;
import platform.damage.domain.*;
import java.time.*;
import java.util.*;
@Stateless @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class EvidenceStore {
    @PersistenceContext(unitName="damage") EntityManager em;
    private OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }
    public Evidence get(UUID id,UUID evaluation) {
        var e=em.find(Evidence.class,id);if(e==null||!e.evaluationId.equals(evaluation))throw new WebApplicationException(404);return e;
    }
    public List<Evidence> list(UUID evaluation) { return em.createQuery("select e from Evidence e where e.evaluationId=:id order by e.createdAt",Evidence.class).setParameter("id",evaluation).getResultList(); }
    public Evidence intent(UUID evaluation,UUID request,String organization,String actor,String name,String mime,long size,String hash,int maximum,int maximumPending) {
        em.createNativeQuery("select pg_advisory_xact_lock(hashtextextended(:organization,74004))").setParameter("organization",organization).getSingleResult();
        var previous=em.createQuery("select e from Evidence e where e.clientRequestId=:id",Evidence.class).setParameter("id",request).getResultList();
        if(!previous.isEmpty()) {
            var e=previous.getFirst();
            if(!e.evaluationId.equals(evaluation)||!e.organizationId.equals(organization)||!e.uploadedBy.equals(actor))throw new WebApplicationException(404);
            if(!e.originalFileName.equals(name)||!e.contentType.equals(mime)||e.size!=size||!e.sha256.equals(hash))throw new WebApplicationException(409);
            return e;
        }
        var owner=em.find(Evaluation.class,evaluation,LockModeType.PESSIMISTIC_WRITE);
        if(owner==null)throw new WebApplicationException(404);
        if(!owner.organization.equals(organization))throw new WebApplicationException(403);
        if(owner.status!=EvaluationStatus.Draft)throw new WebApplicationException(409);
        long pending=em.createQuery("select count(e) from Evidence e where e.organizationId=:organization and e.status<>:status and e.expiresAt>:now",Long.class).setParameter("organization",organization).setParameter("status",Evidence.Status.Verified).setParameter("now",now()).getSingleResult();
        long count=em.createQuery("select count(e) from Evidence e where e.evaluationId=:id and e.status in (:pending,:verified)",Long.class).setParameter("id",evaluation).setParameter("pending",Evidence.Status.PendingUpload).setParameter("verified",Evidence.Status.Verified).getSingleResult();
        if(pending>=maximumPending||count>=maximum)throw new WebApplicationException(429);
        var e=new Evidence();e.evidenceId=UUID.randomUUID();e.evaluationId=evaluation;e.revision=owner.revision;e.clientRequestId=request;
        e.organizationId=organization;e.uploadedBy=actor;e.originalFileName=name;e.contentType=mime;e.size=size;e.sha256=hash;
        e.storageObjectKey="verified/"+evaluation+"/"+e.evidenceId;e.status=Evidence.Status.PendingUpload;e.createdAt=now();e.expiresAt=now().plusHours(3);em.persist(e);em.flush();return e;
    }
    public Evidence authorizeUpload(UUID id,UUID evaluation) {
        var e=em.find(Evidence.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(e==null||!e.evaluationId.equals(evaluation))throw new WebApplicationException(404);
        var owner=em.find(Evaluation.class,evaluation);
        if(owner.status!=EvaluationStatus.Draft||owner.revision!=e.revision)throw new WebApplicationException(409);
        if(e.status!=Evidence.Status.PendingUpload||e.expiresAt.isBefore(now()))throw new WebApplicationException(409);
        // A fresh provider authorization can remain usable for two hours.
        e.expiresAt=now().plusHours(3);em.flush();return e;
    }
    public Evidence claim(UUID id,UUID evaluation,UUID lease) {
        var e=em.find(Evidence.class,id,LockModeType.PESSIMISTIC_WRITE);
        if(e==null||!e.evaluationId.equals(evaluation))throw new WebApplicationException(404);
        if(e.status==Evidence.Status.Verified)return e;
        if(e.status!=Evidence.Status.PendingUpload)throw new WebApplicationException(422);
        if(e.expiresAt.isBefore(now())){e.status=Evidence.Status.Expired;em.flush();return e;}
        if(e.leaseUntil!=null&&e.leaseUntil.isAfter(now()))throw new WebApplicationException(409);
        e.leaseId=lease;e.leaseUntil=now().plusMinutes(5);em.flush();return e;
    }
    public Evidence finish(UUID id,UUID evaluation,UUID lease,boolean valid) {
        var e=get(id,evaluation);if(e.status==Evidence.Status.Verified)return e;
        if(!Objects.equals(e.leaseId,lease))throw new WebApplicationException(409);
        var owner=em.find(Evaluation.class,evaluation,LockModeType.PESSIMISTIC_READ);
        if(owner.status!=EvaluationStatus.Draft||owner.revision!=e.revision)throw new WebApplicationException(409);
        e.status=valid?Evidence.Status.Verified:Evidence.Status.Rejected;e.confirmedAt=valid?now():null;e.leaseUntil=null;e.leaseId=null;em.flush();return e;
    }
    public void release(UUID id,UUID evaluation,UUID lease) {
        var e=get(id,evaluation);if(e.status==Evidence.Status.PendingUpload&&Objects.equals(e.leaseId,lease)){e.leaseUntil=null;e.leaseId=null;}
    }
    public void cleaned(UUID id,UUID evaluation) {
        var e=get(id,evaluation);if(e.expiresAt.isBefore(now()))e.temporaryDeletedAt=now();
    }
    public List<Evidence> expired() {
        var result=em.createQuery("select e from Evidence e where e.expiresAt<:now and e.temporaryDeletedAt is null and (e.leaseUntil is null or e.leaseUntil<:now) order by e.expiresAt",Evidence.class).setParameter("now",now()).setMaxResults(5).getResultList();
        for(var e:result)if(e.status==Evidence.Status.PendingUpload)e.status=Evidence.Status.Expired;
        em.flush();return result;
    }
}

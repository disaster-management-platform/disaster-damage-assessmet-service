package platform.damage.persistence;
import jakarta.ejb.*;
import jakarta.persistence.*;
import jakarta.ws.rs.WebApplicationException;
import platform.damage.domain.*;
import platform.damage.api.Views;
import java.time.*;
import java.util.*;
@Stateless @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class AssessmentSyncStore {
    @PersistenceContext(unitName="damage") EntityManager em;
    public Object page(Integer disaster,UUID cursor,int limit,String actor,String organization,String scope) {
        var now=OffsetDateTime.now(ZoneOffset.UTC);SyncCursor previous=null;
        if(cursor!=null) {
            previous=em.find(SyncCursor.class,cursor);
            if(previous==null||previous.expiresAt.isBefore(now)||!previous.actor.equals(actor)||!previous.organization.equals(organization)||!previous.scope.equals(scope)||!Objects.equals(previous.disasterId,disaster))throw new WebApplicationException("Cursor incompatible; reinicie la descarga",409);
        }
        String lower=previous==null?null:previous.lowerSnapshot;
        String upper=previous==null?null:previous.upperSnapshot;
        UUID last=previous==null?null:previous.lastEvaluation;
        if(upper==null)upper=(String)em.createNativeQuery("select pg_current_snapshot()::text").getSingleResult();
        String query="select e.* from assessments.evaluations e where e.organization=:organization and e.disasterId is not distinct from cast(nullif(:disaster,'') as integer) and (cast(nullif(:last,'') as uuid) is null or e.evaluationId>cast(nullif(:last,'') as uuid)) and exists (select 1 from assessments.evaluation_history h where h.evaluationId=e.evaluationId and pg_visible_in_snapshot(h.transactionId,cast(:upper as pg_snapshot)) and (cast(nullif(:lower,'') as pg_snapshot) is null or not pg_visible_in_snapshot(h.transactionId,cast(nullif(:lower,'') as pg_snapshot)))) order by e.evaluationId";
        @SuppressWarnings("unchecked")
        List<Evaluation> values=em.createNativeQuery(query,Evaluation.class).setParameter("organization",organization).setParameter("disaster",disaster==null?"":disaster.toString()).setParameter("last",last==null?"":last.toString()).setParameter("upper",upper).setParameter("lower",lower==null?"":lower).setMaxResults(limit+1).getResultList();
        boolean more=values.size()>limit;var page=values.subList(0,Math.min(values.size(),limit));
        var next=new SyncCursor();next.id=UUID.randomUUID();next.actor=actor;next.organization=organization;next.scope=scope;next.disasterId=disaster;next.expiresAt=now.plusDays(7);
        next.lowerSnapshot=more?lower:upper;next.upperSnapshot=more?upper:null;next.lastEvaluation=more?page.getLast().evaluationId:null;em.persist(next);
        em.createQuery("delete from SyncCursor c where c.expiresAt<:now").setParameter("now",now).executeUpdate();
        return Map.of("evaluations",page.stream().map(Views::evaluation).toList(),"nextCursor",next.id.toString(),"hasMore",more);
    }
}

package platform.damage.persistence;
import jakarta.ejb.*;
import jakarta.persistence.*;
import jakarta.ws.rs.WebApplicationException;
import platform.damage.api.*;
import platform.damage.domain.*;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
@Stateless @TransactionAttribute(TransactionAttributeType.REQUIRES_NEW)
public class EvaluationStore {
    @PersistenceContext(unitName="damage") EntityManager em;
    public Evaluation get(UUID id) { return em.find(Evaluation.class,id); }
    public Evaluation byRequest(UUID id) { return em.createQuery("select e from Evaluation e where e.clientRequestId=:id",Evaluation.class).setParameter("id",id).getResultStream().findFirst().orElse(null); }
    public List<Evaluation> list(int disasterId) { return em.createQuery("select e from Evaluation e where e.disasterId=:id order by e.evaluationId",Evaluation.class).setParameter("id",disasterId).getResultList(); }
    public Map<String,Object> catalogs() { return Map.of("assetTypes",em.createQuery("select a from AssetType a order by a.id",AssetType.class).getResultList(),"damageCategories",em.createQuery("select a from DamageCategory a order by a.id",DamageCategory.class).getResultList()); }
    private void apply(Evaluation e, EvaluationInput input) {
        var type=em.find(AssetType.class,input.assetTypeId,LockModeType.PESSIMISTIC_READ);
        var category=em.find(DamageCategory.class,input.damageCategoryId,LockModeType.PESSIMISTIC_READ);
        if(type==null||category==null||(!type.active&&!type.id.equals(e.assetTypeId))||(!category.active&&!category.id.equals(e.damageCategoryId))) throw new WebApplicationException("Catálogo inexistente o inactivo",422);
        e.assetId=input.assetId;e.assetTypeId=type.id;e.assetType=type.name;e.damageCategoryId=category.id;e.damageLevel=category.damageLevel;
        e.evaluatedAt=input.evaluatedAt;e.location=input.location;e.latitude=input.latitude;e.longitude=input.longitude;
        e.geometry=input.geometry==null?null:input.geometry.toString();e.description=input.description;
        e.evidence=Json.CODEC.toJson(input.evidence);e.budget=input.budget;
        e.updatedAt=OffsetDateTime.now(ZoneOffset.UTC);
    }
    public String create(UUID requestId,String command,EvaluationInput input,String actor,String organization) {
        var e=new Evaluation();e.evaluationId=UUID.randomUUID();e.clientRequestId=requestId;e.disasterId=input.disasterId;
        e.evaluator=actor;e.organization=organization;apply(e,input);e.createdAt=e.updatedAt;e.creationCommand=command;
        e.creationResult=Json.CODEC.toJson(Views.evaluation(e));em.persist(e);em.flush();
        return e.creationResult;
    }
    public Map<String,Object> update(UUID id,long version,EvaluationInput input,String organization) {
        var e=em.find(Evaluation.class,id);if(e==null)throw new WebApplicationException(404);
        if(!e.organization.equals(organization))throw new WebApplicationException(403);
        if(e.version!=version)throw new WebApplicationException(409);
        if(e.status!=EvaluationStatus.Draft)throw new WebApplicationException("Solo Draft es editable",422);
        if(!Objects.equals(e.disasterId,input.disasterId)||!e.assetId.equals(input.assetId))throw new WebApplicationException("Use la asociación explícita; assetId es estable",422);
        apply(e,input);em.flush();return Views.evaluation(e);
    }
    public Map<String,Object> associate(UUID id,long version,int disasterId,String organization) {
        var e=em.find(Evaluation.class,id);if(e==null)throw new WebApplicationException(404);
        if(!e.organization.equals(organization))throw new WebApplicationException(403);
        if(e.version!=version)throw new WebApplicationException(409);
        if(e.status!=EvaluationStatus.Draft||e.disasterId!=null)throw new WebApplicationException("Solo un borrador preliminar puede asociarse",422);
        e.disasterId=disasterId;e.updatedAt=OffsetDateTime.now(ZoneOffset.UTC);em.flush();return Views.evaluation(e);
    }
    public Object addType(String id,String name) {
        if(em.find(AssetType.class,id)!=null)throw new WebApplicationException(409);
        var e=new AssetType();e.id=id;e.name=name;em.persist(e);em.flush();return e;
    }
    public Object addCategory(String id,String name,DamageLevel level) {
        if(em.find(DamageCategory.class,id)!=null)throw new WebApplicationException(409);
        var e=new DamageCategory();e.id=id;e.name=name;e.damageLevel=level;em.persist(e);em.flush();return e;
    }
    public void deactivateType(String id) { var e=em.find(AssetType.class,id);if(e==null)throw new WebApplicationException(404);e.active=false; }
    public void deactivateCategory(String id) { var e=em.find(DamageCategory.class,id);if(e==null)throw new WebApplicationException(404);e.active=false; }
    public boolean ready() { em.createNativeQuery("select 1 from damage_assessment.evaluations limit 1").getResultList();return true; }
}

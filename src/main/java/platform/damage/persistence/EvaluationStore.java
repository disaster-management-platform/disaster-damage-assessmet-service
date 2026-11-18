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
    private List<String> references(String json) { return jakarta.json.Json.createReader(new java.io.StringReader(json)).readArray().getValuesAs(jakarta.json.JsonString.class).stream().map(jakarta.json.JsonString::getString).toList(); }
    private void evidence(Evaluation e,List<String> references,boolean validating) {
        var previous=e.evidence==null?Set.<String>of():new HashSet<>(references(e.evidence));
        for(String reference:references) {
            if(!validating&&previous.contains(reference))continue;
            if(!reference.startsWith("attachment:"))throw new WebApplicationException("Referencia de evidencia no verificada",422);
            UUID id;try{id=UUID.fromString(reference.substring(11));}catch(Exception failure){throw new WebApplicationException(422);}
            var file=em.find(Evidence.class,id);
            if(file==null||file.status!=Evidence.Status.Verified||!Objects.equals(file.evaluationId,e.evaluationId)||!file.organizationId.equals(e.organization)||file.revision>e.revision)throw new WebApplicationException("Evidencia incompatible",422);
        }
    }
    private void apply(Evaluation e, EvaluationInput input) {
        var type=em.find(AssetType.class,input.assetTypeId,LockModeType.PESSIMISTIC_READ);
        var category=em.find(DamageCategory.class,input.damageCategoryId,LockModeType.PESSIMISTIC_READ);
        if(type==null||category==null||(!type.active&&!type.id.equals(e.assetTypeId))||(!category.active&&!category.id.equals(e.damageCategoryId))||(category.assetTypeId!=null&&!category.assetTypeId.equals(type.id))) throw new WebApplicationException("Catálogo inexistente o inactivo",422);
        evidence(e,input.evidence,false);
        e.assetId=input.assetId;e.assetTypeId=type.id;e.assetType=type.name;e.damageCategoryId=category.id;e.damageLevel=category.damageLevel;
        e.evaluatedAt=input.evaluatedAt;e.location=input.location;e.latitude=input.latitude;e.longitude=input.longitude;
        e.geometry=input.geometry==null?null:input.geometry.toString();e.description=input.description;
        e.evidence=Json.CODEC.toJson(input.evidence);e.budget=input.budget;
        e.updatedAt=OffsetDateTime.now(ZoneOffset.UTC);
    }
    public String create(UUID requestId,String command,EvaluationInput input,String actor,String organization) {
        var e=new Evaluation();e.evaluationId=UUID.randomUUID();e.clientRequestId=requestId;e.disasterId=input.disasterId;
        e.evaluator=actor;e.organization=organization;apply(e,input);e.createdAt=e.updatedAt;e.creationCommand=command;
        e.creationResult=Json.CODEC.toJson(Views.evaluation(e));em.persist(e);em.flush();record(e,actor,"Created",null);
        return e.creationResult;
    }
    public Map<String,Object> update(UUID id,long version,EvaluationInput input,String organization,String actor) {
        var e=writable(id,version,organization);
        if(e.status!=EvaluationStatus.Draft)throw new WebApplicationException("Solo Draft es editable",422);
        if(!Objects.equals(e.disasterId,input.disasterId)||!e.assetId.equals(input.assetId))throw new WebApplicationException("Use la asociación explícita; assetId es estable",422);
        apply(e,input);em.flush();record(e,actor,"Edited",null);return Views.evaluation(e);
    }
    public Map<String,Object> associate(UUID id,long version,int disasterId,String organization,String actor) {
        var e=writable(id,version,organization);
        if(e.status!=EvaluationStatus.Draft||e.disasterId!=null)throw new WebApplicationException("Solo un borrador preliminar puede asociarse",422);
        e.disasterId=disasterId;e.updatedAt=OffsetDateTime.now(ZoneOffset.UTC);em.flush();record(e,actor,"Associated",null);return Views.evaluation(e);
    }
    public Object addType(String id,String name) {
        if(em.find(AssetType.class,id)!=null)throw new WebApplicationException(409);
        var e=new AssetType();e.id=id;e.name=name;em.persist(e);em.flush();return e;
    }
    public Object addCategory(String id,String name,DamageLevel level,String assetTypeId) {
        if(em.find(DamageCategory.class,id)!=null)throw new WebApplicationException(409);
        if(assetTypeId!=null&&em.find(AssetType.class,assetTypeId)==null)throw new WebApplicationException(422);
        var e=new DamageCategory();e.assetTypeId=assetTypeId;e.id=id;e.name=name;e.damageLevel=level;em.persist(e);em.flush();return e;
    }
    public void deactivateType(String id) { var e=em.find(AssetType.class,id);if(e==null)throw new WebApplicationException(404);e.active=false; }
    public void deactivateCategory(String id) { var e=em.find(DamageCategory.class,id);if(e==null)throw new WebApplicationException(404);e.active=false; }
    private void record(Evaluation e,String actor,String action,String reason) {
        var h=new EvaluationHistory();h.id=UUID.randomUUID();h.evaluationId=e.evaluationId;h.revision=e.revision;h.version=e.version;
        h.actor=actor;h.organization=e.organization;h.recordedAt=OffsetDateTime.now(ZoneOffset.UTC);h.action=action;h.reason=reason;
        h.snapshot=Json.CODEC.toJson(Views.evaluation(e));em.persist(h);em.flush();
    }
    private Evaluation writable(UUID id,long version,String organization) {
        var e=em.find(Evaluation.class,id);if(e==null)throw new WebApplicationException(404);
        if(!e.organization.equals(organization))throw new WebApplicationException(403);
        if(e.version!=version)throw new WebApplicationException(409);return e;
    }
    public Map<String,Object> transition(UUID id,long version,EvaluationStatus target,String reason,String organization,String actor) {
        var e=writable(id,version,organization);
        if(e.status!=EvaluationStatus.Draft||target==EvaluationStatus.Draft||target==null)throw new WebApplicationException(422);
        if(target==EvaluationStatus.Validated&&e.disasterId==null)throw new WebApplicationException("Asocie un desastre antes de validar",422);
        if(target==EvaluationStatus.Validated)evidence(e,references(e.evidence),true);
        e.status=target;e.updatedAt=OffsetDateTime.now(ZoneOffset.UTC);em.flush();record(e,actor,target.name(),reason);return Views.evaluation(e);
    }
    public Map<String,Object> revise(UUID id,long version,EvaluationInput input,String organization,String actor) {
        var e=writable(id,version,organization);
        if(e.status==EvaluationStatus.Draft||!Objects.equals(e.disasterId,input.disasterId)||!e.assetId.equals(input.assetId))throw new WebApplicationException(422);
        apply(e,input);e.revision++;e.status=EvaluationStatus.Draft;em.flush();record(e,actor,"RevisionCreated",null);return Views.evaluation(e);
    }
    public List<EvaluationHistory> history(UUID id) {
        return em.createQuery("select h from EvaluationHistory h where h.evaluationId=:id order by h.version",EvaluationHistory.class).setParameter("id",id).getResultList();
    }
    public boolean ready() { em.createNativeQuery("select 1 from assessments.evaluations limit 1").getResultList();return true; }
}

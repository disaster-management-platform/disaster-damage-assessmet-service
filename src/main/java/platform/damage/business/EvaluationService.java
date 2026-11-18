package platform.damage.business;
import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import jakarta.validation.Validator;
import platform.damage.api.*;
import platform.damage.domain.*;
import platform.damage.persistence.EvaluationStore;
import java.util.*;
import java.time.ZoneOffset;
import org.locationtech.jts.io.geojson.GeoJsonReader;
@Stateless @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class EvaluationService {
    @EJB EvaluationStore store;
    @Inject DisasterReader disasters;
    @Inject Validator validator;
    public void validate(EvaluationInput input) {
        if(input==null||!validator.validate(input).isEmpty())throw new WebApplicationException("Datos inválidos",400);
        if(input.latitude!=null&&!Double.isFinite(input.latitude)||input.longitude!=null&&!Double.isFinite(input.longitude)
            ||(input.latitude==null)!=(input.longitude==null))throw new WebApplicationException("Coordenadas inválidas",422);
        if(input.geometry!=null) {
            try {
                if(input.geometry.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length>65536||input.geometry.containsKey("crs"))throw new Exception();
                var g=new GeoJsonReader().read(input.geometry.toString());
                if(g.isEmpty()||!g.isValid()||g.getNumPoints()>2000||!Set.of("Point","Polygon","MultiPolygon").contains(g.getGeometryType()))throw new Exception();
                for(var c:g.getCoordinates())if(!Double.isFinite(c.x)||!Double.isFinite(c.y)||c.x< -180||c.x>180||c.y< -90||c.y>90)throw new Exception();
            }catch(Exception e){throw new WebApplicationException("GeoJSON WGS84 inválido",422);}
        }
        input.budget=input.budget.setScale(2);input.evaluatedAt=input.evaluatedAt.withOffsetSameInstant(ZoneOffset.UTC);
    }
    public record Creation(String body,boolean created) { }
    public Creation create(UUID key,EvaluationInput input,String actor,String organization) {
        validate(input);input.expectedVersion=null;String command=Json.CODEC.toJson(input);
        var existing=store.byRequest(key);
        if(existing!=null)return replay(existing,command,actor,organization);
        disasters.require(input.disasterId);
        try { return new Creation(store.create(key,command,input,actor,organization),true); }
        catch(EJBException failure) { existing=store.byRequest(key);if(existing!=null)return replay(existing,command,actor,organization);throw failure; }
    }
    private Creation replay(Evaluation e,String command,String actor,String organization) {
        if(!e.organization.equals(organization)||!e.evaluator.equals(actor))throw new WebApplicationException(404);
        if(!jakarta.json.Json.createReader(new java.io.StringReader(e.creationCommand)).readObject().equals(jakarta.json.Json.createReader(new java.io.StringReader(command)).readObject()))throw new WebApplicationException("Idempotency-Key reutilizada con otro contenido",409);
        return new Creation(e.creationResult,false);
    }
    public Map<String,Object> edit(UUID id,EvaluationInput input,String organization) {
        validate(input);if(input.expectedVersion==null||input.expectedVersion<0)throw new WebApplicationException(400);
        return store.update(id,input.expectedVersion,input,organization);
    }
    public Map<String,Object> associate(UUID id,long version,int disasterId,String organization) {
        if(disasterId<=0||version<0)throw new WebApplicationException(400);disasters.require(disasterId);return store.associate(id,version,disasterId,organization);
    }
}

package platform.damage.api;
import platform.damage.domain.Evaluation;
import jakarta.json.Json;
import java.io.StringReader;
import java.util.*;
public final class Views {
    private Views() { }
    public static Map<String,Object> evaluation(Evaluation e) {
        Map<String,Object> r=new LinkedHashMap<>();
        r.put("evaluationId",e.evaluationId.toString());r.put("clientRequestId",e.clientRequestId.toString());r.put("disasterId",e.disasterId);
        r.put("assetId",e.assetId);r.put("assetType",e.assetType);r.put("assetTypeId",e.assetTypeId);r.put("damageCategoryId",e.damageCategoryId);
        r.put("evaluatedAt",e.evaluatedAt.toString());r.put("evaluator",e.evaluator);r.put("organization",e.organization);
        r.put("location",e.location);r.put("latitude",e.latitude);r.put("longitude",e.longitude);
        r.put("geometry",e.geometry==null?null:Json.createReader(new StringReader(e.geometry)).readObject());
        r.put("damageLevel",e.damageLevel.name());r.put("description",e.description);
        r.put("evidence",Json.createReader(new StringReader(e.evidence)).readArray());r.put("budget",e.budget);
        r.put("status",e.status.name());r.put("revision",e.revision);r.put("version",e.version);
        r.put("createdAt",e.createdAt.toString());r.put("updatedAt",e.updatedAt.toString());return r;
    }
}

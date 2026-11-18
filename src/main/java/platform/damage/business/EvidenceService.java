package platform.damage.business;
import jakarta.ejb.*;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import platform.damage.domain.Evidence;
import platform.damage.persistence.EvidenceStore;
import platform.damage.storage.SupabaseStorage;
import java.util.*;
@Stateless @TransactionAttribute(TransactionAttributeType.NOT_SUPPORTED)
public class EvidenceService {
    @EJB EvidenceStore store;
    @Inject SupabaseStorage storage;
    @Inject EvidenceCapacity capacity;
    public record UploadInput(String originalFileName,String contentType,Long size,String sha256) { }
    public Evidence intent(UUID evaluation,UUID key,String organization,String actor,UploadInput input) {
        if(input!=null&&input.size()!=null&&input.size()>capacity.maximumBytes())throw new WebApplicationException(413);
        if(input==null||input.originalFileName()==null||input.originalFileName().isBlank()||input.originalFileName().length()>200||input.originalFileName().contains("/")||input.originalFileName().contains("\\")||input.originalFileName().chars().anyMatch(Character::isISOControl)||input.size()==null||input.size()<=0||input.sha256()==null||!input.sha256().matches("[a-fA-F0-9]{64}"))throw new WebApplicationException(400);
        String name=input.originalFileName().toLowerCase(Locale.ROOT);
        String mime=name.endsWith(".pdf")?"application/pdf":name.endsWith(".png")?"image/png":name.endsWith(".jpg")||name.endsWith(".jpeg")?"image/jpeg":null;
        if(mime==null||!mime.equals(input.contentType()))throw new WebApplicationException(422);
        cleanup();return store.intent(evaluation,key,organization,actor,input.originalFileName(),mime,input.size(),input.sha256().toLowerCase(Locale.ROOT),capacity.maximumEvidence(),capacity.maximumPending());
    }
    public static String temporary(Evidence e) { return "pending/"+e.evaluationId+"/"+e.evidenceId; }
    public static Map<String,Object> metadata(Evidence e) {
        var m=new LinkedHashMap<String,Object>();m.put("evidenceId",e.evidenceId);m.put("evaluationId",e.evaluationId);m.put("revision",e.revision);m.put("clientRequestId",e.clientRequestId);m.put("organizationId",e.organizationId);m.put("uploadedBy",e.uploadedBy);m.put("originalFileName",e.originalFileName);m.put("contentType",e.contentType);m.put("size",e.size);m.put("sha256",e.sha256);m.put("status",e.status);m.put("createdAt",e.createdAt);m.put("confirmedAt",e.confirmedAt);m.put("expiresAt",e.expiresAt);m.put("reference",e.status==Evidence.Status.Verified?"attachment:"+e.evidenceId:null);return m;
    }
    public Object authorization(Evidence e) {
        if(e.status!=Evidence.Status.PendingUpload)return metadata(e);
        if(!capacity.acquire())throw new WebApplicationException(503);
        try {
            store.authorizeUpload(e.evidenceId,e.evaluationId);
            var signed=storage.upload(temporary(e));var result=metadata(e);result.put("uploadUrl",signed.url());result.put("uploadExpiresAt",signed.expiresAt());result.put("method","PUT");result.put("headers",Map.of("Content-Type",e.contentType,"x-upsert","false"));return result;
        }finally{capacity.release();}
    }
    private boolean matches(Evidence e,SupabaseStorage.Digest d) { return e.size==d.size()&&e.sha256.equals(d.sha256())&&e.contentType.equals(d.contentType()); }
    public Evidence confirm(UUID evaluation,UUID id) {
        if(!capacity.acquire())throw new WebApplicationException(503);
        try {
            var lease=UUID.randomUUID();var e=store.claim(id,evaluation,lease);
            if(e.status==Evidence.Status.Verified)return e;
            if(e.status!=Evidence.Status.PendingUpload)return e;
            try {
                if(!matches(e,storage.inspect(temporary(e),capacity.maximumBytes())))return store.finish(id,evaluation,lease,false);
                storage.copy(temporary(e),e.storageObjectKey);
                return store.finish(id,evaluation,lease,matches(e,storage.inspect(e.storageObjectKey,capacity.maximumBytes())));
            }catch(WebApplicationException failure) {
                if((failure.getResponse().getStatus()==422&&!"Objeto inexistente".equals(failure.getMessage()))||failure.getResponse().getStatus()==413)return store.finish(id,evaluation,lease,false);
                store.release(id,evaluation,lease);throw failure;
            }
        }finally{capacity.release();}
    }
    public void multipart(Evidence e,java.io.InputStream input) {
        if(!capacity.acquire())throw new WebApplicationException(503);
        try{storage.upload(temporary(e),e.contentType,input);}finally{capacity.release();}
    }
    public SupabaseStorage.Signed download(Evidence e) {
        if(e.status!=Evidence.Status.Verified)throw new WebApplicationException(422);
        if(!capacity.acquire())throw new WebApplicationException(503);
        try{return storage.download(e.storageObjectKey);}finally{capacity.release();}
    }
    private void cleanup() {
        if(!capacity.acquire())return;
        try{for(var e:store.expired())try{storage.deleteTemporary(temporary(e));store.cleaned(e.evidenceId,e.evaluationId);}catch(WebApplicationException failure){break;}}
        finally{capacity.release();}
    }
}

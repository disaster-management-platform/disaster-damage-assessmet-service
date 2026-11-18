package platform.damage.api;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import platform.damage.business.EvaluationService;
import platform.damage.persistence.AssessmentSyncStore;
import java.util.*;
@jakarta.enterprise.context.ApplicationScoped
@Path("/evaluaciones") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"damage.read","damage.write","damage.admin"})
public class AssessmentSyncResource {
    private final java.util.concurrent.Semaphore capacity=new java.util.concurrent.Semaphore(concurrency());
    private int concurrency(){int n=Integer.parseInt(System.getenv().getOrDefault("ASSESSMENT_SYNC_CONCURRENCY","2"));if(n<1||n>4)throw new IllegalArgumentException("ASSESSMENT_SYNC_CONCURRENCY");return n;}
    @EJB EvaluationService service;
    @EJB AssessmentSyncStore store;
    @Inject Identity identity;
    public record Operation(UUID clientRequestId,jakarta.json.JsonValue evaluation) { }
    public record Batch(List<Operation> operations) { }
    @POST @Path("/batch") @RolesAllowed({"damage.write","damage.admin"})
    public Object batch(Batch input) {
        int maximum=Integer.parseInt(System.getenv().getOrDefault("ASSESSMENT_BATCH_MAX","50"));
        if(maximum<1||maximum>50)throw new WebApplicationException(503);
        if(input==null||input.operations()==null||input.operations().isEmpty()||input.operations().size()>maximum)throw new WebApplicationException(400);
        var seen=new HashSet<UUID>();
        for(var operation:input.operations())if(operation==null||operation.clientRequestId()==null||operation.clientRequestId().equals(new UUID(0,0))||!seen.add(operation.clientRequestId()))throw new WebApplicationException(400);
        if(!capacity.tryAcquire())throw new WebApplicationException(503);
        try {
        var results=new ArrayList<Map<String,Object>>();
        for(var operation:input.operations()) {
            var result=new LinkedHashMap<String,Object>();result.put("clientRequestId",operation.clientRequestId());
            try {
                if(!(operation.evaluation() instanceof jakarta.json.JsonObject payload))throw new WebApplicationException(400);
                EvaluationInput evaluation;
                try{evaluation=Json.CODEC.fromJson(payload.toString(),EvaluationInput.class);}catch(RuntimeException invalid){throw new BadRequestException();}
                identity.disaster(evaluation.disasterId);
                var created=service.create(operation.clientRequestId(),evaluation,identity.actor(),identity.organization());
                result.put("status",created.created()?"Created":"AlreadyProcessed");
                result.put("evaluation",jakarta.json.Json.createReader(new java.io.StringReader(created.body())).readObject());
            }catch(Exception failure) {
                int status=Errors.status(failure);
                result.put("status",status==409?"Conflict":status==400||status==401||status==403||status==404||status==422?"ValidationError":"RetryableFailure");
                result.put("httpStatus",status);
            }
            results.add(result);
        }
        return Map.of("results",results);
        }finally{capacity.release();}
    }
    @GET @Path("/sync")
    public Object sync(@QueryParam("disasterId") Integer disaster,@QueryParam("cursor") UUID cursor,@QueryParam("limit") @DefaultValue("50") int limit) {
        int maximum=Integer.parseInt(System.getenv().getOrDefault("ASSESSMENT_SYNC_MAX","50"));
        if(maximum<1||maximum>100)throw new WebApplicationException(503);
        if(limit<1||limit>maximum||disaster!=null&&disaster<=0)throw new WebApplicationException(400);
        identity.disaster(disaster);
        if(!capacity.tryAcquire())throw new WebApplicationException(503);
        try{return store.page(disaster,cursor,limit,identity.actor(),identity.organization(),identity.scope());}finally{capacity.release();}
    }
}

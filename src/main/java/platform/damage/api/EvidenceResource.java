package platform.damage.api;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import platform.damage.business.*;
import platform.damage.domain.*;
import platform.damage.persistence.*;
import java.util.*;
@Path("/evaluaciones/{id}/evidencias") @Produces(MediaType.APPLICATION_JSON)
@RolesAllowed({"damage.read","damage.write","damage.admin","reconstruction.read","reconstruction.write"})
public class EvidenceResource {
    @EJB EvaluationStore evaluations;
    @EJB EvidenceStore store;
    @EJB EvidenceService service;
    @Inject Identity identity;
    private void visible(UUID id,boolean write) {
        var e=evaluations.get(id);if(e==null)throw new WebApplicationException(404);identity.disaster(e.disasterId);
        if(!e.organization.equals(identity.organization())&&(write||e.disasterId==null||!identity.readsAcrossOrganizations()))throw new WebApplicationException(404);
        if(write&&e.status!=EvaluationStatus.Draft)throw new WebApplicationException(409);
    }
    private UUID key(String text) { try{var id=UUID.fromString(text);if(!id.toString().equalsIgnoreCase(text)||id.equals(new UUID(0,0)))throw new Exception();return id;}catch(Exception e){throw new WebApplicationException(400);} }
    @POST @Consumes(MediaType.APPLICATION_JSON) @RolesAllowed({"damage.write","damage.admin"})
    public Object authorize(@PathParam("id") UUID id,@HeaderParam("Idempotency-Key") String key,EvidenceService.UploadInput input) {
        visible(id,true);return service.authorization(service.intent(id,key(key),identity.organization(),identity.actor(),input));
    }
    @POST @Consumes(MediaType.MULTIPART_FORM_DATA) @RolesAllowed({"damage.write","damage.admin"})
    public Object multipart(@PathParam("id") UUID id,@HeaderParam("Idempotency-Key") String key,@FormParam("file") EntityPart file,@FormParam("sha256") String hash,@FormParam("size") String size) throws java.io.IOException {
        visible(id,true);if(file==null||file.getFileName().isEmpty())throw new WebApplicationException(400);
        long count;try{count=Long.parseLong(size);}catch(Exception failure){throw new WebApplicationException(400);}
        var e=service.intent(id,key(key),identity.organization(),identity.actor(),new EvidenceService.UploadInput(file.getFileName().get(),file.getMediaType().toString(),count,hash));
        if(e.status==Evidence.Status.Verified)return EvidenceService.metadata(e);
        try(var stream=file.getContent()){service.multipart(e,stream);}
        return confirmation(service.confirm(id,e.evidenceId));
    }
    @POST @Path("/{evidenceId}/confirmar") @RolesAllowed({"damage.write","damage.admin"})
    public Object confirm(@PathParam("id") UUID id,@PathParam("evidenceId") UUID evidence) {
        visible(id,false);var e=store.get(evidence,id);if(!e.organizationId.equals(identity.organization()))throw new WebApplicationException(404);
        return confirmation(service.confirm(id,evidence));
    }
    private Response confirmation(Evidence e) { return Response.status(e.status==Evidence.Status.Verified?200:422).entity(EvidenceService.metadata(e)).build(); }
    @GET public Object list(@PathParam("id") UUID id) { visible(id,false);return store.list(id).stream().map(EvidenceService::metadata).toList(); }
    @GET @Path("/{evidenceId}") public Object get(@PathParam("id") UUID id,@PathParam("evidenceId") UUID evidence) { visible(id,false);return EvidenceService.metadata(store.get(evidence,id)); }
    @GET @Path("/{evidenceId}/descarga") public Object download(@PathParam("id") UUID id,@PathParam("evidenceId") UUID evidence) { visible(id,false);return service.download(store.get(evidence,id)); }
}

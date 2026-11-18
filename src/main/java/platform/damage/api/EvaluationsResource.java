package platform.damage.api;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.annotation.security.RolesAllowed;
import jakarta.ejb.EJB;
import jakarta.inject.Inject;
import platform.damage.domain.*;
import platform.damage.business.EvaluationService;
import platform.damage.persistence.EvaluationStore;
import java.util.*;
@Path("/evaluaciones") @Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
@RolesAllowed({"reconstruction.read","reconstruction.write","damage.read","damage.write","damage.admin"})
public class EvaluationsResource {
    @EJB EvaluationStore store;
    @EJB EvaluationService service;
    @Inject Identity identity;
    private Evaluation visible(UUID id) { var e=store.get(id);if(e==null)throw new WebApplicationException(404);identity.disaster(e.disasterId);if(e.disasterId==null&&!e.organization.equals(identity.organization()))throw new WebApplicationException(404);return e; }
    @GET @Path("/openapi") @Produces("application/vnd.oai.openapi+json")
    public String openapi() throws java.io.IOException {
        try(var stream=getClass().getResourceAsStream("/META-INF/openapi.json")) { return new String(stream.readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); }
    }
    @GET public Object list(@QueryParam("disasterId") Integer id) {
        if(id==null||id<=0)throw new WebApplicationException(400);identity.disaster(id);
        return Map.of("schemaVersion",1,"disasterId",id,"evaluations",store.list(id).stream().map(Views::evaluation).toList());
    }
    @GET @Path("/{id}") public Object get(@PathParam("id") UUID id) { return Views.evaluation(visible(id)); }
    @GET @Path("/mapa") public Object map(@QueryParam("disasterId") Integer id) { return list(id); }
    @POST @RolesAllowed({"damage.write","damage.admin"}) public Response create(@HeaderParam("Idempotency-Key") String key,EvaluationInput input) {
        UUID request;try{request=UUID.fromString(key);if(request.getMostSignificantBits()==0&&request.getLeastSignificantBits()==0||!request.toString().equalsIgnoreCase(key))throw new Exception();}catch(Exception e){throw new WebApplicationException("Idempotency-Key debe ser UUID",400);}
        if(input==null)throw new WebApplicationException(400);identity.disaster(input.disasterId);
        var result=service.create(request,input,identity.actor(),identity.organization());return Response.status(result.created()?201:200).entity(result.body()).build();
    }
    @PUT @Path("/{id}") @RolesAllowed({"damage.write","damage.admin"}) public Object edit(@PathParam("id") UUID id,EvaluationInput input) { visible(id);return service.edit(id,input,identity.organization()); }
    public record Association(Integer disasterId,Long expectedVersion) { }
    @PUT @Path("/{id}/desastre") @RolesAllowed({"damage.write","damage.admin"}) public Object associate(@PathParam("id") UUID id,Association input) {
        visible(id);if(input==null||input.disasterId()==null||input.expectedVersion()==null)throw new WebApplicationException(400);
        identity.disaster(input.disasterId());return service.associate(id,input.expectedVersion(),input.disasterId(),identity.organization());
    }
    @GET @Path("/catalogos") public Object catalogs() { identity.actor();identity.organization();return store.catalogs(); }
    public record CatalogInput(String id,String name,DamageLevel damageLevel) { }
    private void catalog(CatalogInput input) { identity.actor();identity.organization();if(input==null||input.id()==null||!input.id().matches("[A-Za-z0-9_.:-]{1,120}")||input.name()==null||input.name().isBlank()||input.name().length()>200)throw new WebApplicationException(400); }
    @POST @Path("/catalogos/tipos-activo") @RolesAllowed("damage.admin") public Response addType(CatalogInput input) { catalog(input);return Response.status(201).entity(store.addType(input.id(),input.name())).build(); }
    @POST @Path("/catalogos/categorias-dano") @RolesAllowed("damage.admin") public Response addCategory(CatalogInput input) { catalog(input);if(input.damageLevel()==null)throw new WebApplicationException(400);return Response.status(201).entity(store.addCategory(input.id(),input.name(),input.damageLevel())).build(); }
    @PUT @Path("/catalogos/tipos-activo/{id}/desactivar") @RolesAllowed("damage.admin") public void deactivateType(@PathParam("id") String id) { identity.organization();store.deactivateType(id); }
    @PUT @Path("/catalogos/categorias-dano/{id}/desactivar") @RolesAllowed("damage.admin") public void deactivateCategory(@PathParam("id") String id) { identity.organization();store.deactivateCategory(id); }
}

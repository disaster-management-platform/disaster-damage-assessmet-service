package platform.damage.api;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import jakarta.annotation.security.PermitAll;
import jakarta.ejb.EJB;
import platform.damage.persistence.EvaluationStore;
import java.util.Map;
@Path("/evaluaciones/diagnostico") @PermitAll @Produces(MediaType.APPLICATION_JSON)
public class Diagnostics {
    @EJB EvaluationStore store;
    @GET @Path("/live") public Object live() {return Map.of("status","UP");}
    @GET @Path("/ready") public Response ready() {
        try {store.ready();return Response.ok(Map.of("status","UP")).build();}
        catch(Exception error){return Response.status(503).entity(Map.of("status","DOWN")).build();}
    }
}

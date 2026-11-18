package platform.damage.api;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.core.*;
import jakarta.ws.rs.ext.Provider;
@Provider @Priority(Priorities.AUTHENTICATION)
public class AuthenticationRequired implements ContainerRequestFilter {
    @Context ResourceInfo resource;
    @Context SecurityContext security;
    @Inject Identity identity;
    public void filter(ContainerRequestContext request) {
        if(resource.getResourceClass()==Diagnostics.class)return;
        if(security.getUserPrincipal()==null)throw new WebApplicationException(401);
        identity.actor();identity.organization();
    }
}

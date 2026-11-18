package platform.damage.api;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.WebApplicationException;
import java.io.*;
@Provider
public class RequestLimit implements ContainerRequestFilter {
    public void filter(ContainerRequestContext request) throws IOException {
        if(request.hasEntity()) {
            byte[] body=request.getEntityStream().readNBytes(262145);
            if(body.length>262144)throw new WebApplicationException(413);
            request.setEntityStream(new ByteArrayInputStream(body));
        }
    }
}

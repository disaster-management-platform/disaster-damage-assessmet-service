package platform.damage.api;
import jakarta.ws.rs.container.*;
import jakarta.ws.rs.ext.Provider;
import jakarta.ws.rs.WebApplicationException;
import java.io.*;
@Provider
public class RequestLimit implements ContainerRequestFilter {
    public void filter(ContainerRequestContext request) throws IOException {
        if(request.hasEntity()&&request.getMediaType()!=null&&request.getMediaType().isCompatible(jakarta.ws.rs.core.MediaType.MULTIPART_FORM_DATA_TYPE)
            &&request.getUriInfo().getPath().matches("evaluaciones/[a-fA-F0-9-]{36}/evidencias")) {
            long maximum=Long.parseLong(System.getenv().getOrDefault("EVIDENCE_MAX_BYTES","6291456"))+65536;
            request.setEntityStream(new FilterInputStream(request.getEntityStream()) {
                private long count;
                private int checked(int n) { if(n>0&&(count+=n)>maximum)throw new WebApplicationException(413);return n; }
                public int read() throws IOException { int value=in.read();if(value>=0)checked(1);return value; }
                public int read(byte[] data,int offset,int length) throws IOException { return checked(in.read(data,offset,length)); }
            });return;
        }
        if(request.hasEntity()) {
            int maximum=request.getUriInfo().getPath().equals("evaluaciones/batch")?1048576:262144;
            byte[] body=request.getEntityStream().readNBytes(maximum+1);
            if(body.length>maximum)throw new WebApplicationException(413);
            request.setEntityStream(new ByteArrayInputStream(body));
        }
    }
}

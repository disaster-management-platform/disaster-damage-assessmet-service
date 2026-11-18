package platform.damage.business;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.WebApplicationException;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
@ApplicationScoped
public class DisasterReader {
    private HttpClient client;
    @PostConstruct void start() { client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    @PreDestroy void stop() { client.close(); }
    public void require(Integer id) {
        if(id==null)return;
        String base=System.getenv("DISASTERS_BASE_URL");
        if(base==null)throw new WebApplicationException("Servicio de desastres no configurado",503);
        try {
            var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/$","")+"/api/desastres/"+id)).timeout(Duration.ofSeconds(5)).GET();
            String token=System.getenv("DISASTERS_BEARER_TOKEN");if(token!=null&&!token.isBlank())request.header("Authorization","Bearer "+token);
            var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()==404)throw new WebApplicationException("Desastre inexistente",422);
            if(response.statusCode()!=200)throw new WebApplicationException("Servicio de desastres no disponible",503);
            var body=jakarta.json.Json.createReader(new java.io.StringReader(response.body())).readObject();
            if(body.getInt("id")!=id)throw new WebApplicationException("Contrato de desastres inválido",503);
        } catch(WebApplicationException e) { throw e; }
        catch(InterruptedException e) { Thread.currentThread().interrupt();throw new WebApplicationException(503); }
        catch(Exception e) { throw new WebApplicationException("Dependencia no disponible",503); }
    }
}

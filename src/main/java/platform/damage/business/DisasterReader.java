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
    private final java.util.concurrent.Semaphore tokenGate=new java.util.concurrent.Semaphore(1);
    private String token;
    private long tokenExpires;
    private String serviceToken() {
        if(!tokenGate.tryAcquire())throw new WebApplicationException(503);
        try {
            if(token!=null&&System.nanoTime()<tokenExpires)return token;
            URI endpoint=URI.create(setting("DISASTERS_OAUTH_TOKEN_ENDPOINT"));
            if(!"https".equals(endpoint.getScheme())||endpoint.getHost()==null||endpoint.getUserInfo()!=null||endpoint.getRawQuery()!=null||endpoint.getFragment()!=null)throw new WebApplicationException(503);
            String form="grant_type=client_credentials&client_id="+URLEncoder.encode(setting("DISASTERS_OAUTH_CLIENT_ID"),java.nio.charset.StandardCharsets.UTF_8)+"&client_secret="+URLEncoder.encode(setting("DISASTERS_OAUTH_CLIENT_SECRET"),java.nio.charset.StandardCharsets.UTF_8);
            var request=HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(3)).header("Content-Type","application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form)).build();
            long started=System.nanoTime();
            var pending=client.sendAsync(request,info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try{response=pending.get(3,java.util.concurrent.TimeUnit.SECONDS);}
            finally{if(!pending.isDone())pending.cancel(true);}
            if(response.statusCode()!=200)throw new WebApplicationException(503);
            try(var reader=jakarta.json.Json.createReader(new java.io.ByteArrayInputStream(response.body()))){
                var result=reader.readObject();int ttl=result.getInt("expires_in");String value=result.getString("access_token");
                if(ttl<1||ttl>3600||value.isBlank()||!"Bearer".equalsIgnoreCase(result.getString("token_type")))throw new WebApplicationException(503);
                token=value;tokenExpires=started+(ttl-Math.min(30,ttl/5))*1_000_000_000L;return token;
            }
        }catch(InterruptedException failure){Thread.currentThread().interrupt();throw new WebApplicationException(503);}
        catch(Exception failure){throw new WebApplicationException(503);}
        finally{tokenGate.release();}
    }
    private static String setting(String name){String value=System.getenv(name);if(value==null||value.isBlank())throw new WebApplicationException(503);return value;}
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final HttpResponse.BodySubscriber<byte[]> delegate=HttpResponse.BodySubscribers.ofByteArray();
        private java.util.concurrent.Flow.Subscription subscription;
        private int remaining=65536;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return delegate.getBody();}
        public void onSubscribe(java.util.concurrent.Flow.Subscription value){subscription=value;delegate.onSubscribe(value);}
        public void onNext(java.util.List<java.nio.ByteBuffer> buffers){
            for(var buffer:buffers){if(buffer.remaining()>remaining){subscription.cancel();delegate.onError(new java.io.IOException("OAuth response too large"));return;}remaining-=buffer.remaining();}
            delegate.onNext(buffers);
        }
        public void onError(Throwable failure){delegate.onError(failure);}
        public void onComplete(){delegate.onComplete();}
    }
    @PostConstruct void start() { client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    @PreDestroy void stop() { client.close(); }
    public void require(Integer id) {
        if(id==null)return;
        String base=System.getenv("DISASTERS_BASE_URL");
        if(base==null)throw new WebApplicationException("Servicio de desastres no configurado",503);
        try {
            var request=HttpRequest.newBuilder(URI.create(base.replaceAll("/$","")+"/api/desastres/"+id)).timeout(Duration.ofSeconds(5)).GET();
            request.header("Authorization","Bearer "+serviceToken());
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

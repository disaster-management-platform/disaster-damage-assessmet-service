package platform.damage.storage;
import jakarta.annotation.*;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.concurrent.ManagedScheduledExecutorService;
import jakarta.json.*;
import jakarta.ws.rs.WebApplicationException;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;
@ApplicationScoped
public class SupabaseStorage {
    @Resource(lookup="java:comp/DefaultManagedScheduledExecutorService") ManagedScheduledExecutorService timer;
    private HttpClient client;
    private String token;
    private Instant tokenExpires=Instant.EPOCH;
    @PostConstruct void start() { client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build(); }
    @PreDestroy void stop() { client.close(); }
    private String setting(String key) { String value=System.getenv(key);if(value==null||value.isBlank())throw new WebApplicationException("Storage no configurado",503);return value; }
    private String base() { return "https://yhbwpetxhydxssernbrb.supabase.co"; }
    private String bucket() { String value=setting("EVIDENCE_STORAGE_BUCKET");if(!value.matches("[a-z0-9-]{1,63}"))throw new WebApplicationException(503);return value; }
    private synchronized String bearer() {
        if(tokenExpires.isAfter(Instant.now().plusSeconds(60)))return token;
        var payload=Json.createObjectBuilder().add("email",setting("EVIDENCE_STORAGE_EMAIL")).add("password",setting("EVIDENCE_STORAGE_PASSWORD")).build();
        var request=HttpRequest.newBuilder(URI.create(base()+"/auth/v1/token?grant_type=password")).timeout(Duration.ofSeconds(10)).header("apikey",setting("EVIDENCE_STORAGE_PUBLISHABLE_KEY")).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build();
        try {
            var response=client.send(request,HttpResponse.BodyHandlers.ofString());if(response.statusCode()!=200)throw new WebApplicationException(503);
            var value=Json.createReader(new StringReader(response.body())).readObject();token=value.getString("access_token");tokenExpires=Instant.now().plusSeconds(value.getJsonNumber("expires_in").longValue());return token;
        } catch(InterruptedException e){Thread.currentThread().interrupt();throw new WebApplicationException(503);}catch(IOException|RuntimeException e){if(e instanceof WebApplicationException w)throw w;throw new WebApplicationException(503);}
    }
    private HttpRequest.Builder request(String path) { return HttpRequest.newBuilder(URI.create(base()+"/storage/v1/"+path)).timeout(Duration.ofSeconds(15)).header("apikey",setting("EVIDENCE_STORAGE_PUBLISHABLE_KEY")).header("Authorization","Bearer "+bearer()); }
    private JsonObject json(String path,JsonObject payload,boolean conflictAllowed) {
        try {
            var response=client.send(request(path).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(payload.toString())).build(),HttpResponse.BodyHandlers.ofString());
            if(conflictAllowed&&(response.statusCode()==409||response.statusCode()==400&&response.body().contains("\"KeyAlreadyExists\"")))return Json.createObjectBuilder().build();
            if(response.statusCode()<200||response.statusCode()>=300)throw new WebApplicationException(503);
            return Json.createReader(new StringReader(response.body())).readObject();
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new WebApplicationException(503);}catch(IOException e){throw new WebApplicationException(503);}
    }
    public record Signed(String url,OffsetDateTime expiresAt) { }
    public Signed upload(String path) {
        var result=json("object/upload/sign/"+bucket()+"/"+path,Json.createObjectBuilder().build(),false);
        return signed(result.getString("url"));
    }
    public Signed download(String path) {
        var response=json("object/sign/"+bucket()+"/"+path,Json.createObjectBuilder().add("expiresIn",60).build(),false);
        return signed(response.getString("signedURL"));
    }
    private Signed signed(String relative) {
        try {
            var url=URI.create(base()+"/storage/v1"+relative);
            String token=Arrays.stream(url.getRawQuery().split("&")).filter(v->v.startsWith("token=")).findFirst().orElseThrow().substring(6);
            var claims=Json.createReader(new StringReader(new String(Base64.getUrlDecoder().decode(URLDecoder.decode(token,java.nio.charset.StandardCharsets.UTF_8).split("\\.")[1]),java.nio.charset.StandardCharsets.UTF_8))).readObject();
            return new Signed(url.toString(),OffsetDateTime.ofInstant(Instant.ofEpochSecond(claims.getJsonNumber("exp").longValue()),ZoneOffset.UTC));
        }catch(RuntimeException failure){throw new WebApplicationException(503);}
    }
    public void copy(String source,String destination) { json("object/copy",Json.createObjectBuilder().add("bucketId",bucket()).add("sourceKey",source).add("destinationKey",destination).build(),true); }
    public record Digest(long size,String sha256,String contentType) { }
    public Digest inspect(String path,long maximum) {
        try {
            var response=client.send(request("object/authenticated/"+bucket()+"/"+path).GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            try(var stream=response.body()) {
                if(response.statusCode()==404||response.statusCode()==400)throw new WebApplicationException("Objeto inexistente",422);
                if(response.statusCode()!=200)throw new WebApplicationException(503);
                var close=timer.schedule(()->{try{stream.close();}catch(IOException ignored){}},30,TimeUnit.SECONDS);
                try {
                    var hash=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[8192],header=new byte[8];long size=0;int signature=0,n;
                    while((n=stream.read(buffer))!=-1){if(size+n>maximum)throw new WebApplicationException(413);int amount=Math.min(n,8-signature);System.arraycopy(buffer,0,header,signature,amount);signature+=amount;size+=n;hash.update(buffer,0,n);}
                    String mime=signature>=5&&header[0]=='%'&&header[1]=='P'&&header[2]=='D'&&header[3]=='F'&&header[4]=='-'?"application/pdf":signature>=3&&(header[0]&255)==255&&(header[1]&255)==216&&(header[2]&255)==255?"image/jpeg":signature==8&&Arrays.equals(header,new byte[]{(byte)137,80,78,71,13,10,26,10})?"image/png":null;
                    if(mime==null||size==0)throw new WebApplicationException("Firma inválida",422);
                    if(!response.headers().firstValue("Content-Type").orElse("").split(";")[0].equals(mime))throw new WebApplicationException("MIME incompatible",422);
                    return new Digest(size,HexFormat.of().formatHex(hash.digest()),mime);
                }finally{close.cancel(false);}
            }
        }catch(InterruptedException e){Thread.currentThread().interrupt();throw new WebApplicationException(503);}catch(IOException|java.security.NoSuchAlgorithmException e){throw new WebApplicationException(503);}
    }
    public void upload(String path,String mime,InputStream input) {
        try { var response=client.send(request("object/"+bucket()+"/"+path).header("Content-Type",mime).header("x-upsert","false").POST(HttpRequest.BodyPublishers.ofInputStream(()->input)).build(),HttpResponse.BodyHandlers.ofString());if(response.statusCode()==413||response.body().contains("\"EntityTooLarge\""))throw new WebApplicationException(413);if(response.statusCode()!=200&&response.statusCode()!=201&&response.statusCode()!=409&&!response.body().contains("\"KeyAlreadyExists\""))throw new WebApplicationException(503); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new WebApplicationException(503);}catch(IOException e){throw new WebApplicationException(503);}
    }
    public void deleteTemporary(String path) {
        if(!path.startsWith("pending/"))throw new IllegalArgumentException();
        try { var response=client.send(request("object/"+bucket()).header("Content-Type","application/json").method("DELETE",HttpRequest.BodyPublishers.ofString(Json.createObjectBuilder().add("prefixes",Json.createArrayBuilder().add(path)).build().toString())).build(),HttpResponse.BodyHandlers.discarding());if(response.statusCode()!=200)throw new WebApplicationException(503); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new WebApplicationException(503);}catch(IOException e){throw new WebApplicationException(503);}
    }
}

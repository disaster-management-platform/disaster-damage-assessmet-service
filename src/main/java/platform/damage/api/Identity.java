package platform.damage.api;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.WebApplicationException;
import org.eclipse.microprofile.jwt.JsonWebToken;
import java.util.Collection;
@RequestScoped
public class Identity {
    @Inject JsonWebToken token;
    public String actor() { return claim("sub"); }
    public String organization() { return claim("organization_id"); }
    private String claim(String name) {
        Object value = token.getClaim(name);
        if (value instanceof jakarta.json.JsonString json) value = json.getString();
        if (!(value instanceof String text) || text.isBlank() || text.length()>200) throw new WebApplicationException(403);
        return text;
    }
    public boolean readsAcrossOrganizations() { return token.getGroups().contains("reconstruction.read")||token.getGroups().contains("reconstruction.write"); }
    public boolean organizationVisible(String organization) { return organization.equals(organization())||readsAcrossOrganizations(); }
    public String scope() {
        var groups=new java.util.TreeSet<>(token.getGroups());
        Object grants=token.getClaim("disaster_id");
        var disasters=new java.util.TreeSet<String>();
        if(grants instanceof Collection<?> values)for(var value:values)disasters.add(String.valueOf(value));
        else disasters.add(String.valueOf(grants));
        String value=jakarta.json.Json.createObjectBuilder().add("issuer",token.getIssuer()).add("actor",actor()).add("organization",organization()).add("roles",groups.toString()).add("disasters",disasters.toString()).build().toString();
        try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException failure){throw new IllegalStateException(failure);}
    }
    private boolean allows(Object value,Integer id) {
        try { return Integer.parseInt((value instanceof jakarta.json.JsonString text ? text.getString() : String.valueOf(value)).trim()) == id; }
        catch (NumberFormatException error) { return false; }
    }
    public void disaster(Integer id) {
        actor(); organization();
        if (id == null) return;
        Object values = token.getClaim("disaster_id");
        boolean allowed = values instanceof Collection<?> c ? c.stream().anyMatch(x -> allows(x,id)) : allows(values,id);
        if (!allowed) throw new WebApplicationException(403);
    }
}

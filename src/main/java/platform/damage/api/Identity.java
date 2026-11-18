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

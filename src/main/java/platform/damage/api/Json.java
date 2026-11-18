package platform.damage.api;
import jakarta.json.bind.*;
import jakarta.json.bind.config.PropertyOrderStrategy;
public final class Json {
    public static final Jsonb CODEC = JsonbBuilder.create(new JsonbConfig().withNullValues(true).withPropertyOrderStrategy(PropertyOrderStrategy.LEXICOGRAPHICAL));
    private Json() { }
}

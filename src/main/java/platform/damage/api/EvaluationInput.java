package platform.damage.api;
import jakarta.validation.constraints.*;
import jakarta.json.JsonObject;
import java.time.OffsetDateTime;
import java.math.BigDecimal;
import java.util.List;
public class EvaluationInput {
    @Positive public Integer disasterId;
    @NotBlank @Size(max=200) public String assetId;
    @NotBlank @Size(max=120) public String assetTypeId;
    @NotBlank @Size(max=120) public String damageCategoryId;
    @NotNull @PastOrPresent public OffsetDateTime evaluatedAt;
    @NotBlank @Size(max=200) public String location;
    @DecimalMin("-90") @DecimalMax("90") public Double latitude;
    @DecimalMin("-180") @DecimalMax("180") public Double longitude;
    public JsonObject geometry;
    @NotBlank @Size(max=2000) public String description;
    @NotNull @Size(max=50) public List<@NotBlank @Size(max=500) String> evidence;
    @NotNull @DecimalMin("0") @Digits(integer=17,fraction=2) public BigDecimal budget;
    public Long expectedVersion;
}

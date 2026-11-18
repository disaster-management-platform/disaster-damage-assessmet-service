package platform.damage.domain;
import jakarta.persistence.*;
@Entity @Table(name="asset_types", schema="assessments")
public class AssetType {
    @Id @Column(length=120) public String id;
    @Column(nullable=false,length=200,updatable=false) public String name;
    @Column(nullable=false) public boolean active = true;
}

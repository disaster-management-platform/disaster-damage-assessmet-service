package platform.damage.domain;
import jakarta.persistence.*;
@Entity @Table(name="damage_categories", schema="assessments")
public class DamageCategory {
    @Id @Column(length=120) public String id;
    @Column(nullable=false,length=200,updatable=false) public String name;
    @Enumerated(EnumType.STRING) @Column(nullable=false,length=20,updatable=false) public DamageLevel damageLevel;
    @Column(nullable=false) public boolean active = true;
}

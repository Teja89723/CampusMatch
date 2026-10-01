package com.campusmatch.model;

import com.campusmatch.security.EncryptedStringConverter;
import jakarta.persistence.*;
import java.util.LinkedHashSet;
import java.util.Set;

@Entity
@Table(name = "profiles")
public class Profile {
    @Id
    public Long userId;
    @Convert(converter = EncryptedStringConverter.class)
    public String phone;                       // encrypted at field level
    public String location;
    public String institution;
    public String degree;
    public String fieldOfStudy;
    public Integer gradYear;
    public Integer experienceYears = 0;
    @Column(length = 1000) public String summary;
    @Column(length = 3000) public String experience;
    @Column(length = 200)  public String desiredRoles;   // comma separated
    @Column(length = 10)   public String workMode = "ANY";   // ANY | REMOTE | HYBRID | ONSITE
    @Column(length = 12)   public String jobType = "ANY";    // ANY | INTERNSHIP | FULLTIME | PARTTIME | CONTRACT | TEMPORARY

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "profile_skills", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "skill", length = 60)
    public Set<String> skills = new LinkedHashSet<>();
}

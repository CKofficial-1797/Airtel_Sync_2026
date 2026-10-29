package com.udyam.registry;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of the mock GST registry (mock_gstin_registry.csv). */
@Entity
@Table(name = "gstin_registry")
@Immutable
public class GstinRecord {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 15)
    private String gstin;
    private String legalName;
    private String tradeName;
    private String constitution;
    private String principalPlaceOfBusiness;
    private String state;
    private LocalDate registrationDate;
    /** Exact registry text: Active, Cancelled or Suspended. */
    private String status;

    protected GstinRecord() {
    }

    public String getGstin() { return gstin; }
    public String getLegalName() { return legalName; }
    public String getTradeName() { return tradeName; }
    public String getConstitution() { return constitution; }
    public String getPrincipalPlaceOfBusiness() { return principalPlaceOfBusiness; }
    public String getState() { return state; }
    public LocalDate getRegistrationDate() { return registrationDate; }
    public String getStatus() { return status; }
}

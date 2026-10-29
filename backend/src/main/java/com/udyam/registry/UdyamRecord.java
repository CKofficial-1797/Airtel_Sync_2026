package com.udyam.registry;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDate;
import org.hibernate.annotations.Immutable;

/** One row of the mock Udyam registry (mock_udyam_registry.csv). */
@Entity
@Table(name = "udyam_registry")
@Immutable
public class UdyamRecord {

    @Id
    private String udyamNumber;
    private String enterpriseName;
    private String enterpriseType;
    private String majorActivity;
    private String nicDescription;
    private String state;
    private String city;
    private LocalDate dateOfRegistration;

    protected UdyamRecord() {
    }

    public String getUdyamNumber() { return udyamNumber; }
    public String getEnterpriseName() { return enterpriseName; }
    public String getEnterpriseType() { return enterpriseType; }
    public String getMajorActivity() { return majorActivity; }
    public String getNicDescription() { return nicDescription; }
    public String getState() { return state; }
    public String getCity() { return city; }
    public LocalDate getDateOfRegistration() { return dateOfRegistration; }
}

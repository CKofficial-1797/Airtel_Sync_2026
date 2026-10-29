package com.udyam.catalogue;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** One row of product_catalogue.csv. Read-only: rows are written only by the DataLoader. */
@Entity
@Table(name = "product")
@Immutable
public class Product {

    @Id
    private String productId;
    private String category;
    private String productName;
    private String description;
    private String unit;
    private Integer priceMinInrPerMonth;
    private Integer priceMaxInrPerMonth;
    private Integer oneTimeSetupInr;
    private Integer minContractMonths;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private Integer[] availableCityTiers;

    private Integer minEmployees;
    private Integer minSites;
    private Boolean requiresGstin;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] requiresOneOf;

    private Integer typicalInstallDays;

    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] targetSegments;

    protected Product() {
    }

    public String getProductId() { return productId; }
    public String getCategory() { return category; }
    public String getProductName() { return productName; }
    public String getDescription() { return description; }
    public String getUnit() { return unit; }
    public Integer getPriceMinInrPerMonth() { return priceMinInrPerMonth; }
    public Integer getPriceMaxInrPerMonth() { return priceMaxInrPerMonth; }
    public Integer getOneTimeSetupInr() { return oneTimeSetupInr; }
    public Integer getMinContractMonths() { return minContractMonths; }
    public Integer[] getAvailableCityTiers() { return availableCityTiers; }
    public Integer getMinEmployees() { return minEmployees; }
    public Integer getMinSites() { return minSites; }
    public Boolean getRequiresGstin() { return requiresGstin; }
    public String[] getRequiresOneOf() { return requiresOneOf; }
    public Integer getTypicalInstallDays() { return typicalInstallDays; }
    public String[] getTargetSegments() { return targetSegments; }
}

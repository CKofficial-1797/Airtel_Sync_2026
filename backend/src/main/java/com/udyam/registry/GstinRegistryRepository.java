package com.udyam.registry;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GstinRegistryRepository extends JpaRepository<GstinRecord, String> {

    /** Rows of [status, count]. */
    @Query("select g.status, count(g) from GstinRecord g group by g.status")
    List<Object[]> countByStatus();
}

package com.udyam.registry;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UdyamRegistryRepository extends JpaRepository<UdyamRecord, String> {
}

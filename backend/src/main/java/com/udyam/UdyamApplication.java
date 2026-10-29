package com.udyam;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class UdyamApplication {

    public static void main(String[] args) {
        SpringApplication.run(UdyamApplication.class, args);
    }
}

package com.disaster.allocation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point of the Disaster Resource Allocation System.
 *
 * ACADEMIC SIMULATOR: it is not connected to any real emergency service and
 * never makes real-life dispatch decisions.
 */
@SpringBootApplication
public class DisasterAllocationApplication {
    public static void main(String[] args) {
        SpringApplication.run(DisasterAllocationApplication.class, args);
    }
}

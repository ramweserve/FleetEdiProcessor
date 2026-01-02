package com.weserve.fleetex;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync
public class FleetExApplication {

    public static void main(String[] args) {
        SpringApplication.run(FleetExApplication.class, args);
    }

}

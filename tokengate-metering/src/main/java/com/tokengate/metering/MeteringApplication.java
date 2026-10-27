package com.tokengate.metering;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class MeteringApplication {

    public static void main(String[] args) {
        SpringApplication.run(MeteringApplication.class, args);
    }
}

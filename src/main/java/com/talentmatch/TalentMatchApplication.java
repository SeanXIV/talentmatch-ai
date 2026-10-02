package com.talentmatch;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/** TalentMatch AI REST API entry point. */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TalentMatchApplication {

    public static void main(String[] args) {
        SpringApplication.run(TalentMatchApplication.class, args);
    }
}

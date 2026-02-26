package com.example.ingesta;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class IngestaApplication {

    public static void main(String[] args) {
        SpringApplication.run(IngestaApplication.class, args);
    }
}

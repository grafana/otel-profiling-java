package io.pyroscope.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        // Pyroscope agent is started programmatically in PyroscopeConfig via @PostConstruct.
        SpringApplication.run(App.class, args);
    }
}

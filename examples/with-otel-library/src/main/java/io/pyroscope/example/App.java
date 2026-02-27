package io.pyroscope.example;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class App {
    public static void main(String[] args) {
        // OpenTelemetry SDK and Pyroscope agent are initialized in OtelConfig
        // via @PostConstruct before any requests are served.
        SpringApplication.run(App.class, args);
    }
}

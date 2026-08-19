package com.quartzjobs;

import com.quartzjobs.config.StartupConfigPrinter;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class JobsApplication {

    //	public static void main(String[] args) {
    //		SpringApplication.run(JobsApplication.class, args);
    //	}

    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(JobsApplication.class);
        app.addListeners(new StartupConfigPrinter()); // ← imprime vars antes de conectar BD
        app.run(args);
    }
}

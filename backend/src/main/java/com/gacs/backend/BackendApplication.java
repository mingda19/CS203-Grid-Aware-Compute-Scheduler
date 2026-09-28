package com.gacs.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		com.gacs.backend.config.DotenvLoader.load();
		SpringApplication.run(BackendApplication.class, args);
	}

}

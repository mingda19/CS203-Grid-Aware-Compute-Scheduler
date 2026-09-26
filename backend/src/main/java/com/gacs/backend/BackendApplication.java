package com.gacs.backend;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BackendApplication {

	public static void main(String[] args) {
		com.gacs.backend.config.DotenvLoader.load();
		SpringApplication.run(BackendApplication.class, args);
	}

}

package io.github.harsha135135.ledgercore;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LedgercoreApplication {

	public static void main(String[] args) {
		SpringApplication.run(LedgercoreApplication.class, args);
	}

}

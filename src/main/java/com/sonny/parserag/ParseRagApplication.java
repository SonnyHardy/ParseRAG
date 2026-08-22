package com.sonny.parserag;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/*
 * @EnableScheduling : requis par le ping de maintien en eveil de la base (issue #15). Aucune
 * autre tache planifiee a ce jour.
 */
@EnableScheduling
@SpringBootApplication
public class ParseRagApplication {

    static void main(String[] args) {
        SpringApplication.run(ParseRagApplication.class, args);
    }

}

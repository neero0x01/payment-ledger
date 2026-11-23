package com.paymentledger.projector;

import java.util.Arrays;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The projector: a headless service that builds the Read Model in the {@code readmodel} database
 * from the ledger's Kafka events (ADR 0002). Run with {@code --rebuild}, it rebuilds the Read Model
 * by replaying Kafka and exits.
 */
@SpringBootApplication
public class ProjectorApplication {

  public static void main(String[] args) {
    ConfigurableApplicationContext projector = application().run(args);
    if (Arrays.asList(args).contains("--" + Rebuild.OPTION)) {
      // A one-off command: exit once the Read Model is rebuilt.
      System.exit(SpringApplication.exit(projector));
    }
  }

  /**
   * The projector as an application. Its configuration is {@code projector.yml}, so it never picks
   * up the ledger's {@code application.yml} when both are on one classpath, as in the ledger's
   * tests.
   */
  public static SpringApplicationBuilder application() {
    return new SpringApplicationBuilder(ProjectorApplication.class)
        .web(WebApplicationType.NONE)
        .properties("spring.config.name=projector");
  }
}

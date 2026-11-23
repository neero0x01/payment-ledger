package com.paymentledger.projector;

import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * The projector: a headless service that builds the Read Model in the {@code readmodel} database
 * from the ledger's Kafka events (ADR 0002).
 */
@SpringBootApplication
public class ProjectorApplication {

  public static void main(String[] args) {
    application().run(args);
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

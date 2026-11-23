package com.paymentledger.ledger.views.internal;

import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The read-only connection to the readmodel database (spec §5). It sits beside the ledger's own
 * DataSource without replacing it: neither bean is a default candidate, so only the views module,
 * asking for it by name, ever gets it.
 */
@Configuration(proxyBeanMethods = false)
class ReadModelConnection {

  static final String NAME = "readModelDataSource";

  @Bean(defaultCandidate = false)
  @ConfigurationProperties("ledger.read-model.datasource")
  DataSourceProperties readModelDataSourceProperties() {
    return new DataSourceProperties();
  }

  @Bean(name = NAME, defaultCandidate = false)
  DataSource readModelDataSource(
      @Qualifier("readModelDataSourceProperties") DataSourceProperties properties) {
    HikariDataSource dataSource =
        properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    dataSource.setPoolName("read-model");
    dataSource.setReadOnly(true);
    // readmodel_reader may only SELECT (the projector's V1__read_model.sql).
    dataSource.setConnectionInitSql("set role readmodel_reader");
    return dataSource;
  }
}

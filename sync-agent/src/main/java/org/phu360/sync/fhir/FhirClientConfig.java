package org.phu360.sync.fhir;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class FhirClientConfig {

  @Bean
  public RestClient restClient() {
    return RestClient.builder().build();
  }
}

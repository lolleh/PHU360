package org.phu360.sync.sync;

import org.phu360.sync.config.SyncProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SyncScheduler {

  private static final Logger log = LoggerFactory.getLogger(SyncScheduler.class);

  private final SyncProperties properties;

  public SyncScheduler(SyncProperties properties) {
    this.properties = properties;
  }

  @Scheduled(fixedDelayString = "${phu360.sync.poll-interval-ms:300000}")
  public void sync() {
    log.info("Sync tick: site={} push={} pull={} resources={}",
        properties.getSiteCode(),
        properties.isPushEnabled(),
        properties.isPullEnabled(),
        properties.getResources());
  }
}

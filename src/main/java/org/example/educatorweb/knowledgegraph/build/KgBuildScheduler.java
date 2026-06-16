package org.example.educatorweb.knowledgegraph.build;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class KgBuildScheduler {

    private static final Logger log = LoggerFactory.getLogger(KgBuildScheduler.class);
    private final KgBuildAgent agent;
    private volatile boolean running;

    public KgBuildScheduler(KgBuildAgent agent) {
        this.agent = agent;
    }

    @Scheduled(cron = "${kg.build.schedule:0 0 3 * * SUN}")
    public void scheduledBuild() {
        if (running) {
            log.info("KgBuildScheduler: previous build still running, skipping");
            return;
        }
        running = true;
        try {
            log.info("KgBuildScheduler: scheduled build starting");
            agent.syncSources();
            var result = agent.buildIncremental();
            log.info("KgBuildScheduler: done — {} KPs, {} rels", result.knowledgePoints(), result.relationships());
        } catch (Exception e) {
            log.error("KgBuildScheduler: failed: {}", e.getMessage());
        } finally {
            running = false;
        }
    }
}

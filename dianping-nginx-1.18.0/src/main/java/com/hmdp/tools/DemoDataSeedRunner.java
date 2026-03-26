package com.hmdp.tools;

import com.hmdp.HmDianPingApplication;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.HashMap;
import java.util.Map;

@Slf4j
public class DemoDataSeedRunner {

    public static void main(String[] args) {
        setSystemPropertyIfAbsent("spring.main.web-application-type", "none");
        setSystemPropertyIfAbsent("spring.main.banner-mode", "off");
        setSystemPropertyIfAbsent("spring.rabbitmq.listener.simple.auto-startup", "false");
        setSystemPropertyIfAbsent("spring.rabbitmq.listener.direct.auto-startup", "false");
        setSystemPropertyIfAbsent("logging.level.root", "warn");
        setSystemPropertyIfAbsent("logging.level.com.hmdp", "info");
        setSystemPropertyIfAbsent("logging.level.com.baomidou", "warn");
        setSystemPropertyIfAbsent("logging.level.org.mybatis", "warn");
        setSystemPropertyIfAbsent("mybatis-plus.configuration.log-impl", "org.apache.ibatis.logging.nologging.NoLoggingImpl");

        Map<String, Object> defaults = new HashMap<>();
        defaults.put("spring.main.web-application-type", "none");
        defaults.put("spring.main.banner-mode", "off");
        defaults.put("spring.rabbitmq.listener.simple.auto-startup", "false");
        defaults.put("spring.rabbitmq.listener.direct.auto-startup", "false");
        defaults.put("logging.level.com.hmdp", "info");
        defaults.put("mybatis-plus.configuration.log-impl", "org.apache.ibatis.logging.nologging.NoLoggingImpl");

        ConfigurableApplicationContext ctx = new SpringApplicationBuilder(HmDianPingApplication.class)
                .web(WebApplicationType.NONE)
                .properties(defaults)
                .run(args);
        int exitCode = 0;
        try {
            DemoDataSeedService seedService = ctx.getBean(DemoDataSeedService.class);
            DemoDataSeedService.SeedSummary summary = seedService.seed();
            DemoDataSeedService.VerifySummary verifySummary = seedService.verify();
            log.info("demo data seed finished: {}", summary);
            log.info("demo data verify finished: {}", verifySummary);
        } catch (Exception e) {
            exitCode = 1;
            log.error("demo data seed failed: {}", e.getMessage(), e);
        } finally {
            ctx.close();
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void setSystemPropertyIfAbsent(String key, String value) {
        if (System.getProperty(key) == null) {
            System.setProperty(key, value);
        }
    }
}

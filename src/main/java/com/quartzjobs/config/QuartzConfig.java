package com.quartzjobs.config;

import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SpringBeanJobFactory;

@Configuration
public class QuartzConfig {

    private final ApplicationContext applicationContext;

    public QuartzConfig(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Bean
    public SpringBeanJobFactory springBeanJobFactory() {
        SpringBeanJobFactory factory = new SpringBeanJobFactory();
        factory.setApplicationContext(applicationContext);
        return factory;
    }
}

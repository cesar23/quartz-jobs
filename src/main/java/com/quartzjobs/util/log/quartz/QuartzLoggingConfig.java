package com.quartzjobs.util.log.quartz;

import jakarta.annotation.PostConstruct;
import org.quartz.Scheduler;
import org.quartz.SchedulerException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.quartz.SchedulerFactoryBean;

/**
 * Registra {@link QuartzExecutionListener} como listener global del Scheduler (Quartz no lo detecta
 * solo por ser {@code @Component}).
 */
@Configuration
public class QuartzLoggingConfig {

    private final SchedulerFactoryBean schedulerFactoryBean;
    private final QuartzExecutionListener quartzExecutionListener;

    @Autowired
    public QuartzLoggingConfig(
            SchedulerFactoryBean schedulerFactoryBean,
            QuartzExecutionListener quartzExecutionListener) {
        this.schedulerFactoryBean = schedulerFactoryBean;
        this.quartzExecutionListener = quartzExecutionListener;
    }

    @PostConstruct
    public void registerGlobalJobListener() throws SchedulerException {
        Scheduler scheduler = schedulerFactoryBean.getScheduler();
        scheduler.getListenerManager().addJobListener(quartzExecutionListener);
    }
}

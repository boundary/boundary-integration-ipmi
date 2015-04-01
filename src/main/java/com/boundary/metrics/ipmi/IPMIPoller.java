package com.boundary.metrics.ipmi;

import com.boundary.metrics.ipmi.client.metrics.MetricsClient;
import com.boundary.metrics.ipmi.poller.IPMIMetricsPoller;
import com.boundary.metrics.ipmi.poller.MonitoredEntity;
import com.boundary.metrics.ipmi.poller.MonitoredMetric;
import com.boundary.metrics.ipmi.resources.MonitoredEntitiesResource;
import com.google.common.collect.Maps;
import com.sun.jersey.api.client.Client;
import com.veraxsystems.vxipmi.api.sync.IpmiConnector;
import io.dropwizard.Application;
import io.dropwizard.client.JerseyClientBuilder;
import io.dropwizard.lifecycle.Managed;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;

import java.util.List;
import java.util.Map;
import java.util.Vector;
import java.util.HashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class IPMIPoller extends Application<IPMIPollerConfiguration> {

    public static void main(String[] args) throws Exception {
        new IPMIPoller().run(args);
    }

    @Override
    public String getName() {
        return "IPMI-Poller";
    }

    @Override
    public void initialize(Bootstrap<IPMIPollerConfiguration> bootstrap) { }

    @Override
    public void run(IPMIPollerConfiguration config, Environment environment) throws Exception {

        // get metric metadata from sensor configurations
        Map<String, MonitoredMetric.Metric> metrics = new HashMap<String, MonitoredMetric.Metric>();
        for (IPMIPollerConfiguration.EntityConfiguration e : config.monitoredEntities) {
            for (IPMIPollerConfiguration.SensorConfiguration s : e.sensors) {
                if (!metrics.containsKey(s.metric)) {
                    metrics.put(s.metric, new MonitoredMetric.Metric(s));
                }
            }
        }

        // build the monitored entity list
        List<MonitoredEntity> entities = new Vector<MonitoredEntity>();
        for (IPMIPollerConfiguration.EntityConfiguration e : config.monitoredEntities) {
            List<MonitoredMetric> sensors = new Vector<MonitoredMetric>();
            for (IPMIPollerConfiguration.SensorConfiguration s : e.sensors) {
                String source = s.source != null ? s.source : e.source;
                sensors.add(new MonitoredMetric(s, metrics.get(s.metric), source));
            }
            entities.add(new MonitoredEntity(e.host, e.username, e.password, sensors));
        }

        /**
         * Create and register clients
         */
        final MetricsClient metricsClient = new MetricsClient(
            new JerseyClientBuilder(environment).using(config.client).build("http-client"),
            config.metricsClient.baseUri,
            config.metricsClient.apiUser,
            config.metricsClient.apiToken);

        final ScheduledExecutorService scheduler = environment.lifecycle().scheduledExecutorService("ipmi-poller")
            .threads(Math.min(entities.size(), Runtime.getRuntime().availableProcessors())).build();
        environment.jersey().register(new MonitoredEntitiesResource());
        final IpmiConnector connector = new IpmiConnector(0); // Share the same IpmiConnector

        /**
         * Start pollers for each configured entity
         */
        for (MonitoredEntity e : entities) {
            IPMIMetricsPoller poller = new IPMIMetricsPoller(e, metricsClient, connector);
            environment.metrics().registerAll(poller);
            scheduler.scheduleAtFixedRate(poller, 1, config.pollFrequency.toSeconds(), TimeUnit.SECONDS);
        }

        /**
         * Create metrics
         */
        for (MonitoredMetric.Metric m : metrics.values()) {
            if (!metricsClient.createMetric(m, (int) config.pollFrequency.toSeconds()*1000)) {
                metricsClient.bevent("ipmi", "Create/Update Metric Failed", "Couldn't create or update the metric %s".format(m.name));
            }
        }

        environment.lifecycle().manage(new Managed() {
            @Override
            public void start() throws Exception { }

            @Override
            public void stop() throws Exception {
                connector.tearDown();
            }
        });
    }
}

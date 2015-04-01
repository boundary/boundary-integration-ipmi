package com.boundary.metrics.ipmi.client.metrics;

import com.boundary.metrics.ipmi.poller.MonitoredMetric;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.sun.jersey.api.client.AsyncWebResource;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.async.TypeListener;
import com.sun.jersey.core.util.Base64;
import org.joda.time.DateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.HttpHeaders;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;
import java.util.List;
import java.util.Vector;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;

import static com.google.common.base.Preconditions.checkNotNull;

public class MetricsClient {

    private class UpdateMetric {
        private MonitoredMetric.Metric metric;
        private int frequency;
        public UpdateMetric(MonitoredMetric.Metric m, int pollFrequency) {
            metric = m;
            frequency = pollFrequency;
        }
        @JsonProperty
        public String getName() { return metric.name; }
        @JsonProperty
        public String getDescription() { return metric.description; }
        @JsonProperty
        public String getDisplayName() { return metric.displayName; }
        @JsonProperty
        public String getDisplayNameShort() { return metric.displayNameShort; }
        @JsonProperty
        public String getUnit() { return metric.unit; }
        @JsonProperty
        public String getDefaultAggregate() { return metric.aggregate; }
        @JsonProperty
        public int getDefaultResolutionMS() { return frequency; }
        @JsonProperty
        public boolean getIsDisabled() { return false; }
    }

    private final WebResource baseResource;
    private final AsyncWebResource asyncWebResource;
    private final String auth;
    private final ObjectMapper mapper;

    private static final Joiner PATH_JOINER = Joiner.on('/');
    private static final Logger LOG = LoggerFactory.getLogger(MetricsClient.class);

    public MetricsClient(Client client, URI baseUrl, String user, String token) {
        checkNotNull(client);
        checkNotNull(baseUrl);
        this.baseResource = client.resource(baseUrl);
        this.asyncWebResource = client.asyncResource(baseUrl);
        this.auth = "Basic " + new String(Base64.encode(user + ":" + token), Charsets.US_ASCII);
        this.mapper = new ObjectMapper();
    }

    public static class BoundaryResponse {
        public static class Success {
            public boolean success;
        }
        public Success result;
    }

    public boolean createMetric(MonitoredMetric.Metric metric, int pollFrequency) {
        boolean result = false;
        ClientResponse response = baseResource.path(PATH_JOINER.join("v1", "metrics", metric.name))
        .header(HttpHeaders.AUTHORIZATION, auth)
        .entity(new UpdateMetric(metric, pollFrequency), MediaType.APPLICATION_JSON_TYPE)
        .put(ClientResponse.class);
        try {
            BoundaryResponse br = mapper.readValue(response.getEntity(String.class), BoundaryResponse.class);
            result = br.result.success;
        }
        catch (Exception e) {}
        response.close();
        return result;
    }

    public void bevent(String source, String title, String message) {
        Map<String, Object> eMap = new HashMap<String, Object>();
        Map<String, String> sMap = new HashMap<String, String>();
        List<String> fpList = new Vector<String>();
        sMap.put("type", "host");
        sMap.put("ref", source);
        eMap.put("source", sMap);
        eMap.put("title", title);
        eMap.put("message", message);
        fpList.add("@title");
        fpList.add("@message");
        eMap.put("fingerprintFields", fpList);
        baseResource.path("v1/events")
        .header(HttpHeaders.AUTHORIZATION, auth)
        .entity(eMap, MediaType.APPLICATION_JSON_TYPE)
        .post(ClientResponse.class)
        .close();
    }

    public void addMeasurements(List<MonitoredMetric> metrics, Map<Integer, Number> measurements, Optional<DateTime> optionalTimestamp) {
        List<List<Object>> payload = Lists.newArrayList();
        final long timestamp = optionalTimestamp.or(new DateTime()).getMillis();
        for (MonitoredMetric m : metrics) {
            payload.add(ImmutableList.<Object>of(m.source, m.metric.name, measurements.get(m.ipmiid), timestamp));
        }
        asyncWebResource.path(PATH_JOINER.join("v1", "measurements"))
                .header(HttpHeaders.AUTHORIZATION, auth)
                .entity(payload, MediaType.APPLICATION_JSON_TYPE)
                .post(new TypeListener<ClientResponse>(ClientResponse.class) {
                    @Override
                    public void onComplete(Future<ClientResponse> f) throws InterruptedException {
                        try {
                            ClientResponse response = f.get();
                            response.close();
                            if (Response.Status.OK.getStatusCode() != response.getStatus()) {
                                LOG.error("Unexpected response adding measurements: {}", response.getStatusInfo());
                                throw new WebApplicationException(response.getStatus());
                            }
                            response.close();
                        } catch (ExecutionException e) {
                            LOG.error("Interrupted trying to add measurement");
                        }
                    }
                });
    }
}

package uk.gov.hmrc.flume.sink;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.mockito.Mockito.*;
import static org.apache.flume.Sink.Status;

import com.github.tomakehurst.wiremock.global.RequestDelaySpec;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit.WireMockRule;
import org.apache.flume.*;
import org.apache.flume.event.SimpleEvent;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.experimental.categories.Category;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Runs a set of tests against a correctly configured external running Flume
 * instance.
 */
@Category(IntegrationTest.class)
@RunWith(MockitoJUnitRunner.class)
public class HttpSinkIT {

    private static final int RESPONSE_TIMEOUT = 2000;
    private static final int CONNECT_TIMEOUT = 2000;

    private HttpSink httpSink;

    @Mock
    private Channel channel;

    @Mock
    private Transaction transaction;

    @Before
    public void setupSink() {
        if (httpSink == null) {
            Context context = new Context();
            context.put("endpoint", "http://localhost:8080/datastream");
            context.put("requestTimeout", "2000");
            context.put("connectionTimeout", "2000");
            context.put("acceptHeader", "application/json");
            context.put("contentTypeHeader", "application/json");

            httpSink = new HttpSink();
            httpSink.configure(context);
            httpSink.setChannel(channel);
            httpSink.start();
        }
    }

    @Rule
    public WireMockRule service = new WireMockRule(wireMockConfig().port(8080));

    @Test
    public void ensureSuccessfulMessageDelivery() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);

        service.addMockServiceRequestListener((request, response) -> gate.countDown());

        service.stubFor(post(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("SUCCESS")))
                .willReturn(aResponse().withStatus(200)));

        addEventToChannel(event("SUCCESS"));

        gate.await(10, TimeUnit.SECONDS);
        service.verify(1, postRequestedFor(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("SUCCESS"))));

        // wait till flume reads any responses before shutting down
        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
    }

    @Test
    public void ensureAuditEventsResentOn503Failure() throws Exception {
        final CountDownLatch gate = new CountDownLatch(2);

        service.addMockServiceRequestListener((request, response) -> gate.countDown());

        String errorScenario = "Error Scenario";

        service.stubFor(post(urlEqualTo("/datastream"))
                .inScenario(errorScenario)
                .whenScenarioStateIs(STARTED)
                .withRequestBody(equalToJson(event("TRANSIENT_ERROR")))
                .willReturn(aResponse().withStatus(503))
                .willSetStateTo("Error Sent"));

        service.stubFor(post(urlEqualTo("/datastream"))
                .inScenario(errorScenario)
                .whenScenarioStateIs("Error Sent")
                .withRequestBody(equalToJson(event("TRANSIENT_ERROR")))
                .willReturn(aResponse().withStatus(200)));

        addEventToChannel(event("TRANSIENT_ERROR"), false, Status.BACKOFF);
        addEventToChannel(event("TRANSIENT_ERROR"), true, Status.READY);

        gate.await(20, TimeUnit.SECONDS);
        service.verify(2, postRequestedFor(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("TRANSIENT_ERROR"))));

        // wait till flume reads any responses before shutting down
        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
    }

    @Test
    public void ensureAuditEventsResentOnNetworkFailure() throws Exception {
        final CountDownLatch gate = new CountDownLatch(2);

        service.addMockServiceRequestListener((request, response) -> gate.countDown());

        String errorScenario = "Error Scenario";

        service.stubFor(post(urlEqualTo("/datastream"))
                .inScenario(errorScenario)
                .whenScenarioStateIs(STARTED)
                .withRequestBody(equalToJson(event("NETWORK_ERROR")))
                .willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE))
                .willSetStateTo("Error Sent"));

        service.stubFor(post(urlEqualTo("/datastream"))
                .inScenario(errorScenario)
                .whenScenarioStateIs("Error Sent")
                .withRequestBody(equalToJson(event("NETWORK_ERROR")))
                .willReturn(aResponse().withStatus(200)));

        addEventToChannel(event("NETWORK_ERROR"), false, Status.BACKOFF);
        addEventToChannel(event("NETWORK_ERROR"), true, Status.READY);

        gate.await(10, TimeUnit.SECONDS);
        service.verify(2, postRequestedFor(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("NETWORK_ERROR"))));

        // wait till flume reads any responses before shutting down
        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
    }

    @Test
    public void ensureAuditEventsResentOnConnectionTimeout() throws Exception {
        final CountDownLatch gate = new CountDownLatch(2);

        service.addMockServiceRequestListener((request, response) -> {
                gate.countDown();
                service.addSocketAcceptDelay(new RequestDelaySpec(0));
            }
        );

        service.addSocketAcceptDelay(new RequestDelaySpec(CONNECT_TIMEOUT));
        service.stubFor(post(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("SLOW_SOCKET")))
                .willReturn(aResponse().withStatus(200)));

        addEventToChannel(event("SLOW_SOCKET"), false, Status.BACKOFF);
        addEventToChannel(event("SLOW_SOCKET"), true, Status.READY);

        gate.await(10, TimeUnit.SECONDS);
        service.verify(2, postRequestedFor(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("SLOW_SOCKET"))));

        // wait till flume reads any responses before shutting down
        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
    }

    @Test
    public void ensureAuditEventsResentOnRequestTimeout() throws Exception {
        final CountDownLatch gate = new CountDownLatch(2);

        service.addMockServiceRequestListener((request, response) -> gate.countDown());

        String errorScenario = "Error Scenario";

        service.stubFor(post(urlEqualTo("/datastream"))
                .inScenario(errorScenario)
                .whenScenarioStateIs(STARTED)
                .withRequestBody(equalToJson(event("SLOW_RESPONSE")))
                .willReturn(aResponse().withFixedDelay(RESPONSE_TIMEOUT).withStatus(200))
                .willSetStateTo("Slow Response Sent"));

        service.stubFor(post(urlEqualTo("/datastream"))
                .inScenario(errorScenario)
                .whenScenarioStateIs("Slow Response Sent")
                .withRequestBody(equalToJson(event("SLOW_RESPONSE")))
                .willReturn(aResponse().withStatus(200)));

        addEventToChannel(event("SLOW_RESPONSE"), false, Status.BACKOFF);
        addEventToChannel(event("SLOW_RESPONSE"), true, Status.READY);

        gate.await(10, TimeUnit.SECONDS);
        service.verify(2, postRequestedFor(urlEqualTo("/datastream"))
                .withRequestBody(equalToJson(event("SLOW_RESPONSE"))));

        // wait till flume reads any responses before shutting down
        new CountDownLatch(1).await(200, TimeUnit.MILLISECONDS);
    }

    private void addEventToChannel(String line) throws EventDeliveryException {
        addEventToChannel(line, true, Status.READY);
    }

    private void addEventToChannel(String line, boolean commitTx, Status expectedStatus)
            throws EventDeliveryException {

        SimpleEvent event = new SimpleEvent();
        event.setBody(line.getBytes());

        when(channel.getTransaction()).thenReturn(transaction);
        when(channel.take()).thenReturn(event);

        Sink.Status status = httpSink.process();

        assert(status == expectedStatus);

        if (commitTx) {
            verify(transaction).commit();
        } else {
            verify(transaction).rollback();
        }
    }

    private String event(String id) {
        return "{'id':'" + id + "'}";
    }
}

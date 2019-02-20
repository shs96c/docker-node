package org.infalible.selenium.docker;

import com.google.common.collect.ImmutableMap;
import org.openqa.selenium.concurrent.Regularly;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.grid.config.CompoundConfig;
import org.openqa.selenium.grid.config.Config;
import org.openqa.selenium.grid.config.EnvConfig;
import org.openqa.selenium.grid.config.MapConfig;
import org.openqa.selenium.grid.data.NodeStatusEvent;
import org.openqa.selenium.grid.log.LoggingOptions;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.server.BaseServer;
import org.openqa.selenium.grid.server.BaseServerOptions;
import org.openqa.selenium.grid.server.EventBusConfig;
import org.openqa.selenium.grid.server.Server;
import org.openqa.selenium.grid.server.W3CCommandHandler;
import org.openqa.selenium.grid.web.Routes;
import org.openqa.selenium.remote.tracing.DistributedTracer;
import org.openqa.selenium.remote.tracing.GlobalDistributedTracer;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;

public class Main {

  public static void main(String[] args) throws URISyntaxException {
    Config config = new CompoundConfig(
        new EnvConfig(),
        new MapConfig(ImmutableMap.of(
            "events", ImmutableMap.of(
                "publish", "tcp://*:4442",
                "subscribe", "tcp://*:4443"))));

    LoggingOptions loggingOptions = new LoggingOptions(config);
    loggingOptions.configureLogging();

    DistributedTracer tracer = loggingOptions.getTracer();
    GlobalDistributedTracer.setInstance(tracer);

    EventBusConfig events = new EventBusConfig(config);
    EventBus bus = events.getEventBus();

    BaseServerOptions serverOptions = new BaseServerOptions(config);

    String hostName = serverOptions.getHostname().orElse("localhost");
    URI host = new URI("http", null, hostName, serverOptions.getPort(), null, null, null);

    Node node = new DockerNode(tracer, host, bus);

    Server<?> server = new BaseServer<>(serverOptions);
    server.addRoute(Routes.matching(node).using(node).decorateWith(W3CCommandHandler.class));
    server.start();

    Regularly regularly = new Regularly("Register Node with Distributor");

    regularly.submit(
        () -> bus.fire(new NodeStatusEvent(node.getStatus())),
        Duration.ofMinutes(5),
        Duration.ofSeconds(30));
  }
}


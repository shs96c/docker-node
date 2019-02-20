package org.infalible.selenium.docker;

import com.google.common.collect.ImmutableMap;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.ContainerInfo;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.ImmutableCapabilities;
import org.openqa.selenium.NoSuchSessionException;
import org.openqa.selenium.UnsupportedCommandException;
import org.openqa.selenium.events.EventBus;
import org.openqa.selenium.events.zeromq.ZeroMqEventBus;
import org.openqa.selenium.firefox.FirefoxOptions;
import org.openqa.selenium.grid.component.HealthCheck;
import org.openqa.selenium.grid.data.NodeStatus;
import org.openqa.selenium.grid.data.Session;
import org.openqa.selenium.grid.data.SessionClosedEvent;
import org.openqa.selenium.grid.node.Node;
import org.openqa.selenium.grid.web.CommandHandler;
import org.openqa.selenium.grid.web.ReverseProxyHandler;
import org.openqa.selenium.net.PortProber;
import org.openqa.selenium.remote.RemoteWebDriver;
import org.openqa.selenium.remote.SessionId;
import org.openqa.selenium.remote.http.HttpClient;
import org.openqa.selenium.remote.http.HttpRequest;
import org.openqa.selenium.remote.http.HttpResponse;
import org.openqa.selenium.remote.tracing.DistributedTracer;
import org.openqa.selenium.support.ui.FluentWait;
import org.openqa.selenium.support.ui.Wait;
import org.zeromq.ZContext;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static org.openqa.selenium.remote.http.HttpMethod.DELETE;
import static org.openqa.selenium.remote.http.HttpMethod.GET;

public class DockerNode extends Node {

  private final int maxSessionCount;
  private final DockerClient docker;
  private final List<RunningInstance> instances = new ArrayList<>();
  private final EventBus bus;

  protected DockerNode(DistributedTracer tracer, URI uri, EventBus bus) {
    super(tracer, UUID.randomUUID(), uri);
    this.bus = bus;

    maxSessionCount = Runtime.getRuntime().availableProcessors();

    docker = new DefaultDockerClient("unix:///var/run/docker.sock");
  }

  @Override
  public Optional<Session> newSession(Capabilities capabilities) {
    if (instances.size() >= maxSessionCount) {
      return Optional.empty();
    }

    int nodePort = PortProber.findFreePort();

    RunningInstance instance = execute(() -> {
      HostConfig.Builder hostConfigBuilder = HostConfig.builder()
          .networkMode("bridge")
          .autoRemove(true)
          .privileged(false);

      Map<String, List<PortBinding>> portBindings = new HashMap<>();
      List<PortBinding> hostPorts = new ArrayList<>();
      hostPorts.add(PortBinding.of("", nodePort));
      portBindings.put("4444/tcp", hostPorts);
      hostConfigBuilder.portBindings(portBindings);

      HostConfig hostConfig = hostConfigBuilder.build();

      ContainerCreation container = docker.createContainer(ContainerConfig.builder()
          .image("selenium/standalone-firefox")
          .attachStderr(true)
          .attachStdout(true)
          .hostConfig(hostConfig)
          .build());

      System.out.println(container);
      ContainerInfo info = docker.inspectContainer(container.id());
      System.out.println(info);
      docker.startContainer(container.id());

      System.out.println("Waiting for port: " + nodePort);
      long start = System.currentTimeMillis();

      URL remoteAddress = null;
      try {
        remoteAddress = new URL(String.format("http://localhost:%s/wd/hub", nodePort));
      } catch (MalformedURLException e) {
        throw new UncheckedIOException(e);
      }

      Wait<Object> wait = new FluentWait<>(new Object())
          .withTimeout(Duration.ofSeconds(30))
          .ignoring(UncheckedIOException.class);

      HttpClient client = HttpClient.Factory.createDefault().createClient(remoteAddress);
      wait.until(obj -> {
        try {
          HttpResponse response = client.execute(new HttpRequest(GET, "/status"));
          return 200 == response.getStatus();
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });

      System.out.printf("Looks good! Took %d\n", (System.currentTimeMillis() - start) / 1000);

      try {

        RemoteWebDriver driver = new RemoteWebDriver(
            remoteAddress,
            capabilities);

        System.out.println(driver.getCapabilities());

        return new RunningInstance(
            new ImmutableCapabilities("browserName", "firefox"),
            container,
            nodePort,
            driver);
      } catch (MalformedURLException e) {
        throw new UncheckedIOException(e);
      }
    });

    instances.add(instance);
    Session session = new Session(instance.driver.getSessionId(), getUri(), instance.driver.getCapabilities());

    return Optional.of(session);
  }

  @Override
  public void executeWebDriverCommand(HttpRequest req, HttpResponse resp) {
    System.out.println("Executing: " + req);
    // True enough to be good enough
    if (!req.getUri().startsWith("/session/")) {
      throw new UnsupportedCommandException(String.format(
          "Unsupported command: (%s) %s", req.getMethod(), req.getMethod()));
    }

    String[] split = req.getUri().split("/", 4);
    SessionId id = new SessionId(split[2]);

    RunningInstance instance = instances.stream()
        .filter(i -> i.driver.getSessionId().equals(id))
        .findFirst()
        .orElseThrow(() -> new NoSuchSessionException("Cannot find session with id: " + id));

    try {
      instance.handler.execute(req, resp);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }

    if (req.getMethod() == DELETE && req.getUri().equals("/session/" + id)) {
      stop(id);
    }
  }

  @Override
  public Session getSession(SessionId sessionId) throws NoSuchSessionException {
    Objects.requireNonNull(sessionId);

    RunningInstance instance = instances.stream()
        .filter(i -> sessionId.equals(i.driver.getSessionId()))
        .findFirst()
        .orElseThrow(() -> new NoSuchSessionException("Cannot find session with ID: " + sessionId));

    return new Session(instance.driver.getSessionId(), getUri(), instance.driver.getCapabilities());
  }

  @Override
  public void stop(SessionId sessionId) throws NoSuchSessionException {
    System.out.println("Stopping! " + sessionId);

    Objects.requireNonNull(sessionId);

    RunningInstance instance = instances.stream()
        .filter(i -> sessionId.equals(i.driver.getSessionId()))
        .findFirst()
        .orElseThrow(() -> new NoSuchSessionException("Cannot find session with ID: " + sessionId));

    System.out.println("Have found " + instance);

    try {
      instance.driver.quit();
    } catch (NoSuchSessionException e) {
      // Ignored
    }

    execute(() -> {
      docker.stopContainer(instance.container.id(), 10);
      return null;
    });

    bus.fire(new SessionClosedEvent(sessionId));
  }

  @Override
  protected boolean isSessionOwner(SessionId sessionId) {
    return instances.stream().anyMatch(i -> sessionId.equals(i.driver.getSessionId()));
  }

  @Override
  public boolean isSupporting(Capabilities capabilities) {
    return "firefox".equals(capabilities.getBrowserName());
  }

  @Override
  public NodeStatus getStatus() {
    Set<NodeStatus.Active> actives = instances.stream()
        .map(instance -> new NodeStatus.Active(
            instance.stereotype,
            instance.driver.getSessionId(),
            instance.driver.getCapabilities()))
        .collect(toImmutableSet());

    return new NodeStatus(
        getId(),
        getUri(),
        maxSessionCount,
        ImmutableMap.of(new ImmutableCapabilities("browserName", "firefox"), maxSessionCount),
        actives);
  }

  @Override
  public HealthCheck getHealthCheck() {
    return () -> new HealthCheck.Result(true, "Making an assumption");
  }

  private <V> V execute(DockerCmd<V> toExecute) {
    try {
      return toExecute.execute();
    } catch (DockerException e) {
      throw new RuntimeException(e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
  }

  private static class RunningInstance {
    public final Capabilities stereotype;
    public final ContainerCreation container;
    public final int port;
    public final RemoteWebDriver driver;
    public final CommandHandler handler;

    public RunningInstance(
        Capabilities stereotype,
        ContainerCreation container,
        int port,
        RemoteWebDriver driver) throws MalformedURLException {
      this.stereotype = stereotype;
      this.container = container;
      this.port = port;
      this.driver = driver;
      this.handler = new ReverseProxyHandler(
          HttpClient.Factory.createDefault().createClient(new URL(String.format("http://localhost:%d/wd/hub", port))));
    }
  }

  @FunctionalInterface
  private interface DockerCmd<V> {
    V execute() throws DockerException, InterruptedException;
  }

  public static void main(String[] args) throws UnknownHostException, MalformedURLException, URISyntaxException {

    URL url = new URL("http", InetAddress.getLocalHost().getHostAddress(), PortProber.findFreePort(), "");

    Node node = new DockerNode(
        DistributedTracer.builder().build(),
        url.toURI(),
        ZeroMqEventBus.create(new ZContext(), "inproc://pub", "inproc://sub", true));

    Session session = node.newSession(new FirefoxOptions()).orElseThrow(() -> new RuntimeException("Eeep"));
    System.out.println(session);
  }
}

/*
 * MIT License
 *
 * Copyright (c) 2019 1619kHz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.aquiver.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.util.ResourceLeakDetector;
import org.aquiver.*;
import org.aquiver.advice.Advice;
import org.aquiver.advice.AdviceManager;
import org.aquiver.mvc.annotation.advice.ExceptionHandler;
import org.aquiver.mvc.annotation.advice.RouteAdvice;
import org.aquiver.mvc.resolver.ParamResolverManager;
import org.aquiver.mvc.route.PathRouteFinder;
import org.aquiver.mvc.route.RouteFinder;
import org.aquiver.mvc.route.RouteManager;
import org.aquiver.mvc.route.RouteParam;
import org.aquiver.server.banner.Banner;
import org.aquiver.server.watcher.GlobalEnvListener;
import org.aquiver.server.watcher.GlobalEnvTask;
import org.aquiver.utils.ReflectionUtils;
import org.aquiver.utils.SystemUtils;
import org.aquiver.websocket.WebSocket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.net.ssl.SSLException;
import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Paths;
import java.security.cert.CertificateException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.aquiver.Const.*;

/**
 * open ssl {@code initSSL}
 * open webSocket {@code initWebSocket}
 * open the Http com.aquiver.service {@code startServer}
 * observe the environment {@code watchEnv}
 * configure the shutdown thread to stop the hook {@code shutdownHook}
 *
 * @author WangYi
 * @since 2019/6/5
 */
public class NettyServer implements Server {
  private static final Logger log = LoggerFactory.getLogger(NettyServer.class);

  /** Banner and bootstrap when service starts. */
  private final Banner defaultBanner = new NettyServerBanner();
  private final ServerBootstrap serverBootstrap = new ServerBootstrap();

  /** Netty builds long connection service. */
  private EventLoopGroup bossGroup;
  private EventLoopGroup workerGroup;
  private Environment environment;
  private Aquiver aquiver;
  private Channel channel;
  private SslContext sslContext;

  /** route manager. */
  private RouteManager routeManager;
  private AdviceManager adviceManager;
  private ParamResolverManager resolverManager;

  /** Service startup status, using volatile to ensure threads are visible. */
  private volatile boolean stop = false;

  /**
   * start server and init setting
   *
   * @param aquiver Aquiver
   * @throws Exception Common Exception
   */
  @Override
  public void start(Aquiver aquiver) throws Exception {
    long startMs = System.currentTimeMillis();

    this.aquiver = aquiver;
    this.environment = aquiver.environment();
    this.routeManager = Aquiver.of().routeManager();
    this.adviceManager = Aquiver.of().adviceManager();
    this.resolverManager = Aquiver.of().resolverManager();
    this.printBanner();

    final String bootClsName = aquiver.bootClsName();
    final String bootConfName = aquiver.bootConfName();
    final String envName = aquiver.envName();
    final String deviceName = SystemUtils.getDeviceName();
    final String currentUserName = System.getProperty("user.name");
    final String pidCode = SystemUtils.getPid();

    log.info("Starting {} on {} with PID {} ", bootClsName, deviceName + "/" + currentUserName, pidCode);
    log.info("Starting service [Netty]");
    log.info("Starting Http Server: Netty/4.1.36.Final");
    log.info("The configuration file loaded by this application startup is {}", bootConfName);

    this.configLoadLog(aquiver, envName);

    this.initSsl();
    this.initComponent();
    this.startServer(startMs);
    this.watchEnv();
    this.shutdownHook();
  }

  /**
   * record the log loaded by the configuration
   *
   * @param aquiver Aquiver
   * @param envName Enabled environment name
   */
  private void configLoadLog(Aquiver aquiver, String envName) {
    if (aquiver.masterConfig()) {
      log.info("Configuration information is loaded");
    }
    if (!aquiver.envConfig()) {
      log.info("No active profile set, falling back to default profiles: default");
    }
    log.info("The application startup env is: {}", envName);
  }

  /**
   * init ioc container
   */
  private void initComponent() throws Exception {
    final String scanPath = aquiver.bootCls().getPackage().getName();

    final ClassgraphOptions classgraphOptions = ClassgraphOptions.builder()
            .verbose(aquiver.verbose()).enableRealtimeLogging(aquiver.realtimeLogging())
            .scanPackages(aquiver.scanPaths()).build();

    final Scanner scanner = new ClassgraphScanner(classgraphOptions);
    final Set<Class<?>> classSet = scanner.scan(scanPath);

    if (classSet.isEmpty()) {
      return;
    }
    this.resolverManager.initialize(classSet);
    this.routeManager.setResolverManager(resolverManager);
    this.loadRoute(classSet);
    this.loadAdvice(classSet);
    this.loadWebSocket(classSet);
  }

  /**
   * Filter out the routing class from the scanned
   * result set and add it to the routing manager
   *
   * @param classSet Scanned result
   */
  public void loadRoute(Set<Class<?>> classSet) {
    final RouteFinder routeFinder = new PathRouteFinder();
    try {
      Map<String, Class<?>> routeClsMap = routeFinder.finderRoute(classSet);
      for (Map.Entry<String, Class<?>> entry : routeClsMap.entrySet()) {
        this.routeManager.addRoute(entry.getValue(), entry.getKey());
      }
    } catch (Throwable e) {
      log.error("An exception occurred while load route", e);
    }
  }

  /**
   * Filter the Advice class from the scanned result
   * set and add it to the Advice Manager
   *
   * @param classSet Scanned result
   * @throws ReflectiveOperationException Exception superclass when
   *                                      performing reflection operation
   */
  private void loadAdvice(Set<Class<?>> classSet) throws ReflectiveOperationException {
    for (Class<?> cls : classSet) {
      Method[] declaredMethods = cls.getDeclaredMethods();
      if (!ReflectionUtils.isNormal(cls)
              || !cls.isAnnotationPresent(RouteAdvice.class)
              || declaredMethods.length == 0) {
        continue;
      }
      for (Method method : declaredMethods) {
        ExceptionHandler declaredAnnotation =
                method.getDeclaredAnnotation(ExceptionHandler.class);
        if (Objects.isNull(declaredAnnotation)) {
          continue;
        }
        Parameter[] parameters = method.getParameters();
        String[] paramNames = resolverManager.getMethodParamName(method);
        List<RouteParam> routeParams = this.resolverManager.invokeParamResolver(parameters, paramNames);

        Advice advice = Advice.builder().clazz(cls).exception(declaredAnnotation.value())
                .methodName(method.getName()).params(routeParams).target(cls.newInstance());
        this.adviceManager.addAdvice(declaredAnnotation.value(), advice);
      }
    }
  }

  /**
   * Filter out the classes marked with Web Socket annotations
   * from the scan result set and reduce them to the Web Socket
   * list of the routing manager
   *
   * @param classSet Scanned result
   * @throws ReflectiveOperationException Exception superclass when
   *                                      performing reflection operation
   */
  private void loadWebSocket(Set<Class<?>> classSet) throws ReflectiveOperationException {
    for (Class<?> cls : classSet) {
      Method[] declaredMethods = cls.getDeclaredMethods();
      if (!ReflectionUtils.isNormal(cls)
              || !cls.isAnnotationPresent(WebSocket.class)
              || declaredMethods.length == 0) continue;
      this.routeManager.addWebSocket(cls);
    }
  }

  /**
   * init ssl connection
   *
   * @throws CertificateException This exception indicates one of a variety of certificate problems.
   * @throws SSLException         Indicates some kind of error detected by an SSL subsystem.
   *                              This class is the general class of exceptions produced
   *                              by failed SSL-related operations.
   */
  private void initSsl() throws CertificateException, SSLException {
    log.info("Check if the ssl configuration is enabled.");

    final Boolean ssl = environment.getBoolean(PATH_SERVER_SSL, SERVER_SSL);
    final SelfSignedCertificate ssc = new SelfSignedCertificate();

    if (ssl) {
      log.info("Ssl configuration takes effect :{}", true);

      final String sslCert = this.environment.get(PATH_SERVER_SSL_CERT, null);
      final String sslPrivateKey = this.environment.get(PATH_SERVER_SSL_PRIVATE_KEY, null);
      final String sslPrivateKeyPass = this.environment.get(PATH_SERVER_SSL_PRIVATE_KEY_PASS, null);

      log.info("SSL CertChainFile  Path: {}", sslCert);
      log.info("SSL PrivateKeyFile Path: {}", sslPrivateKey);
      log.info("SSL PrivateKey Pass: {}", sslPrivateKeyPass);

      sslContext = SslContextBuilder.forServer(setKeyCertFileAndPriKey(sslCert, ssc.certificate()),
              setKeyCertFileAndPriKey(sslPrivateKey, ssc.privateKey()), sslPrivateKeyPass).build();
    }

    log.info("Current netty server ssl startup status: {}", ssl);
    log.info("A valid ssl connection configuration is not configured and is rolled back to the default connection state.");
  }

  /**
   * Open an com.aquiver.http server and initialize the Netty configuration.
   * {@link NettyServerInitializer}
   * <p>
   * After initializing, the thread group is initialized according to the configuration parameters, including
   * {@code netty.accept-thread-count} 和 {@code netty.io-thread-count}
   * <p>
   * Then, according to the current system, the choice of communication model for Mac or Windows, NIO mode on Windows.
   * Judgment method in {@link EventLoopKit}, {@code nioGroup} is the judgment method under windows
   * {@code epollGroup} is the judgment method for Mac system or Linux system.
   * <p>
   * <p>
   * IO threads, in order to make full use of the CPU, while considering reducing the overhead of line context
   * switching, usually set to twice the number of CPU coresFor scenes with high response time requirements,
   * disable the nagle algorithm and send immediately without waiting.
   * After these are done, they will get the port and ip address to start. These are all user-configurable.
   * </p>
   * <p>
   * Bootstrap configuration parameter, specify a pooled Allocator, PooledByteBufAllocator pooled multiplexingIn
   * IO operation, direct memory is allocated instead of JVM heap space, to avoid copying from JVM to direct memory
   * when sending data
   * </p>
   *
   * @param startTime Startup time for calculating start time
   * @throws Exception Common exception
   */
  private void startServer(long startTime) throws Exception {

    ResourceLeakDetector.setLevel(ResourceLeakDetector.Level.DISABLED);

    this.serverBootstrap.childHandler(new NettyServerInitializer(sslContext));

    int acceptThreadCount = environment.getInteger(PATH_SERVER_NETTY_ACCEPT_THREAD_COUNT, DEFAULT_ACCEPT_THREAD_COUNT);
    int ioThreadCount = environment.getInteger(PATH_SERVER_NETTY_IO_THREAD_COUNT, DEFAULT_IO_THREAD_COUNT);

    NettyServerGroup nettyServerGroup = EventLoopKit.nioGroup(acceptThreadCount, ioThreadCount);
    this.bossGroup = nettyServerGroup.getBossGroup();
    this.workerGroup = nettyServerGroup.getWorkGroup();

    if (EventLoopKit.epollIsAvailable()) {
      nettyServerGroup = EventLoopKit.epollGroup(acceptThreadCount, ioThreadCount);
      this.bossGroup = nettyServerGroup.getBossGroup();
      this.workerGroup = nettyServerGroup.getWorkGroup();
    }

    this.serverBootstrap.group(bossGroup, workerGroup).option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
            .channel(nettyServerGroup.getChannelClass());

    log.info("The IO mode of the application startup is: {}",
            EventLoopKit.judgeMode(nettyServerGroup.getChannelClass().getSimpleName()));

    this.stop = false;

    final Integer port = this.environment.getInteger(PATH_SERVER_PORT, SERVER_PORT);
    final String address = this.environment.get(PATH_SERVER_ADDRESS, SERVER_ADDRESS);
    this.channel = serverBootstrap.bind(address, port).sync().channel();

    long endTime = System.currentTimeMillis();
    long startUpTime = (endTime - startTime);
    long jvmStartTime = (endTime - SystemUtils.getJvmStartUpTime());

    log.info("Aquiver started on port(s): {} (com.aquiver.http) with context path ''", port);
    log.info("Started {} in {} ms (JVM running for {} ms)", aquiver.bootClsName(), startUpTime, jvmStartTime);
  }

  /**
   * stop http server
   */
  @Override
  public void stop() {
    log.info("Netty Server Shutdown...");
    if (stop) {
      return;
    }
    stop = true;
    try {
      if (Objects.nonNull(bossGroup)) {
        this.bossGroup.shutdownGracefully();
      }
      if (Objects.nonNull(workerGroup)) {
        this.workerGroup.shutdownGracefully();
      }
      log.info("The netty service is gracefully closed");
    } catch (Exception e) {
      log.error("An exception occurred while the Netty Http service was down", e);
    }
  }

  @Override
  public void join() {
    try {
      this.channel.closeFuture().sync();
    } catch (InterruptedException e) {
      log.error("Channel close future fail", e);
    }
  }

  /**
   * Observe the environment class, which can be opened by configuration.
   * After opening, it will perform various operations through the log
   * printing environment.
   */
  private void watchEnv() {
    boolean watch = this.environment.getBoolean(PATH_ENV_WATCHER, false);
    if (watch) {
      log.info("start application watcher");
      final GlobalEnvListener fileListener = new GlobalEnvListener();
      GlobalEnvTask.config().watchPath(SERVER_WATCHER_PATH).listener(fileListener).start();
    }
  }

  /**
   * Add a hook that stops the current service when the system is shut down
   */
  private void shutdownHook() {
    Thread shutdownThread = new Thread(this::stop);
    shutdownThread.setName("shutdown@thread");
    Runtime.getRuntime().addShutdownHook(shutdownThread);
  }

  /**
   * print default banner
   */
  private void printBanner() {
    this.defaultBanner.printBanner(System.out, aquiver.bannerText(), aquiver.bannerFont());
  }

  /**
   * Use the configured path if the certificate and private key are
   * configured, otherwise use the default configuration
   *
   * @param keyPath         private keystore path
   * @param defaultFilePath default private keystore path
   * @return keystore file
   */
  private File setKeyCertFileAndPriKey(String keyPath, File defaultFilePath) {
    return Objects.nonNull(keyPath) ? Paths.get(keyPath).toFile() : defaultFilePath;
  }
}

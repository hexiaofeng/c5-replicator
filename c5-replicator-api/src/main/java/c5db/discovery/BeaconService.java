/*
 * Copyright 2014 WANdisco
 *
 *  WANdisco licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package c5db.discovery;

import c5db.codec.UdpProtostuffDecoder;
import c5db.codec.UdpProtostuffEncoder;
import c5db.discovery.generated.Availability;
import c5db.discovery.generated.ModuleDescriptor;
import c5db.interfaces.DiscoveryModule;
import c5db.interfaces.ModuleInformationProvider;
import c5db.interfaces.discovery.NewNodeVisible;
import c5db.interfaces.discovery.NodeInfo;
import c5db.interfaces.discovery.NodeInfoReply;
import c5db.interfaces.discovery.NodeInfoRequest;
import c5db.messages.generated.ModuleType;
import c5db.util.C5Futures;
import c5db.util.FiberOnly;
import c5db.util.FiberSupplier;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.net.InetAddresses;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.jetbrains.annotations.NotNull;
import org.jetlang.channels.MemoryChannel;
import org.jetlang.channels.MemoryRequestChannel;
import org.jetlang.channels.Request;
import org.jetlang.channels.RequestChannel;
import org.jetlang.channels.Subscriber;
import org.jetlang.fibers.Fiber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static c5db.codec.UdpProtostuffEncoder.UdpProtostuffMessage;

/**
 * Uses broadcast UDP packets to discover 'adjacent' nodes in the cluster. Maintains
 * a state table for them, and provides information to other modules as they request it.
 * <p>
 * Currently UDP broadcast has some issues on Mac OSX vs Linux.  The big question,
 * specifically, is what happens when multiple processes bind to 255.255.255.255:PORT
 * and send packets?  Which processes receive such packets?
 * <ul>
 * <li>On Mac OSX 10.8/9, all processes reliably receive all packets including
 * the originating process</li>
 * <li>On Linux (Ubuntu, modern) a variety of things appear to occur:
 * <ul>
 * <li>First to bind receives all packets</li>
 * <li>All processes receives all packets</li>
 * <li>No one receives any packets</li>
 * <li>Please fill this doc in!</li>
 * </ul></li>
 * </ul>
 * <p>
 * The beacon service needs to be refactored and different discovery methods need to be
 * pluggable but all behind the discovery module interface.
 */
public class BeaconService extends AbstractService implements DiscoveryModule {
  private static final Logger LOG = LoggerFactory.getLogger(BeaconService.class);
  private static final InetAddress BROADCAST_ADDRESS = InetAddresses.forString("255.255.255.255");
  private static final int BEACON_SERVICE_INITIAL_BROADCAST_DELAY_MILLISECONDS = 2000;
  private static final int BEACON_SERVICE_BROADCAST_PERIOD_MILLISECONDS = 10000;

  @Override
  public ModuleType getModuleType() {
    return ModuleType.Discovery;
  }

  @Override
  public boolean hasPort() {
    return true;
  }

  @Override
  public int port() {
    return discoveryPort;
  }

  @Override
  public String acceptCommand(String commandString) {
    return null;
  }

  private final RequestChannel<NodeInfoRequest, NodeInfoReply> nodeInfoRequests = new MemoryRequestChannel<>();

  @Override
  public RequestChannel<NodeInfoRequest, NodeInfoReply> getNodeInfo() {
    return nodeInfoRequests;
  }

  @Override
  public ListenableFuture<NodeInfoReply> getNodeInfo(long nodeId, ModuleType module) {
    SettableFuture<NodeInfoReply> future = SettableFuture.create();
    fiber.execute(() -> {
      NodeInfo peer = peerNodeInfoMap.get(nodeId);
      if (peer == null) {
        future.set(NodeInfoReply.NO_REPLY);
      } else {
        Integer servicePort = peer.modules.get(module);
        if (servicePort == null) {
          future.set(NodeInfoReply.NO_REPLY);
        } else {
          List<String> peerAddresses = peer.availability.getAddressesList();
          future.set(new NodeInfoReply(true, peerAddresses, servicePort));
        }
      }
    });
    return future;
  }

  @FiberOnly
  private void handleNodeInfoRequest(Request<NodeInfoRequest, NodeInfoReply> message) {
    NodeInfoRequest req = message.getRequest();
    NodeInfo peer = peerNodeInfoMap.get(req.nodeId);
    if (peer == null) {
      message.reply(NodeInfoReply.NO_REPLY);
      return;
    }

    Integer servicePort = peer.modules.get(req.moduleType);
    if (servicePort == null) {
      message.reply(NodeInfoReply.NO_REPLY);
      return;
    }

    List<String> peerAddresses = peer.availability.getAddressesList();
    if (peerAddresses == null || peerAddresses.isEmpty()) {
      message.reply(NodeInfoReply.NO_REPLY);
      return;
    }

    // does this module run on that peer?
    message.reply(new NodeInfoReply(true, peerAddresses, servicePort));
  }

  @Override
  public String toString() {
    return "BeaconService{" +
        "discoveryPort=" + discoveryPort +
        ", nodeId=" + nodeId +
        '}';
  }

  // For main system modules/pubsub stuff.
  private final long nodeId;
  private final int discoveryPort;
  private final EventLoopGroup eventLoopGroup;
  private final InetSocketAddress broadcastAddress;
  private final InetSocketAddress loopbackAddress;
  private final Map<Long, NodeInfo> peerNodeInfoMap = new HashMap<>();
  private final org.jetlang.channels.Channel<Availability> incomingMessages = new MemoryChannel<>();
  private final org.jetlang.channels.Channel<NewNodeVisible> newNodeVisibleChannel = new MemoryChannel<>();
  private final ModuleInformationProvider moduleInformationProvider;
  private final FiberSupplier fiberSupplier;

  // These should be final, but they are initialized in doStart().
  private Channel broadcastChannel = null;
  private Bootstrap bootstrap = null;
  private List<String> localIPs;
  private Fiber fiber;

  // This field is updated when modules' availability changes. It must only be accessed from the fiber.
  private ImmutableMap<ModuleType, Integer> onlineModuleToPortMap = ImmutableMap.of();

  private class BeaconMessageHandler extends SimpleChannelInboundHandler<Availability> {
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
      LOG.warn("Exception, ignoring datagram", cause);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, Availability msg) throws Exception {
      incomingMessages.publish(msg);
    }
  }

  /**
   * @param nodeId                    the id of this node.
   * @param discoveryPort             the port to send discovery beacon messages on, and to listen to
   *                                  for messages from others
   * @param eventLoopGroup            An EventLoopGroup that's not shut down.
   * @param moduleInformationProvider Used to receive module availability updates
   */
  public BeaconService(long nodeId,
                       int discoveryPort,
                       EventLoopGroup eventLoopGroup,
                       ModuleInformationProvider moduleInformationProvider,
                       FiberSupplier fiberSupplier
  ) {
    this.nodeId = nodeId;
    this.discoveryPort = discoveryPort;
    this.eventLoopGroup = eventLoopGroup;
    this.moduleInformationProvider = moduleInformationProvider;
    this.fiberSupplier = fiberSupplier;
    this.broadcastAddress = new InetSocketAddress(BROADCAST_ADDRESS, discoveryPort);
    this.loopbackAddress = new InetSocketAddress(InetAddress.getLoopbackAddress(), discoveryPort);
  }

  @Override
  public ListenableFuture<ImmutableMap<Long, NodeInfo>> getState() {
    final SettableFuture<ImmutableMap<Long, NodeInfo>> future = SettableFuture.create();

    fiber.execute(() -> {
      future.set(getCopyOfState());
    });

    return future;
  }


  @Override
  public Subscriber<NewNodeVisible> getNewNodeNotifications() {
    return newNodeVisibleChannel;
  }

  private ImmutableMap<Long, NodeInfo> getCopyOfState() {
    return ImmutableMap.copyOf(peerNodeInfoMap);
  }

  @FiberOnly
  private void sendBeacon() {
    if (broadcastChannel == null) {
      LOG.debug("Channel not available yet, deferring beacon send");
      return;
    }
    LOG.trace("Sending beacon broadcast message to {}", broadcastAddress);

    List<ModuleDescriptor> msgModules = new ArrayList<>(onlineModuleToPortMap.size());
    for (ModuleType moduleType : onlineModuleToPortMap.keySet()) {
      msgModules.add(
          new ModuleDescriptor(moduleType,
              onlineModuleToPortMap.get(moduleType))
      );
    }

    if (!localIPs.isEmpty()) {
      Availability beaconMessage = new Availability(nodeId, 0, localIPs, msgModules);

      broadcastChannel.writeAndFlush(new UdpProtostuffMessage<>(broadcastAddress, beaconMessage))
          .addListener(
              future -> {
                if (!future.isSuccess()) {
                  LOG.warn("node {} error sending message {} to broadcast address {}",
                      nodeId, beaconMessage, broadcastAddress);
                }
              });
    }

    List<String> loopbackIps = Lists.newArrayList(loopbackAddress.getAddress().getHostAddress());
    Availability localMessage = new Availability(nodeId, 0, loopbackIps, msgModules);

    broadcastChannel.writeAndFlush(new UdpProtostuffMessage<>(loopbackAddress, localMessage))
        .addListener(
            future -> {
              if (!future.isSuccess()) {
                LOG.warn("node {} error sending message {} to loopback address", nodeId, localMessage);
              }
            });

    // Fix issue #76, feed back the beacon Message to our own database:
    processWireMessage(localMessage);
  }

  @FiberOnly
  private void processWireMessage(Availability message) {
    LOG.trace("Got incoming message {}", message);
    if (message.getNodeId() == 0) {
//        if (!message.hasNodeId()) {
      LOG.error("Incoming availability message does not have node id, ignoring!");
      return;
    }
    // Always just overwrite what was already there for now.
    // TODO consider a more sophisticated merge strategy?
    NodeInfo nodeInfo = new NodeInfo(message);
    if (!peerNodeInfoMap.containsKey(message.getNodeId())) {
      newNodeVisibleChannel.publish(new NewNodeVisible(message.getNodeId(), nodeInfo));
    }

    peerNodeInfoMap.put(message.getNodeId(), nodeInfo);
  }

  @Override
  protected void doStart() {
    eventLoopGroup.next().execute(() -> {
      bootstrap = new Bootstrap();
      bootstrap.group(eventLoopGroup)
          .channel(NioDatagramChannel.class)
          .option(ChannelOption.SO_BROADCAST, true)
          .option(ChannelOption.SO_REUSEADDR, true)
          .handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            protected void initChannel(DatagramChannel ch) throws Exception {
              ChannelPipeline p = ch.pipeline();

              p.addLast("protobufDecoder",
                  new UdpProtostuffDecoder<>(Availability.getSchema(), false));

              p.addLast("protobufEncoder",
                  new UdpProtostuffEncoder<>(Availability.getSchema(), false));

              p.addLast("beaconMessageHandler", new BeaconMessageHandler());
            }
          });
      // Wait, this is why we are in a new executor...
      //noinspection RedundantCast
      bootstrap.bind(discoveryPort).addListener((ChannelFutureListener) future -> {
        if (future.isSuccess()) {
          broadcastChannel = future.channel();
        } else {
          LOG.error("Unable to bind! ", future.cause());
          notifyFailed(future.cause());
        }
      });

      try {
        localIPs = getLocalIPs();
      } catch (SocketException e) {
        LOG.error("SocketException:", e);
        notifyFailed(e);
        return;
      }

      fiber = fiberSupplier.getNewFiber(this::notifyFailed);
      fiber.start();

      // Schedule fiber tasks and subscriptions.
      incomingMessages.subscribe(fiber, this::processWireMessage);
      nodeInfoRequests.subscribe(fiber, this::handleNodeInfoRequest);
      moduleInformationProvider.moduleChangeChannel().subscribe(fiber, this::updateCurrentModulePorts);

      if (localIPs.isEmpty()) {
        LOG.warn("Found no IP addresses to broadcast to other nodes; as a result, only sending to loopback");
      }

      fiber.scheduleAtFixedRate(this::sendBeacon,
          BEACON_SERVICE_INITIAL_BROADCAST_DELAY_MILLISECONDS,
          BEACON_SERVICE_BROADCAST_PERIOD_MILLISECONDS,
          TimeUnit.MILLISECONDS);

      C5Futures.addCallback(moduleInformationProvider.getOnlineModules(),
          (ImmutableMap<ModuleType, Integer> onlineModuleToPortMap) -> {
            updateCurrentModulePorts(onlineModuleToPortMap);
            notifyStarted();
          },
          this::notifyFailed,
          fiber);
    });
  }

  @Override
  protected void doStop() {
    fiber.dispose();
    fiber = null;
    eventLoopGroup.next().execute(this::notifyStopped);
  }

  @FiberOnly
  private void updateCurrentModulePorts(ImmutableMap<ModuleType, Integer> onlineModuleToPortMap) {
    if (onlineModuleToPortMap == null) {
      notifyFailed(new NullPointerException("received null instead of a map of online modules to their ports"));
      return;
    }
    this.onlineModuleToPortMap = onlineModuleToPortMap;
  }

  @NotNull
  private List<String> getLocalIPs() throws SocketException {
    List<String> ips = new LinkedList<>();
    for (Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces(); interfaces.hasMoreElements(); ) {
      NetworkInterface networkInterface = interfaces.nextElement();
      if (networkInterface.isPointToPoint()) {
        continue; //ignore tunnel type interfaces
      }
      for (Enumeration<InetAddress> addrs = networkInterface.getInetAddresses(); addrs.hasMoreElements(); ) {
        InetAddress addr = addrs.nextElement();
        if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() || addr.isAnyLocalAddress()) {
          continue;
        }
        ips.add(addr.getHostAddress());
      }
    }
    return ips;
  }
}

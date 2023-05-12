package com.flipkart.krystal.krystex.node;

import static com.flipkart.krystal.utils.Futures.linkFutures;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.concurrent.CompletableFuture.allOf;
import static java.util.concurrent.CompletableFuture.runAsync;
import static java.util.concurrent.CompletableFuture.supplyAsync;
import static java.util.function.Function.identity;

import com.flipkart.krystal.data.Inputs;
import com.flipkart.krystal.krystex.KrystalExecutor;
import com.flipkart.krystal.krystex.MainLogicDefinition;
import com.flipkart.krystal.krystex.RequestId;
import com.flipkart.krystal.krystex.commands.ExecuteWithInputs;
import com.flipkart.krystal.krystex.commands.Flush;
import com.flipkart.krystal.krystex.commands.NodeRequestCommand;
import com.flipkart.krystal.krystex.decoration.InitiateActiveDepChains;
import com.flipkart.krystal.krystex.decoration.LogicDecorationOrdering;
import com.flipkart.krystal.krystex.decoration.LogicExecutionContext;
import com.flipkart.krystal.krystex.decoration.MainLogicDecorator;
import com.flipkart.krystal.krystex.decoration.MainLogicDecoratorConfig;
import com.flipkart.krystal.krystex.decoration.MainLogicDecoratorConfig.DecoratorContext;
import com.flipkart.krystal.utils.MultiLeasePool;
import com.flipkart.krystal.utils.MultiLeasePool.Lease;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;

/** Default implementation of Krystal executor which */
@Slf4j
public final class KrystalNodeExecutor implements KrystalExecutor {

  private final NodeDefinitionRegistry nodeDefinitionRegistry;
  private final LogicDecorationOrdering logicDecorationOrdering;
  private final Lease<? extends ExecutorService> commandQueueLease;
  private final RequestId requestId;
  private final ImmutableMap</*DecoratorType*/ String, MainLogicDecoratorConfig>
      requestScopedLogicDecoratorConfigs;

  /** DecoratorType -> {InstanceId -> Decorator} */
  private final Map<String, Map<String, MainLogicDecorator>> requestScopedMainDecorators =
      new LinkedHashMap<>();

  private final NodeRegistry nodeRegistry = new NodeRegistry();
  private volatile boolean closed;
  private final Map<RequestId, List<NodeResult>> allRequests = new LinkedHashMap<>();
  private final Map<RequestId, List<NodeResult>> unFlushedRequests = new LinkedHashMap<>();
  private final Map<NodeId, Set<DependantChain>> dependantChainsPerNode = new LinkedHashMap<>();

  public KrystalNodeExecutor(
      NodeDefinitionRegistry nodeDefinitionRegistry,
      LogicDecorationOrdering logicDecorationOrdering,
      MultiLeasePool<? extends ExecutorService> commandQueuePool,
      String requestId,
      Map<String, MainLogicDecoratorConfig> requestScopedLogicDecoratorConfigs) {
    this.nodeDefinitionRegistry = nodeDefinitionRegistry;
    this.logicDecorationOrdering = logicDecorationOrdering;
    this.commandQueueLease = commandQueuePool.lease();
    this.requestId = new RequestId(requestId);
    this.requestScopedLogicDecoratorConfigs =
        ImmutableMap.copyOf(requestScopedLogicDecoratorConfigs);
  }

  private ImmutableMap<String, MainLogicDecorator> getRequestScopedDecorators(
      LogicExecutionContext logicExecutionContext) {
    NodeId nodeId = logicExecutionContext.nodeId();
    NodeDefinition nodeDefinition = nodeDefinitionRegistry.get(nodeId);
    MainLogicDefinition<?> mainLogicDefinition =
        nodeDefinitionRegistry.logicDefinitionRegistry().getMain(nodeDefinition.mainLogicNode());
    Map<String, MainLogicDecorator> decorators = new LinkedHashMap<>();
    Stream.concat(
            mainLogicDefinition.getRequestScopedLogicDecoratorConfigs().entrySet().stream(),
            requestScopedLogicDecoratorConfigs.entrySet().stream())
        .forEach(
            entry -> {
              String decoratorType = entry.getKey();
              MainLogicDecoratorConfig decoratorConfig = entry.getValue();
              if (decoratorConfig.shouldDecorate().test(logicExecutionContext)) {
                String instanceId =
                    decoratorConfig.instanceIdGenerator().apply(logicExecutionContext);
                MainLogicDecorator mainLogicDecorator =
                    requestScopedMainDecorators
                        .computeIfAbsent(decoratorType, t -> new LinkedHashMap<>())
                        .computeIfAbsent(
                            instanceId,
                            _i ->
                                decoratorConfig
                                    .factory()
                                    .apply(
                                        new DecoratorContext(instanceId, logicExecutionContext)));
                mainLogicDecorator.executeCommand(
                    new InitiateActiveDepChains(
                        nodeId, ImmutableSet.copyOf(dependantChainsPerNode.get(nodeId))));
                decorators.put(decoratorType, mainLogicDecorator);
              }
            });
    return ImmutableMap.copyOf(decorators);
  }

  @Override
  public <T> CompletableFuture<T> executeNode(NodeId nodeId, Inputs inputs) {
    //noinspection unchecked
    return (CompletableFuture<T>) executeNode(nodeId, inputs, requestId);
  }

  @Override
  public <T> CompletableFuture<T> executeNode(NodeId nodeId, Inputs inputs, String executionId) {
    //noinspection unchecked
    if (executionId == null) {
      throw new IllegalArgumentException("Execution id can not be null");
    }
    return (CompletableFuture<T>) executeNode(nodeId, inputs, this.requestId.append(executionId));
  }

  private CompletableFuture<?> executeNode(NodeId nodeId, Inputs inputs, RequestId requestId) {
    if (closed) {
      throw new RejectedExecutionException("KrystalNodeExecutor is already closed");
    }
    return supplyAsync( // Perform all datastructure manipulations in the command queue to avoid
            // multi-thread access
            () -> {
              createDependantNodes(nodeId, DependantChainStart.instance());
              CompletableFuture<Object> future = new CompletableFuture<>();
              NodeResult nodeResult = new NodeResult(nodeId, inputs, future);
              allRequests.computeIfAbsent(requestId, r -> new ArrayList<>()).add(nodeResult);
              unFlushedRequests.computeIfAbsent(requestId, r -> new ArrayList<>()).add(nodeResult);
              return future;
            },
            commandQueueLease.get())
        .thenCompose(identity());
  }

  private void createDependantNodes(NodeId nodeId, DependantChain dependantChain) {
    NodeDefinition nodeDefinition = nodeDefinitionRegistry.get(nodeId);
    if (dependantChain.contains(nodeId)) {
      // There is a cyclic dependency for the given node. So we avoid creating nodes for
      // dependencies nodes to avoid infinite recursion of this method.
      //
      // This means 'dependantChainsPerNode' field of this class will not contain all possible
      // dependantChains (since there will be infinitely many of them). Instead, there will be
      // exactly one dependantChain which will have this node as a dependant, and this
      // dependantChain can be used to infer that there is a dependency recursion.
      //
      // The implication of this is that any LogicDecorators configured for this node or any
      // of its transitive dependencies, where such LogicDecorators  rely on the
      // 'InitiateActiveDepChains' command to initiate all possible active DependantChains might
      // not work as expected (For example, InputModulationDecorator of vajram-krystex library).
      //
      // It is the responsibility of users of the krystex library to make sure that either:
      // 1. Nodes which have LogicDecorators which depend on activeDepChains to be exhaustive,
      // should not have dependant chains containing loops, or ...
      // 2. If the above is not possible, then such LogicDecorators should gracefully handle the
      // scenario that InitiateActiveDepChains will not contain recursive active dependant
      // chains.
      dependantChainsPerNode
          .computeIfAbsent(nodeId, _n -> new LinkedHashSet<>())
          .add(dependantChain);
    } else {
      nodeRegistry.createIfAbsent(
          nodeId,
          _n ->
              new Node(
                  nodeDefinition, this, this::getRequestScopedDecorators, logicDecorationOrdering));
      ImmutableMap<String, NodeId> dependencyNodes = nodeDefinition.dependencyNodes();
      dependencyNodes.forEach(
          (dependencyName, depNodeId) ->
              createDependantNodes(
                  depNodeId, DependantChain.from(nodeId, dependencyName, dependantChain)));
      dependantChainsPerNode
          .computeIfAbsent(nodeId, _n -> new LinkedHashSet<>())
          .add(dependantChain);
    }
  }

  CompletableFuture<NodeResponse> enqueueCommand(NodeRequestCommand nodeCommand) {
    return supplyAsync(
            () -> nodeRegistry.get(nodeCommand.nodeId()).executeRequestCommand(nodeCommand),
            commandQueueLease.get())
        .thenCompose(identity());
  }

  void enqueueCommand(Flush flush) {
    runAsync(() -> nodeRegistry.get(flush.nodeId()).executeCommand(flush), commandQueueLease.get());
  }

  public void flush() {
    runAsync(
        () -> {
          //noinspection CodeBlock2Expr
          unFlushedRequests.forEach(
              (requestId, nodeExecutionInfos) -> {
                nodeExecutionInfos.forEach(
                    nodeResult -> {
                      NodeId nodeId = nodeResult.nodeId();
                      if (nodeResult.future().isDone()) {
                        return;
                      }
                      NodeDefinition nodeDefinition = nodeDefinitionRegistry.get(nodeId);
                      CompletableFuture<Object> submissionResult =
                          enqueueCommand(
                                  new ExecuteWithInputs(
                                      nodeId,
                                      nodeDefinitionRegistry
                                          .logicDefinitionRegistry()
                                          .getMain(nodeDefinition.mainLogicNode())
                                          .inputNames()
                                          .stream()
                                          .filter(
                                              s -> !nodeDefinition.dependencyNodes().containsKey(s))
                                          .collect(toImmutableSet()),
                                      nodeResult.inputs(),
                                      DependantChainStart.instance(),
                                      requestId))
                              .thenApply(NodeResponse::response)
                              .thenApply(
                                  valueOrError -> {
                                    if (valueOrError.error().isPresent()) {
                                      throw new RuntimeException(valueOrError.error().get());
                                    } else {
                                      return valueOrError.value().orElse(null);
                                    }
                                  });
                      linkFutures(submissionResult, nodeResult.future());
                    });
              });
          unFlushedRequests.forEach(
              (requestId, nodeExecutionInfos) ->
                  nodeExecutionInfos.forEach(
                      nodeResult -> enqueueCommand(new Flush(nodeResult.nodeId()))));
          unFlushedRequests.clear();
        },
        commandQueueLease.get());
  }

  /**
   * Prevents accepting new requests. For reasons of performance optimization, submitted requests
   * are executed in this method.
   */
  @Override
  public void close() {
    if (closed) {
      return;
    }
    this.closed = true;
    flush();
    supplyAsync(
        () ->
            allOf(
                    allRequests.values().stream()
                        .flatMap(
                            nodeExecutionInfos ->
                                nodeExecutionInfos.stream().map(NodeResult::future))
                        .toArray(CompletableFuture[]::new))
                .whenComplete((unused, throwable) -> commandQueueLease.close()),
        commandQueueLease.get());
  }

  private record NodeResult(NodeId nodeId, Inputs inputs, CompletableFuture<Object> future) {}
}

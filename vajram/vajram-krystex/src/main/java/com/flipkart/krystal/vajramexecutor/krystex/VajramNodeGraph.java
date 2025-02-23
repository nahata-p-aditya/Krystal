package com.flipkart.krystal.vajramexecutor.krystex;

import static com.flipkart.krystal.vajram.VajramLoader.loadVajramsFromClassPath;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.ImmutableSet.toImmutableSet;

import com.flipkart.krystal.data.InputValue;
import com.flipkart.krystal.data.Inputs;
import com.flipkart.krystal.data.ValueOrError;
import com.flipkart.krystal.krystex.ForkJoinExecutorPool;
import com.flipkart.krystal.krystex.LogicDefinitionRegistry;
import com.flipkart.krystal.krystex.MainLogicDefinition;
import com.flipkart.krystal.krystex.ResolverCommand;
import com.flipkart.krystal.krystex.ResolverDefinition;
import com.flipkart.krystal.krystex.ResolverLogicDefinition;
import com.flipkart.krystal.krystex.decoration.LogicDecorationOrdering;
import com.flipkart.krystal.krystex.decoration.MainLogicDecoratorConfig;
import com.flipkart.krystal.krystex.node.NodeDefinition;
import com.flipkart.krystal.krystex.node.NodeDefinitionRegistry;
import com.flipkart.krystal.krystex.node.NodeId;
import com.flipkart.krystal.krystex.node.NodeLogicId;
import com.flipkart.krystal.utils.MultiLeasePool;
import com.flipkart.krystal.vajram.ApplicationRequestContext;
import com.flipkart.krystal.vajram.IOVajram;
import com.flipkart.krystal.vajram.MandatoryInputsMissingException;
import com.flipkart.krystal.vajram.Vajram;
import com.flipkart.krystal.vajram.VajramDefinitionException;
import com.flipkart.krystal.vajram.VajramID;
import com.flipkart.krystal.vajram.das.AccessSpecMatchingResult;
import com.flipkart.krystal.vajram.das.DataAccessSpec;
import com.flipkart.krystal.vajram.das.VajramIndex;
import com.flipkart.krystal.vajram.exec.VajramDefinition;
import com.flipkart.krystal.vajram.exec.VajramExecutableGraph;
import com.flipkart.krystal.vajram.inputs.Dependency;
import com.flipkart.krystal.vajram.inputs.DependencyCommand;
import com.flipkart.krystal.vajram.inputs.Input;
import com.flipkart.krystal.vajram.inputs.InputResolver;
import com.flipkart.krystal.vajram.inputs.InputResolverDefinition;
import com.flipkart.krystal.vajram.inputs.InputSource;
import com.flipkart.krystal.vajram.inputs.VajramInputDefinition;
import com.flipkart.krystal.vajramexecutor.krystex.InputModulatorConfig.ModulatorContext;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import lombok.Getter;

/** The execution graph encompassing all registered vajrams. */
public final class VajramNodeGraph implements VajramExecutableGraph {

  @Getter private final NodeDefinitionRegistry nodeDefinitionRegistry;

  private final LogicDefRegistryDecorator logicRegistryDecorator;

  private final Map<VajramID, VajramDefinition> vajramDefinitions = new LinkedHashMap<>();
  /** These are those call graphs of a vajram where no other vajram depends on this. */
  private final Map<VajramID, NodeId> vajramExecutables = new LinkedHashMap<>();

  private final VajramIndex vajramIndex = new VajramIndex();

  private final ImmutableMap<VajramID, InputModulatorConfig> inputModulatorConfigs;

  /** LogicDecorator Id -> LogicDecoratorConfig */
  private final ImmutableMap<String, MainLogicDecoratorConfig> sessionScopedDecoratorConfigs;

  private final LogicDecorationOrdering logicDecorationOrdering;
  private MultiLeasePool<? extends ExecutorService> executorPool;

  private VajramNodeGraph(
      String[] packagePrefixes,
      ImmutableMap<VajramID, InputModulatorConfig> inputModulatorConfigs,
      ImmutableMap<String, MainLogicDecoratorConfig> sessionScopedDecorators,
      LogicDecorationOrdering logicDecorationOrdering,
      double maxParallelismPerCore) {
    this.inputModulatorConfigs = inputModulatorConfigs;
    this.sessionScopedDecoratorConfigs = sessionScopedDecorators;
    this.logicDecorationOrdering = logicDecorationOrdering;
    this.executorPool = new ForkJoinExecutorPool(maxParallelismPerCore);
    LogicDefinitionRegistry logicDefinitionRegistry = new LogicDefinitionRegistry();
    this.nodeDefinitionRegistry = new NodeDefinitionRegistry(logicDefinitionRegistry);
    this.logicRegistryDecorator = new LogicDefRegistryDecorator(logicDefinitionRegistry);
    for (String packagePrefix : packagePrefixes) {
      loadVajramsFromClassPath(packagePrefix).forEach(this::registerVajram);
    }
  }

  public MultiLeasePool<? extends ExecutorService> getExecutorPool() {
    return executorPool;
  }

  @Override
  public <C extends ApplicationRequestContext> KrystexVajramExecutor<C> createExecutor(
      C requestContext) {
    return new KrystexVajramExecutor<>(this, logicDecorationOrdering, executorPool, requestContext);
  }

  @Override
  public void close() {
    executorPool.close();
  }

  /**
   * Registers vajrams that need to be executed at a later point. This is a necessary step for
   * vajram execution.
   *
   * @param vajram The vajram to be registered for future execution.
   */
  private void registerVajram(Vajram vajram) {
    if (vajramDefinitions.containsKey(vajram.getId())) {
      return;
    }
    vajramDefinitions.put(vajram.getId(), new VajramDefinition(vajram));
    vajramIndex.add(vajram);
  }

  /**
   * If necessary, creates the nodes for the given vajram and, recursively for its dependencies, and
   * returns the {@link NodeId} of the {@link NodeDefinition} corresponding to this vajram.
   *
   * <p>This method should be called once all necessary vajrams have been registered using the
   * {@link #registerVajram(Vajram)} method. If a dependency of a vajram is not registered before
   * this step, this method will throw an exception.
   *
   * @param vajramId The id of the vajram to execute.
   * @return {@link NodeId} of the {@link NodeDefinition} corresponding to this given vajramId
   */
  NodeId getNodeId(VajramID vajramId) {
    return _getVajramExecutionGraph(vajramId);
  }

  private NodeId _getVajramExecutionGraph(VajramID vajramId) {
    NodeId nodeId = vajramExecutables.get(vajramId);
    if (nodeId != null) {
      return nodeId;
    }
    nodeId = new NodeId(vajramId.vajramId());
    vajramExecutables.put(vajramId, nodeId);

    VajramDefinition vajramDefinition =
        getVajramDefinition(vajramId)
            .orElseThrow(
                () ->
                    new NoSuchElementException(
                        "Could not find vajram with id: %s".formatted(vajramId)));

    InputResolverCreationResult inputResolverCreationResult =
        createNodeLogicsForInputResolvers(vajramDefinition);

    ImmutableMap<String, NodeId> depNameToProviderNode =
        createNodeDefinitionsForDependencies(vajramDefinition);

    MainLogicDefinition<?> vajramLogicMainLogicDefinition = createVajramNodeLogic(vajramDefinition);

    NodeDefinition nodeDefinition =
        nodeDefinitionRegistry.newNodeDefinition(
            nodeId.value(),
            vajramLogicMainLogicDefinition.nodeLogicId(),
            depNameToProviderNode,
            inputResolverCreationResult.resolverDefinitions());
    return nodeDefinition.nodeId();
  }

  private InputResolverCreationResult createNodeLogicsForInputResolvers(
      VajramDefinition vajramDefinition) {
    Vajram<?> vajram = vajramDefinition.getVajram();
    VajramID vajramId = vajram.getId();

    // Create node definitions for all input resolvers defined in this vajram
    List<InputResolverDefinition> inputResolvers =
        new ArrayList<>(vajramDefinition.getInputResolverDefinitions());

    ImmutableList<ResolverDefinition> resolverDefinitions =
        inputResolvers.stream()
            .map(
                inputResolverDefinition -> {
                  String dependencyName =
                      inputResolverDefinition.resolutionTarget().dependencyName();
                  ImmutableSet<String> resolvedInputNames =
                      inputResolverDefinition.resolutionTarget().inputNames();
                  ImmutableSet<String> sources = inputResolverDefinition.sources();
                  ImmutableCollection<VajramInputDefinition> requiredInputs =
                      vajram.getInputDefinitions().stream()
                          .filter(i -> sources.contains(i.name()))
                          .collect(toImmutableList());
                  ResolverLogicDefinition inputResolverNode =
                      logicRegistryDecorator.newResolverLogic(
                          "%s:dep(%s):inputResolver(%s)"
                              .formatted(
                                  vajramId, dependencyName, String.join(",", resolvedInputNames)),
                          sources,
                          inputValues -> {
                            validateMandatory(vajramId, inputValues, requiredInputs);
                            DependencyCommand<Inputs> dependencyCommand;
                            if (inputResolverDefinition instanceof InputResolver inputResolver) {
                              dependencyCommand =
                                  inputResolver.resolve(
                                      dependencyName, resolvedInputNames, inputValues);
                            } else {
                              dependencyCommand =
                                  vajram.resolveInputOfDependency(
                                      dependencyName, resolvedInputNames, inputValues);
                            }
                            if (dependencyCommand
                                instanceof DependencyCommand.Skip<Inputs> skipCommand) {
                              return ResolverCommand.skip(skipCommand.reason());
                            }
                            return ResolverCommand.multiExecuteWith(
                                dependencyCommand.inputs().stream()
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .collect(toImmutableList()));
                          });
                  return new ResolverDefinition(
                      inputResolverNode.nodeLogicId(), sources, dependencyName, resolvedInputNames);
                })
            .collect(toImmutableList());
    return new InputResolverCreationResult(resolverDefinitions);
  }

  private void validateMandatory(
      VajramID vajramID, Inputs inputs, ImmutableCollection<VajramInputDefinition> requiredInputs) {
    Iterable<VajramInputDefinition> mandatoryInputs =
        requiredInputs.stream()
                .filter(inputDefinition -> inputDefinition instanceof Input<?>)
                .filter(VajramInputDefinition::isMandatory)
            ::iterator;
    Map<String, Throwable> missingMandatoryValues = new HashMap<>();
    for (VajramInputDefinition mandatoryInput : mandatoryInputs) {
      ValueOrError<?> value = inputs.getInputValue(mandatoryInput.name());
      if (value.error().isPresent() || value.value().isEmpty()) {
        missingMandatoryValues.put(
            mandatoryInput.name(),
            value
                .error()
                .orElse(
                    new NoSuchElementException(
                        "No value present for input %s".formatted(mandatoryInput.name()))));
      }
    }
    if (missingMandatoryValues.isEmpty()) {
      return;
    }
    throw new MandatoryInputsMissingException(vajramID, missingMandatoryValues);
  }

  private MainLogicDefinition<?> createVajramNodeLogic(VajramDefinition vajramDefinition) {
    VajramID vajramId = vajramDefinition.getVajram().getId();
    ImmutableCollection<VajramInputDefinition> inputDefinitions =
        vajramDefinition.getVajram().getInputDefinitions();
    ImmutableSet<String> inputNames =
        inputDefinitions.stream().map(VajramInputDefinition::name).collect(toImmutableSet());
    NodeLogicId vajramLogicNodeName = new NodeLogicId("%s:vajramLogic".formatted(vajramId));
    MainLogicDefinition<?> vajramLogic;
    // Step 4: Create and register node for the main vajram logic
    vajramLogic =
        logicRegistryDecorator.newMainLogic(
            vajramDefinition.getVajram() instanceof IOVajram<?>,
            vajramLogicNodeName,
            inputNames,
            inputsList -> {
              inputsList.forEach(
                  inputs ->
                      validateMandatory(
                          vajramId, inputs, vajramDefinition.getVajram().getInputDefinitions()));
              ImmutableList<Inputs> inputValues =
                  inputsList.stream()
                      .map(inputs -> injectFromSession(inputDefinitions, inputs))
                      .collect(toImmutableList());
              return vajramDefinition.getVajram().execute(inputValues);
            },
            vajramDefinition.getMainLogicTags());
    enableInputModulation(vajramLogic, vajramDefinition.getVajram());
    sessionScopedDecoratorConfigs
        .values()
        .forEach(vajramLogic::registerSessionScopedLogicDecorator);
    return vajramLogic;
  }

  private <T> void enableInputModulation(
      MainLogicDefinition<T> logicDefinition, Vajram<?> ioVajram) {
    InputModulatorConfig inputModulatorConfig = inputModulatorConfigs.get(ioVajram.getId());
    if (inputModulatorConfig != null) {
      logicDefinition.registerRequestScopedDecorator(
          new MainLogicDecoratorConfig(
              InputModulationDecorator.DECORATOR_TYPE,
              nodeExecutionContext ->
                  ioVajram.getInputDefinitions().stream()
                      .filter(inputDefinition -> inputDefinition instanceof Input<?>)
                      .map(inputDefinition -> (Input<?>) inputDefinition)
                      .anyMatch(Input::needsModulation),
              inputModulatorConfig.instanceIdGenerator(),
              decoratorContext ->
                  inputModulatorConfig
                      .decoratorFactory()
                      .apply(new ModulatorContext(ioVajram, decoratorContext))));
    }
  }

  private static Inputs injectFromSession(
      ImmutableCollection<VajramInputDefinition> inputDefinitions, Inputs inputs) {
    Map<String, InputValue<Object>> newValues = new HashMap<>();
    for (VajramInputDefinition inputDefinition : inputDefinitions) {
      String inputName = inputDefinition.name();
      if (inputDefinition instanceof Input<?> input) {
        if (input.sources().contains(InputSource.CLIENT)) {
          ValueOrError<Object> value = inputs.getInputValue(inputName);
          if (!ValueOrError.empty().equals(value)) {
            continue;
          }
          // Input was not resolved by another vajram. Check if it is resolvable
          // by SESSION
        }
        if (input.sources().contains(InputSource.SESSION)) {
          // TODO handle session provided inputs
        }
      }
    }
    if (!newValues.isEmpty()) {
      inputs.values().forEach(newValues::putIfAbsent);
      return new Inputs(newValues);
    } else {
      return inputs;
    }
  }

  private ImmutableMap<String, NodeId> createNodeDefinitionsForDependencies(
      VajramDefinition vajramDefinition) {
    List<Dependency> dependencies = new ArrayList<>();
    for (VajramInputDefinition vajramInputDefinition :
        vajramDefinition.getVajram().getInputDefinitions()) {
      if (vajramInputDefinition instanceof Dependency definition) {
        dependencies.add(definition);
      }
    }
    Map<String, NodeId> depNameToProviderNode = new HashMap<>();
    // Create and register sub graphs for dependencies of this vajram
    for (Dependency dependency : dependencies) {
      var accessSpec = dependency.dataAccessSpec();
      String dependencyName = dependency.name();
      AccessSpecMatchingResult<DataAccessSpec> accessSpecMatchingResult =
          vajramIndex.getVajrams(accessSpec);
      if (accessSpecMatchingResult.hasUnsuccessfulMatches()) {
        throw new VajramDefinitionException(
            "Unable to find vajrams for accessSpecs %s"
                .formatted(accessSpecMatchingResult.unsuccessfulMatches()));
      }
      ImmutableMap<DataAccessSpec, Vajram> dependencyVajrams =
          accessSpecMatchingResult.successfulMatches();
      if (dependencyVajrams.size() > 1) {
        throw new UnsupportedOperationException();
      }
      Vajram dependencyVajram = dependencyVajrams.values().iterator().next();

      depNameToProviderNode.put(dependencyName, _getVajramExecutionGraph(dependencyVajram.getId()));
    }
    return ImmutableMap.copyOf(depNameToProviderNode);
  }

  private record InputResolverCreationResult(
      ImmutableList<ResolverDefinition> resolverDefinitions) {}

  private Optional<VajramDefinition> getVajramDefinition(VajramID vajramId) {
    return Optional.ofNullable(vajramDefinitions.get(vajramId));
  }

  public static Builder builder() {
    return new Builder();
  }

  public static final class Builder {
    private final LinkedHashSet<String> packagePrefixes = new LinkedHashSet<>();
    private final Map<String, MainLogicDecoratorConfig> sessionScopedDecoratorConfigs =
        new HashMap<>();
    private final Map<VajramID, InputModulatorConfig> inputModulators = new LinkedHashMap<>();
    private LogicDecorationOrdering logicDecorationOrdering =
        new LogicDecorationOrdering(ImmutableSet.of());
    private double maxParallelismPerCore = 1;

    public Builder loadFromPackage(String packagePrefix) {
      packagePrefixes.add(packagePrefix);
      return this;
    }

    public Builder decorateVajramLogicForSession(MainLogicDecoratorConfig logicDecoratorConfig) {
      if (sessionScopedDecoratorConfigs.putIfAbsent(
              logicDecoratorConfig.decoratorType(), logicDecoratorConfig)
          != null) {
        throw new IllegalArgumentException(
            "Cannot have two decorator configs for same decorator type : %s"
                .formatted(logicDecoratorConfig.decoratorType()));
      }
      return this;
    }

    public Builder maxParallelismPerCore(double maxParallelismPerCore) {
      this.maxParallelismPerCore = maxParallelismPerCore;
      return this;
    }

    public Builder registerInputModulator(VajramID vajramID, InputModulatorConfig inputModulator) {
      inputModulators.put(vajramID, inputModulator);
      return this;
    }

    public Builder logicDecorationOrdering(LogicDecorationOrdering logicDecorationOrdering) {
      this.logicDecorationOrdering = logicDecorationOrdering;
      return this;
    }

    public VajramNodeGraph build() {
      return new VajramNodeGraph(
          packagePrefixes.toArray(String[]::new),
          ImmutableMap.copyOf(inputModulators),
          ImmutableMap.copyOf(sessionScopedDecoratorConfigs),
          logicDecorationOrdering,
          maxParallelismPerCore);
    }
  }
}

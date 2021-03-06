package izumi.distage.bootstrap

import izumi.distage.AbstractLocator
import izumi.distage.bootstrap.CglibBootstrap.CglibProxyProvider
import izumi.distage.model.Locator.LocatorMeta
import izumi.distage.model._
import izumi.distage.model.definition._
import izumi.distage.model.exceptions.{MissingInstanceException, SanityCheckFailedException}
import izumi.distage.model.plan.ExecutableOp.InstantiationOp
import izumi.distage.model.plan._
import izumi.distage.model.planning._
import izumi.distage.model.provisioning.PlanInterpreter.FinalizerFilter
import izumi.distage.model.provisioning.proxies.ProxyProvider
import izumi.distage.model.provisioning.proxies.ProxyProvider.ProxyProviderFailingImpl
import izumi.distage.model.provisioning.strategies._
import izumi.distage.model.provisioning.{PlanInterpreter, ProvisioningFailureInterceptor}
import izumi.distage.model.references.IdentifiedRef
import izumi.distage.model.reflection.{DIKey, MirrorProvider}
import izumi.distage.planning._
import izumi.distage.planning.sequential.{ForwardingRefResolverDefaultImpl, SanityCheckerDefaultImpl}
import izumi.distage.planning.solver.SemigraphSolver.SemigraphSolverImpl
import izumi.distage.planning.solver.{GraphPreparations, PlanSolver, SemigraphSolver}
import izumi.distage.provisioning._
import izumi.distage.provisioning.strategies._
import izumi.fundamentals.platform.console.TrivialLogger
import izumi.fundamentals.platform.functional.Identity
import izumi.reflect.TagK

import scala.collection.immutable

final class BootstrapLocator(bindings0: BootstrapContextModule, bootstrapActivation: Activation) extends AbstractLocator {
  override val parent: Option[AbstractLocator] = None
  override val plan: OrderedPlan = {
    // BootstrapModule & bootstrap plugins cannot modify `Activation` after 0.11.0,
    // it's solely under control of `PlannerInput` now.
    // Please open an issue if you need the ability to override Activation using BootstrapModule
    val bindings = bindings0 ++ new BootstrapModuleDef {
      make[Activation].fromValue(bootstrapActivation)
      make[BootstrapModule].fromValue(bindings0)
    }

    BootstrapLocator
      .bootstrapPlanner
      .plan(PlannerInput.noGC(bindings, bootstrapActivation))
  }

  override lazy val index: Map[DIKey, Any] = instances.map(i => i.key -> i.value).toMap

  private[this] val bootstrappedContext: Locator = {
    val resource = BootstrapLocator.bootstrapProducer.instantiate[Identity](plan, this, FinalizerFilter.all)
    resource.unsafeGet().throwOnFailure()
  }

  private[this] val _instances: immutable.Seq[IdentifiedRef] = bootstrappedContext.instances

  override def finalizers[F[_]: TagK]: collection.Seq[PlanInterpreter.Finalizer[F]] = Nil

  override def meta: LocatorMeta = LocatorMeta.empty

  override def instances: immutable.Seq[IdentifiedRef] = {
    val instances = _instances
    if (instances ne null) {
      instances
    } else {
      throw new SanityCheckFailedException(s"Injector bootstrap tried to enumerate instances from root locator, something is terribly wrong")
    }
  }

  override protected def lookupLocalUnsafe(key: DIKey): Option[Any] = {
    val instances = _instances
    if (instances ne null) {
      index.get(key)
    } else {
      throw new MissingInstanceException(s"Injector bootstrap tried to perform a lookup from root locator, bootstrap plan in incomplete! Missing key: $key", key)
    }
  }

}

object BootstrapLocator {
  private[this] final val mirrorProvider = MirrorProvider.Impl
  private[this] final val fullStackTraces = izumi.distage.DebugProperties.`izumi.distage.interpreter.full-stacktraces`.boolValue(true)
  private[this] final val initProxiesAsap = izumi.distage.DebugProperties.`izumi.distage.init-proxies-asap`.boolValue(true)

  private final val bootstrapPlanner: Planner = {
    val analyzer = new PlanAnalyzerDefaultImpl

    val bootstrapObserver = new PlanningObserverAggregate(
      Set(
        new BootstrapPlanningObserver(TrivialLogger.make[BootstrapLocator](izumi.distage.DebugProperties.`izumi.distage.debug.bootstrap`.name))
        //new GraphObserver(analyzer, Set.empty),
      )
    )

    val hook = new PlanningHookAggregate(Set.empty)
    val forwardingRefResolver = new ForwardingRefResolverDefaultImpl(analyzer, true)
    val sanityChecker = new SanityCheckerDefaultImpl(analyzer)
    val mp = mirrorProvider
    val resolver = new PlanSolver.Impl(
      new SemigraphSolverImpl[DIKey, Int, InstantiationOp](),
      new GraphPreparations(new BindingTranslator.Impl()),
    )

    new PlannerDefaultImpl(
      forwardingRefResolver = forwardingRefResolver,
      sanityChecker = sanityChecker,
      planningObserver = bootstrapObserver,
      hook = hook,
      analyzer = analyzer,
      mirrorProvider = mp,
      resolver = resolver,
    )
  }

  private final val bootstrapProducer: PlanInterpreter = {
    val verifier = new ProvisionOperationVerifier.Default(mirrorProvider)
    new PlanInterpreterDefaultRuntimeImpl(
      setStrategy = new SetStrategyDefaultImpl,
      proxyStrategy = new ProxyStrategyFailingImpl,
      providerStrategy = new ProviderStrategyDefaultImpl,
      importStrategy = new ImportStrategyDefaultImpl,
      instanceStrategy = new InstanceStrategyDefaultImpl,
      effectStrategy = new EffectStrategyDefaultImpl,
      resourceStrategy = new ResourceStrategyDefaultImpl,
      failureHandler = new ProvisioningFailureInterceptor.DefaultImpl,
      verifier = verifier,
      fullStackTraces = fullStackTraces,
    )
  }

  final val defaultBootstrap: BootstrapContextModule = new BootstrapContextModuleDef {
    make[Boolean].named("distage.init-proxies-asap").fromValue(initProxiesAsap)
    make[Boolean].named("izumi.distage.interpreter.full-stacktraces").fromValue(fullStackTraces)

    make[ProvisionOperationVerifier].from[ProvisionOperationVerifier.Default]

    make[MirrorProvider].fromValue(mirrorProvider)

    make[PlanAnalyzer].from[PlanAnalyzerDefaultImpl]

    make[PlanSolver].from[PlanSolver.Impl]
    make[GraphPreparations]

    make[SemigraphSolver[DIKey, Int, InstantiationOp]].from[SemigraphSolverImpl[DIKey, Int, InstantiationOp]]

    make[ForwardingRefResolver].from[ForwardingRefResolverDefaultImpl]
    make[SanityChecker].from[SanityCheckerDefaultImpl]
    make[Planner].from[PlannerDefaultImpl]
    make[SetStrategy].from[SetStrategyDefaultImpl]
    make[ProviderStrategy].from[ProviderStrategyDefaultImpl]
    make[ImportStrategy].from[ImportStrategyDefaultImpl]
    make[InstanceStrategy].from[InstanceStrategyDefaultImpl]
    make[EffectStrategy].from[EffectStrategyDefaultImpl]
    make[ResourceStrategy].from[ResourceStrategyDefaultImpl]
    make[PlanInterpreter].from[PlanInterpreterDefaultRuntimeImpl]
    make[ProvisioningFailureInterceptor].from[ProvisioningFailureInterceptor.DefaultImpl]

    many[PlanningObserver]
    many[PlanningHook]

    make[PlanningObserver].from[PlanningObserverAggregate]
    make[PlanningHook].from[PlanningHookAggregate]

    make[BindingTranslator].from[BindingTranslator.Impl]

    make[ProxyProvider].tagged(Cycles.Proxy).from[CglibProxyProvider]
    make[ProxyProvider].from[ProxyProviderFailingImpl]

    make[ProxyStrategy].tagged(Cycles.Disable).from[ProxyStrategyFailingImpl]
    make[ProxyStrategy].from[ProxyStrategyDefaultImpl]
  }

  final val defaultBootstrapActivation: Activation = Activation(Cycles -> Cycles.Proxy)
}

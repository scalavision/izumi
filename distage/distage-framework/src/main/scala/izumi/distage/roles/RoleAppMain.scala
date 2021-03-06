package izumi.distage.roles

import distage.Injector
import cats.effect.LiftIO
import izumi.distage.modules.{DefaultModule, DefaultModule2}
import izumi.distage.model.definition.Module
import izumi.distage.plugins.PluginConfig
import izumi.distage.roles.RoleAppMain.{AdditionalRoles, ArgV}
import izumi.distage.roles.launcher.AppResourceProvider.AppResource
import izumi.distage.roles.launcher.AppShutdownStrategy._
import izumi.distage.roles.launcher.{AppFailureHandler, AppShutdownStrategy}
import izumi.functional.bio.Async2
import izumi.fundamentals.platform.cli.model.raw.RawRoleParams
import izumi.fundamentals.platform.cli.model.schema.ParserDef
import izumi.fundamentals.platform.functional.Identity
import izumi.fundamentals.platform.language.unused
import izumi.fundamentals.platform.resources.IzArtifactMaterializer
import izumi.reflect.{TagK, TagKK}

import scala.concurrent.ExecutionContext

trait PlanHolder {
  // FIXME: remove if unnecessary
  type AppEffectType[_]
  implicit def tagK: TagK[AppEffectType]
  def finalAppModule(argv: ArgV): Module
}

abstract class RoleAppMain[F[_]](
  implicit
  val tagK: TagK[F],
  val defaultModule: DefaultModule[F],
  val artifact: IzArtifactMaterializer,
) extends PlanHolder {
  protected def pluginConfig: PluginConfig
  protected def bootstrapPluginConfig: PluginConfig = PluginConfig.empty
  protected def shutdownStrategy: AppShutdownStrategy[F]

  override final type AppEffectType[A] = F[A]

  def main(args: Array[String]): Unit = {
    val argv = ArgV(args)
    try {
      Injector.NoProxies[Identity]().produceRun(finalAppModule(argv)) {
        appResource: AppResource[F] =>
          appResource.runApp()
      }
    } catch {
      case t: Throwable =>
        createEarlyFailureHandler(argv).onError(t)
    }
  }

  def finalAppModule(argv: ArgV): Module = {
    val appModule = makeAppModule(argv, AdditionalRoles(requiredRoles(argv)))
    val overrideModule = makeAppModuleOverride(argv)
    appModule overriddenBy overrideModule
  }

  protected def requiredRoles(@unused argv: ArgV): Vector[RawRoleParams] = {
    Vector.empty
  }

  protected def makeAppModuleOverride(@unused argv: ArgV): Module = {
    Module.empty
  }

  protected def makeAppModule(argv: ArgV, additionalRoles: AdditionalRoles): Module = {
    new MainAppModule[F](
      args = argv,
      additionalRoles = additionalRoles,
      shutdownStrategy = shutdownStrategy,
      pluginConfig = pluginConfig,
      bootstrapPluginConfig = bootstrapPluginConfig,
      appArtifact = artifact.get,
    )
  }

  protected def createEarlyFailureHandler(@unused args: ArgV): AppFailureHandler = {
    AppFailureHandler.TerminatingHandler
  }
}

object RoleAppMain {

  abstract class LauncherBIO[F[+_, +_]: TagKK: Async2: DefaultModule2](implicit artifact: IzArtifactMaterializer) extends RoleAppMain[F[Throwable, ?]] {
    override protected def shutdownStrategy: AppShutdownStrategy[F[Throwable, ?]] = new BIOShutdownStrategy[F]
  }

  abstract class LauncherCats[F[_]: TagK: LiftIO: DefaultModule](
    shutdownExecutionContext: ExecutionContext = ExecutionContext.global
  )(implicit artifact: IzArtifactMaterializer
  ) extends RoleAppMain[F] {
    override protected def shutdownStrategy: AppShutdownStrategy[F] = new CatsEffectIOShutdownStrategy(shutdownExecutionContext)
  }

  abstract class LauncherIdentity(implicit artifact: IzArtifactMaterializer) extends RoleAppMain[Identity] {
    override protected def shutdownStrategy: AppShutdownStrategy[Identity] = new JvmExitHookLatchShutdownStrategy
  }

  final case class ArgV(args: Array[String])
  final case class AdditionalRoles(knownRequiredRoles: Vector[RawRoleParams])

  object Options extends ParserDef {
    final val logLevelRootParam = arg("log-level-root", "ll", "root log level", "{trace|debug|info|warn|error|critical}")
    final val logFormatParam = arg("log-format", "lf", "log format", "{hocon|json}")
    final val configParam = arg("config", "c", "path to config file", "<path>")
    final val dumpContext = flag("debug-dump-graph", "dump DI graph for debugging")
    final val use = arg("use", "u", "activate a choice on functionality axis", "<axis>:<choice>")
  }
}

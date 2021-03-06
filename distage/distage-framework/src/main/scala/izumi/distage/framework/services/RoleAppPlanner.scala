package izumi.distage.framework.services

import distage.Injector
import izumi.distage.framework.config.PlanningOptions
import izumi.distage.framework.model.IntegrationCheck
import izumi.distage.framework.services.RoleAppPlanner.AppStartupPlans
import izumi.distage.model.definition.{Activation, BootstrapModule, Id, ModuleDef}
import izumi.distage.model.effect.{QuasiAsync, QuasiIO, QuasiIORunner}
import izumi.distage.model.plan.{OrderedPlan, Roots, TriSplittedPlan}
import izumi.distage.model.recursive.{BootConfig, Bootloader}
import izumi.distage.model.reflection.DIKey
import izumi.distage.modules.DefaultModule
import izumi.fundamentals.platform.functional.Identity
import izumi.logstage.api.IzLogger
import izumi.reflect.TagK

trait RoleAppPlanner {
  def reboot(bsModule: BootstrapModule): RoleAppPlanner
  def makePlan(appMainRoots: Set[DIKey] /*, appModule: ModuleBase*/ ): AppStartupPlans
}

object RoleAppPlanner {

  final case class AppStartupPlans(
    runtime: OrderedPlan,
    app: TriSplittedPlan,
    injector: Injector[Identity],
  )

  class Impl[F[_]: TagK](
    options: PlanningOptions,
    activation: Activation @Id("roleapp"),
    bsModule: BootstrapModule @Id("roleapp"),
    bootloader: Bootloader @Id("roleapp"),
    logger: IzLogger,
  )(implicit
    defaultModule: DefaultModule[F]
  ) extends RoleAppPlanner { self =>

    private[this] val runtimeGcRoots: Set[DIKey] = Set(
      DIKey.get[QuasiIORunner[F]],
      DIKey.get[QuasiIO[F]],
      DIKey.get[QuasiAsync[F]],
    )

    override def reboot(bsOverride: BootstrapModule): RoleAppPlanner = {
      new RoleAppPlanner.Impl[F](options, activation, bsModule overriddenBy bsOverride, bootloader, logger)
    }

    override def makePlan(appMainRoots: Set[DIKey]): AppStartupPlans = {
      val selfReflectionModule = new ModuleDef {
        make[RoleAppPlanner].fromValue(self)
        make[PlanningOptions].fromValue(options)
      }
      val bootstrappedApp = bootloader.boot(
        BootConfig(
          bootstrap = _ => bsModule,
          appModule = _ overriddenBy selfReflectionModule,
          activation = _ => activation,
          roots = _ => Roots(runtimeGcRoots),
        )
      )
      val runtimeKeys = bootstrappedApp.plan.keys

      val appPlan = bootstrappedApp.injector.trisectByKeys(activation, bootstrappedApp.module.drop(runtimeKeys), appMainRoots) {
        _.collectChildrenKeysSplit[IntegrationCheck[Identity], IntegrationCheck[F]]
      }

      val check = new PlanCircularDependencyCheck(options, logger)
      check.verify(bootstrappedApp.plan)
      check.verify(appPlan.shared)
      check.verify(appPlan.side)
      check.verify(appPlan.primary)

      val out = AppStartupPlans(bootstrappedApp.plan, appPlan, bootstrappedApp.injector)
      logger.info(
        s"Planning finished. ${out.app.primary.keys.size -> "main ops"}, ${out.app.side.keys.size -> "integration ops"}, ${out
          .app.shared.keys.size -> "shared ops"}, ${out.runtime.keys.size -> "runtime ops"}"
      )
      out
    }

  }

}

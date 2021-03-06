package izumi.distage.model

import izumi.distage.model.definition.Lifecycle
import izumi.distage.model.effect.QuasiIO
import izumi.distage.model.plan.OrderedPlan
import izumi.distage.model.provisioning.PlanInterpreter.{FailedProvision, FinalizerFilter}
import izumi.fundamentals.platform.functional.Identity
import izumi.reflect.TagK

/** Executes instructions in [[OrderedPlan]] to produce a [[Locator]] */
trait Producer {
  private[distage] def produceDetailedFX[F[_]: TagK: QuasiIO](plan: OrderedPlan, filter: FinalizerFilter[F]): Lifecycle[F, Either[FailedProvision[F], Locator]]
  private[distage] final def produceFX[F[_]: TagK: QuasiIO](plan: OrderedPlan, filter: FinalizerFilter[F]): Lifecycle[F, Locator] = {
    produceDetailedFX[F](plan, filter).evalMap(_.throwOnFailure())
  }

  final def produceCustomF[F[_]: TagK: QuasiIO](plan: OrderedPlan): Lifecycle[F, Locator] = {
    produceFX[F](plan, FinalizerFilter.all[F])
  }
  final def produceDetailedCustomF[F[_]: TagK: QuasiIO](plan: OrderedPlan): Lifecycle[F, Either[FailedProvision[F], Locator]] = {
    produceDetailedFX[F](plan, FinalizerFilter.all[F])
  }

  final def produceCustomIdentity(plan: OrderedPlan): Lifecycle[Identity, Locator] =
    produceCustomF[Identity](plan)
  final def produceDetailedIdentity(plan: OrderedPlan): Lifecycle[Identity, Either[FailedProvision[Identity], Locator]] =
    produceDetailedCustomF[Identity](plan)
}

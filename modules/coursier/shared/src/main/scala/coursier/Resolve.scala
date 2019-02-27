package coursier

import coursier.cache.{Cache, CacheLogger}
import coursier.error.ResolutionError
import coursier.error.conflict.UnsatisfiedRule
import coursier.extra.Typelevel
import coursier.params.ResolutionParams
import coursier.params.rule.{Rule, RuleResolution}
import coursier.util._

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.language.higherKinds

final class Resolve[F[_]] private[coursier] (private val params: Resolve.Params[F]) {

  override def equals(obj: Any): Boolean =
    obj match {
      case other: Resolve[_] =>
        params == other.params
    }

  override def hashCode(): Int =
    17 + params.##

  override def toString: String =
    s"Resolve($params)"

  private def withParams(params: Resolve.Params[F]): Resolve[F] =
    new Resolve(params)


  def withDependencies(dependencies: Seq[Dependency]): Resolve[F] =
    withParams(params.copy(dependencies = dependencies))
  def addDependencies(dependencies: Dependency*): Resolve[F] =
    withParams(params.copy(dependencies = params.dependencies ++ dependencies))

  def withRepositories(repositories: Seq[Repository]): Resolve[F] =
    withParams(params.copy(repositories = repositories))
  def addRepositories(repositories: Repository*): Resolve[F] =
    withParams(params.copy(repositories = params.repositories ++ repositories))

  def withResolutionParams(resolutionParams: ResolutionParams): Resolve[F] =
    withParams(params.copy(resolutionParams = resolutionParams))

  def withCache(cache: Cache[F]): Resolve[F] =
    withParams(params.copy(cache = cache))

  def transformResolution(f: F[Resolution] => F[Resolution]): Resolve[F] =
    withParams(params.copy(through = f))
  def transformFetcher(f: ResolutionProcess.Fetch[F] => ResolutionProcess.Fetch[F]): Resolve[F] =
    withParams(params.copy(transformFetcher = f))


  private def S = params.S

  private def fetchVia: ResolutionProcess.Fetch[F] = {
    val fetchs = params.cache.fetchs
    ResolutionProcess.fetch(params.repositories, fetchs.head, fetchs.tail: _*)(S)
  }

  def ioWithConflicts: F[(Resolution, Seq[UnsatisfiedRule])] = {

    val initialRes = Resolve.initialResolution(params.dependencies, params.resolutionParams)
    val fetch = params.transformFetcher(fetchVia)

    def run(res: Resolution): F[Resolution] = {
      val t = Resolve.runProcess(res, fetch, params.resolutionParams.maxIterations, params.cache.loggerOpt)(S)
      params.through(t)
    }

    def validate0(res: Resolution): F[Resolution] =
      Resolve.validate(res).either match {
        case Left(errors) =>
          val err = ResolutionError.from(errors.head, errors.tail: _*)
          S.fromAttempt(Left(err))
        case Right(()) =>
          S.point(res)
      }

    def recurseOnRules(res: Resolution, rules: Seq[(Rule, RuleResolution)]): F[(Resolution, List[UnsatisfiedRule])] =
      rules match {
        case Seq() =>
          S.point((res, Nil))
        case Seq((rule, ruleRes), t @ _*) =>
          rule.enforce(res, ruleRes) match {
            case Left(c) =>
              S.fromAttempt(Left(c))
            case Right(Left(c)) =>
              S.map(recurseOnRules(res, t)) {
                case (res0, conflicts) =>
                  (res0, c :: conflicts)
              }
            case Right(Right(None)) =>
              recurseOnRules(res, t)
            case Right(Right(Some(newRes))) =>
              S.bind(S.bind(run(newRes.copy(dependencies = Set.empty)))(validate0)) { res0 =>
                // FIXME check that the rule passes after it tried to address itself
                recurseOnRules(res0, t)
              }
          }
      }

    def validateAllRules(res: Resolution, rules: Seq[(Rule, RuleResolution)]): F[Resolution] =
      rules match {
        case Seq() =>
          S.point(res)
        case Seq((rule, _), t @ _*) =>
          rule.check(res) match {
            case Some(c) =>
              S.fromAttempt(Left(c))
            case None =>
              validateAllRules(res, t)
          }
      }

    S.bind(S.bind(run(initialRes))(validate0)) { res0 =>
      S.bind(recurseOnRules(res0, params.resolutionParams.rules)) {
        case (res0, conflicts) =>
          S.map(validateAllRules(res0, params.resolutionParams.rules)) { _ =>
            (res0, conflicts)
          }
      }
    }
  }

  def io: F[Resolution] =
    S.map(ioWithConflicts)(_._1)

}

object Resolve extends PlatformResolve {

  // Ideally, cache shouldn't be passed here, and a default one should be created from S.
  // But that would require changes in Sync or an extra typeclass (similar to Async in cats-effect)
  // to allow to use the default cache on Scala.JS with a generic F.
  def apply[F[_]](cache: Cache[F] = Cache.default)(implicit S: Sync[F]): Resolve[F] =
    new Resolve(
      Params(
        Nil,
        defaultRepositories,
        ResolutionParams(),
        cache,
        identity,
        identity,
        S
      )
    )

  implicit class ResolveTaskOps(private val resolve: Resolve[Task]) extends AnyVal {

    def future()(implicit ec: ExecutionContext = resolve.params.cache.ec): Future[Resolution] =
      resolve.io.future()

    def either()(implicit ec: ExecutionContext = resolve.params.cache.ec): Either[ResolutionError, Resolution] = {

      val f = resolve
        .io
        .map(Right(_))
        .handle { case ex: ResolutionError => Left(ex) }
        .future()

      Await.result(f, Duration.Inf)
    }

    def run()(implicit ec: ExecutionContext = resolve.params.cache.ec): Resolution = {
      val f = future()(ec)
      Await.result(f, Duration.Inf)
    }

  }

  private[coursier] final case class Params[F[_]](
    dependencies: Seq[Dependency],
    repositories: Seq[Repository],
    resolutionParams: ResolutionParams,
    cache: Cache[F],
    through: F[Resolution] => F[Resolution],
    transformFetcher: ResolutionProcess.Fetch[F] => ResolutionProcess.Fetch[F],
    S: Sync[F]
  )

  private[coursier] def initialResolution(
    dependencies: Seq[Dependency],
    params: ResolutionParams = ResolutionParams()
  ): Resolution = {

    val forceScalaVersions =
      if (params.doForceScalaVersion) {
        val scalaOrg =
          if (params.typelevel) org"org.typelevel"
          else org"org.scala-lang"
        Seq(
          Module(scalaOrg, name"scala-library") -> params.selectedScalaVersion,
          Module(scalaOrg, name"org.scala-lang:scala-reflect") -> params.selectedScalaVersion,
          Module(scalaOrg, name"org.scala-lang:scala-compiler") -> params.selectedScalaVersion,
          Module(scalaOrg, name"org.scala-lang:scalap") -> params.selectedScalaVersion
        )
      } else
        Nil

    val mapDependencies = {
      val l = (if (params.typelevel) Seq(Typelevel.swap) else Nil) ++
        (if (params.doForceScalaVersion) Seq(coursier.core.Resolution.forceScalaVersion(params.selectedScalaVersion)) else Nil)

      l.reduceOption((f, g) => dep => f(g(dep)))
    }

    Resolution(
      dependencies,
      forceVersions = params.forceVersion ++ forceScalaVersions,
      filter = Some(dep => params.keepOptionalDependencies || !dep.optional),
      userActivations =
        if (params.profiles.isEmpty) None
        else Some(params.profiles.iterator.map(p => if (p.startsWith("!")) p.drop(1) -> false else p -> true).toMap),
      forceProperties = params.forcedProperties,
      mapDependencies = mapDependencies
    )
  }

  private[coursier] def runProcess[F[_]](
    initialResolution: Resolution,
    fetch: ResolutionProcess.Fetch[F],
    maxIterations: Int = 200,
    loggerOpt: Option[CacheLogger] = None
  )(implicit S: Sync[F]): F[Resolution] = {

    val task = initialResolution
      .process
      .run(fetch, maxIterations)

    loggerOpt match {
      case None =>
        task
      case Some(logger) =>
        S.bind(S.delay(logger.init())) { _ =>
          S.bind(S.attempt(task)) { a =>
            S.bind(S.delay(logger.stop())) { _ =>
              S.fromAttempt(a)
            }
          }
        }
    }
  }

  def validate(res: Resolution): ValidationNel[ResolutionError, Unit] = {

    val checkDone: ValidationNel[ResolutionError, Unit] =
      if (res.isDone)
        ValidationNel.success(())
      else
        ValidationNel.failure(new ResolutionError.MaximumIterationReached(res))

    val checkErrors: ValidationNel[ResolutionError, Unit] = res
      .errors
      .map {
        case ((module, version), errors) =>
          new ResolutionError.CantDownloadModule(res, module, version, errors)
      } match {
        case Seq() =>
          ValidationNel.success(())
        case Seq(h, t @ _*) =>
          ValidationNel.failures(h, t: _*)
      }

    val checkConflicts: ValidationNel[ResolutionError, Unit] =
      if (res.conflicts.isEmpty)
        ValidationNel.success(())
      else
        ValidationNel.failure(
          new ResolutionError.ConflictingDependencies(
            res,
            res.conflicts.map { dep =>
              dep.copy(
                version = res.projectCache.get(dep.moduleVersion).fold(dep.version)(_._2.actualVersion)
              )
            }
          )
        )

    checkDone.zip(checkErrors, checkConflicts).map {
      case ((), (), ()) =>
    }
  }

}

package tofu.data.calc

import tofu.higherKind.bi.FunBK

import cats.evidence.Is
import cats.Monoid

import scala.annotation.tailrec
import cats.evidence.As

sealed abstract class CalcM[+F[+_, +_], -R, -SI, +SO, +E, +A] extends CalcMOps[F, R, SI, SO, E, A] {
  def translateState[G[+_, +_], ST, R1](
      translator: Translator[F, G, ST, R, R1]
  ): CalcM[G, R1, (ST, SI), (ST, SO), E, A]

  def translate[G[+_, +_], R1](translator: ITranslator[F, G, R, R1]): CalcM[G, R1, SI, SO, E, A] =
    CalcM[SI].mapState(si => ((), si)) *>> translateState(translator).mapState(_._2)
}

object CalcM extends CalcMInstances {
  def apply[S]: CalcM[Nothing, Any, S, S, Nothing, S] = get[S]

  def unit[S]: CalcM[Nothing, Any, S, S, Nothing, Unit]       = Pure(())
  def pure[S, A](a: A): CalcM[Nothing, Any, S, S, Nothing, A] = Pure(a)
  def read[S, R]: CalcM[Nothing, R, S, S, Nothing, R]         = Read()
  def get[S]: CalcM[Nothing, Any, S, S, Nothing, S]           = Get()
  def set[S](s: S): CalcM[Nothing, Any, Any, S, Nothing, S]   = Set(s)

  def update[S1, S2](f: S1 => S2): CalcM[Nothing, Any, S1, S2, Nothing, S2]       =
    get[S1].flatMapS(s => set(f(s)))
  def state[S1, S2, A](f: S1 => (S2, A)): CalcM[Nothing, Any, S1, S2, Nothing, A] =
    get[S1].flatMapS { s1 =>
      val (s2, a) = f(s1)
      set(s2) as a
    }

  def raise[S, E](e: E): CalcM[Nothing, Any, S, S, E, Nothing]           = Raise(e)
  def defer[F[+_, +_], R, S1, S2, E, A](x: => CalcM[F, R, S1, S2, E, A]) = Defer(() => x)
  def delay[S, A](x: => A): CalcM[Nothing, Any, S, S, Nothing, A]        = defer(pure[S, A](x))

  def write[S](s: S)(implicit S: Monoid[S]): CalcM[Nothing, Any, S, S, Nothing, S] = update(S.combine(_, s))

  def lift[F[+_, +_], S, E, A](fea: F[E, A]): CalcM[F, Any, S, S, E, A] = Sub(fea)

  sealed trait CalcMRes[-R, -S1, +S2, +E, +A] extends CalcM[Nothing, R, S1, S2, E, A] {
    def submit[X](submit: Submit[R, S1, S2, E, A, X]): X
    override def mapK[G[+_, +_]](fk: Nothing FunBK G): CalcM[G, R, S1, S2, E, A] = this
  }

  sealed trait CalcMResStatic[-S1, +S2, +E, +A] extends CalcMRes[Any, S1, S2, E, A] {
    override def translate[G[+_, +_], R1](translator: ITranslator[Nothing, G, Any, R1]): CalcM[G, R1, S1, S2, E, A] =
      this
    def translateState[G[+_, +_], ST, R1](
        translator: Translator[Nothing, G, ST, Any, R1]
    ): CalcM[G, R1, (ST, S1), (ST, S2), E, A]                                                                       = focusSecond
  }

  final case class Pure[S, +A](a: A) extends CalcMResStatic[S, S, Nothing, A]   {
    def submit[X](submit: Submit[Any, S, S, Nothing, A, X]): X                                             = submit.success(submit.state, a)
    override def local[R1](f: R1 => Any): CalcM[Nothing, R1, S, S, Nothing, A]                             = this
    override def dimapState[SI1, SO1](f: SI1 => S, g: S => SO1): CalcM[Nothing, Any, SI1, SO1, Nothing, A] =
      update(g compose f) as_ a
  }
  final case class Read[S, R]()      extends CalcMRes[R, S, S, Nothing, R]      {
    def submit[X](submit: Submit[R, S, S, Nothing, R, X]): X                                                          = submit.success(submit.state, submit.read)
    override def local[R1](f: R1 => R): CalcM[Nothing, R1, S, S, Nothing, R]                                          = Read[S, R1].map(f)
    override def translate[G[+_, +_], R1](translator: ITranslator[Nothing, G, R, R1]): CalcM[G, R1, S, S, Nothing, R] =
      CalcM.read[S, R1].map(translator.mapRead)
    def translateState[G[+_, +_], ST, R1](
        translator: Translator[Nothing, G, ST, R, R1]
    ): CalcM[G, R1, (ST, S), (ST, S), Nothing, R]                                                                     =
      CalcM.read[S, R1].map(translator.mapRead).focusSecond
  }
  final case class Get[S]()          extends CalcMResStatic[S, S, Nothing, S]   {
    def submit[X](submit: Submit[Any, S, S, Nothing, S, X]): X                                             = submit.success(submit.state, submit.state)
    override def local[R1](f: R1 => Any): CalcM[Nothing, R1, S, S, Nothing, S]                             = this
    override def dimapState[SI1, SO1](f: SI1 => S, g: S => SO1): CalcM[Nothing, Any, SI1, SO1, Nothing, S] =
      get[SI1] *>> state { s1 =>
        val s = f(s1)
        (g(s), s)
      }
  }
  final case class Set[S](s: S)      extends CalcMResStatic[Any, S, Nothing, S] {
    def submit[X](submit: Submit[Any, Any, S, Nothing, S, X]): X                                             = submit.success(s, s)
    override def local[R1](f: R1 => Any): CalcM[Nothing, R1, Any, S, Nothing, S]                             = this
    override def dimapState[SI1, SO1](f: SI1 => Any, g: S => SO1): CalcM[Nothing, Any, SI1, SO1, Nothing, S] =
      set(g(s)) as_ s
  }
  final case class Raise[S, E](e: E) extends CalcMResStatic[S, S, E, Nothing]   {
    def submit[X](submit: Submit[Any, S, S, E, Nothing, X]): X                                             = submit.error(submit.state, e)
    override def local[R1](f: R1 => Any): CalcM[Nothing, R1, S, S, E, Nothing]                             = this
    override def dimapState[SI1, SO1](f: SI1 => S, g: S => SO1): CalcM[Nothing, Any, SI1, SO1, E, Nothing] =
      update(g compose f).swap errorAs_ e
  }
  final case class Defer[+F[+_, +_], -R, -S1, +S2, +E, +A](runStep: () => CalcM[F, R, S1, S2, E, A])
      extends CalcM[F, R, S1, S2, E, A] {
    override def mapK[G[+_, +_]](fk: F FunBK G): CalcM[G, R, S1, S2, E, A] = Defer(() => runStep().mapK(fk))

    override def translate[G[+_, +_], R1](translator: ITranslator[F, G, R, R1]): CalcM[G, R1, S1, S2, E, A] =
      Defer(() => runStep().translate(translator))

    def translateState[G[+_, +_], ST, R1](
        translator: Translator[F, G, ST, R, R1]
    ): CalcM[G, R1, (ST, S1), (ST, S2), E, A] =
      Defer(() => runStep().translateState(translator))
  }

  sealed trait ProvideM[+F[+_, +_], R, -S1, +S2, +E, +A] extends CalcM[F, R, S1, S2, E, A] {
    type R1
    def r: R1
    def inner: CalcM[F, R1, S1, S2, E, A]
    def any: R Is Any
  }

  final case class Provide[+F[+_, +_], R, -S1, +S2, +E, +A](r: R, inner: CalcM[F, R, S1, S2, E, A])
      extends ProvideM[F, Any, S1, S2, E, A] {
    type R1 = R
    def any                                                                  = Is.refl
    override def mapK[G[+_, +_]](fk: F FunBK G): CalcM[G, Any, S1, S2, E, A] = Provide(r, inner.mapK(fk))

    override def local[R2](f: R2 => Any): CalcM[F, R2, S1, S2, E, A]                                          = this
    override def translate[G[+_, +_], R2](translator: ITranslator[F, G, Any, R2]): CalcM[G, R2, S1, S2, E, A] =
      inner.translate(translator.setR(r))

    override def translateState[G[+_, +_], ST, R2](
        translator: Translator[F, G, ST, Any, R2]
    ): CalcM[G, R2, (ST, S1), (ST, S2), E, A] = inner.translateState(translator.setR(r))
  }

  abstract class Sub[+F[+_, +_], -S1, +S2, E, A](val fa: F[E, A]) extends CalcM[F, Any, S1, S2, E, A] {
    def iss: S1 As S2
    def subcalc[F1[+_, +_], R, SX, E1, A1](calc: CalcM[F1, R, S2, SX, E1, A1]): CalcM[F1, R, S1, SX, E1, A1] =
      iss.substitute[CalcM[F1, R, -*, SX, E1, A1]](calc)
    override def local[R1](f: R1 => Any): CalcM[F, R1, S1, S2, E, A]                                         = this
  }

  object Sub {

    def apply[F[+_, +_], S, E, A](fa: F[E, A]): CalcM[F, Any, S, S, E, A] = new Sub[F, S, S, E, A](fa) {
      def iss = As.refl

      override def mapK[G[+_, +_]](fk: F FunBK G): CalcM[G, Any, S, S, E, A] = Sub(fk(fa))

      override def translate[G[+_, +_], R1](translator: ITranslator[F, G, Any, R1]): CalcM[G, R1, S, S, E, A] =
        translator.translate(fa)

      override def translateState[G[+_, +_], ST, R1](
          translator: Translator[F, G, ST, Any, R1]
      ): CalcM[G, R1, (ST, S), (ST, S), E, A] =
        translator.translateState(fa)
    }
  }

  final case class Bound[+F[+_, +_], R, S1, S2, S3, E1, E2, A, B](
      src: CalcM[F, R, S1, S2, E1, A],
      continue: Continue[A, E1, CalcM[F, R, S2, S3, E2, B]],
  ) extends CalcM[F, R, S1, S3, E2, B] {
    type MidState = S2
    type MidErr   = E1
    type MidVal   = A

    override def mapK[G[+_, +_]](fk: F FunBK G): CalcM[G, R, S1, S3, E2, B] =
      src.mapK(fk).bind(continue.map(_.mapK(fk)))

    override def translate[G[+_, +_], R1](translator: ITranslator[F, G, R, R1]): CalcM[G, R1, S1, S3, E2, B] =
      src.translate(translator).bind(continue.map(_.translate(translator)))

    override def translateState[G[+_, +_], ST, R1](
        translator: Translator[F, G, ST, R, R1]
    ): CalcM[G, R1, (ST, S1), (ST, S3), E2, B] =
      src.translateState(translator).bind(continue.map(_.translateState(translator)))
  }

  abstract class Submit[+R, +S1, -S2, -E, -A, +X](val read: R, val state: S1) {
    def success(s: S2, a: A): X
    def error(s: S2, err: E): X
  }

  @tailrec
  def step[F[+_, +_], R, S1, S2, E, A](calc: CalcM[F, R, S1, S2, E, A], r: R, init: S1): StepResult[F, S2, E, A] =
    calc match {
      case res: CalcMRes[R, S1, S2, E, A]            =>
        res.submit(new Submit[R, S1, S2, E, A, StepResult[F, S2, E, A]](r, init) {
          def success(s: S2, a: A) = StepResult.Ok(s, a)
          def error(s: S2, err: E) = StepResult.Error(s, err)
        })
      case d: Defer[F, R, S1, S2, E, A]              => step(d.runStep(), r, init)
      case sub: Sub[F, S1, S2, E, A]                 =>
        StepResult.Wrap[F, R, S1, S2, E, E, A, A](
          r,
          init,
          sub.fa,
          (e: E) => sub.iss.substitute[CalcM[F, R, -*, S2, E, A]](CalcM.raise[S2, E](e)),
          (a: A) => sub.iss.substitute[CalcM[F, R, -*, S2, E, A]](CalcM.pure[S2, A](a))
        )
      case p: Provide[F, r, S1, S2, E, A]            => step[F, r, S1, S2, E, A](p.inner, p.r, init)
      case c1: Bound[F, R, S1, s1, S2, e1, E, a1, A] =>
        c1.src match {
          case res: CalcMRes[R, S1, c1.MidState, e1, a1] =>
            val (sm, next) =
              res.submit(new Submit[R, S1, s1, e1, a1, (s1, CalcM[F, R, s1, S2, E, A])](r, init) {
                def success(s: s1, a: a1) = (s, c1.continue.success(a))
                def error(s: s1, err: e1) = (s, c1.continue.error(err))
              })
            step[F, R, s1, S2, E, A](next, r, sm)
          case d: Defer[F, R, S1, _, _, _]               => step(d.runStep().bind(c1.continue), r, init)
          case sub: Sub[F, S1, s1, c1.MidErr, c1.MidVal] =>
            StepResult
              .Wrap[F, R, S1, S2, e1, E, a1, A](
                r,
                init,
                sub.fa,
                e => sub.subcalc[F, R, S2, E, A](c1.continue.error(e)),
                a => sub.subcalc[F, R, S2, E, A](c1.continue.success(a)),
              )
          case p: ProvideM[F, R, S1, _, _, _]            =>
            val kcont = p.any.substitute[λ[r => Continue[a1, e1, CalcM[F, r, s1, S2, E, A]]]](c1.continue)

            step(p.inner.bind[F, p.R1, E, S2, A](kcont), p.r, init)
          case c2: Bound[F, R, S1, s2, _, e2, _, a2, _]  =>
            step(c2.src.bind(Continue.compose(c2.continue, c1.continue)), r, init)
        }
    }
}

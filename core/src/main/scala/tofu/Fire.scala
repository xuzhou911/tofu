package tofu

import cats.effect.{Concurrent, Fiber}
import simulacrum.typeclass
import tofu.internal.NonTofu

import scala.util.Either
import scala.annotation.nowarn

@typeclass
trait Fire[F[_]] {
  def fireAndForget[A](fa: F[A]): F[Unit]
}

object Fire extends StartInstances[Fire]

@typeclass
trait Race[F[_]] extends Fire[F] {
  def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]]
  def never[A]: F[A]
}

object Race extends StartInstances[Race]

@typeclass
trait Start[F[_]] extends Fire[F] with Race[F] {
  def start[A](fa: F[A]): F[Fiber[F, A]]
  def racePair[A, B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]]
}

object Start extends StartInstances[Start]

trait StartInstances[TC[f[_]] >: Start[f]] {
  final implicit def concurrentInstance[F[_]](implicit F: Concurrent[F], @nowarn _nonTofu: NonTofu[F]): TC[F] =
    new Start[F] {
      def start[A](fa: F[A]): F[Fiber[F, A]]                                                = F.start(fa)
      def fireAndForget[A](fa: F[A]): F[Unit]                                               = F.void(start(fa))
      def racePair[A, B](fa: F[A], fb: F[B]): F[Either[(A, Fiber[F, B]), (Fiber[F, A], B)]] = F.racePair(fa, fb)
      def race[A, B](fa: F[A], fb: F[B]): F[Either[A, B]]                                   = F.race(fa, fb)
      def never[A]: F[A]                                                                    = F.never
    }

}

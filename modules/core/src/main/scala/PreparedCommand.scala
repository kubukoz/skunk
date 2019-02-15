// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk

import cats.{Contravariant, ~>}
import cats.effect.Bracket
import skunk.data.Completion
import skunk.net.Protocol

/**
 * A prepared command, valid for the life of its defining `Session`.
 * @group Session
 */
trait PreparedCommand[F[_], A] { outer =>
  def check: F[Unit]
  def execute(args: A): F[Completion]

  def mapK[G[_]](f: F ~> G): PreparedCommand[G, A] = new PreparedCommand[G, A] {
    override def check: G[Unit] = f(outer.check)
    override def execute(args: A): G[Completion] = f(outer.execute(args))
  }
}

/** @group Companions */
object PreparedCommand {

  /** `PreparedCommand[F, ?]` is a contravariant functor for all `F`. */
  implicit def contravariantPreparedCommand[F[_]]: Contravariant[PreparedCommand[F, ?]] =
    new Contravariant[PreparedCommand[F, ?]] {
      def contramap[A, B](fa: PreparedCommand[F,A])(f: B => A) =
        new PreparedCommand[F, B] {
          def check = fa.check
          def execute(args: B) = fa.execute(f(args))
        }
    }

  def fromProto[F[_]: Bracket[?[_], Throwable], A](pc: Protocol.PreparedCommand[F, A]) =
    new PreparedCommand[F, A] {
      def check = pc.check
      def execute(args: A) =
        Bracket[F, Throwable].bracket(pc.bind(args))(_.execute)(_.close)
    }

}

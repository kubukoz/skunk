// Copyright (c) 2018 by Rob Norris
// This software is licensed under the MIT License (MIT).
// For more information see LICENSE or https://opensource.org/licenses/MIT

package skunk

import cats._
import cats.arrow.Profunctor
import cats.effect._
import cats.implicits._
import fs2.{ Chunk, Stream }
import skunk.net.Protocol

/**
 * A prepared query, valid for the life of its originating `Session`.
 * @group Session
 */
trait PreparedQuery[F[_], A, B] {

  /**
   * Check that this `PreparedQuery`'s asserted argument and result types align correctly with the
   * schema. In case of misalignment an exception is raised with a description of the problem.
   */
  def check: F[Unit]

  /**
   * `Resource` that binds the supplied arguments to this `PreparedQuery`, yielding a `Cursor` from
   * which rows can be `fetch`ed. Note that higher-level operations like `stream`, `option`, and
   * `unique` are usually what you want.
   */
  def cursor(args: A): Resource[F, Cursor[F, B]]

  /**
   * Construct a `Cursor`-backed stream that calls `fetch` repeatedly and emits chunks until none
   * remain. Note that each chunk is read atomically while holding the session mutex, which means
   * interleaved streams will achieve better fairness with smaller chunks but greater overall
   * throughput with larger chunks. So it's important to consider the use case when specifying
   * `chunkSize`.
   */
  def stream(args: A, chunkSize: Int): Stream[F, B]

  /**
   * Fetch and return at most one row, raising an exception if more rows are available.
   */
  def option(args: A): F[Option[B]]

  /**
   * Fetch and return exactly one row, raising an exception if there are more or fewer.
   */
  def unique(args: A): F[B]

}

/** @group Companions */
object PreparedQuery {

  def mapK[F[_]: Bracket[?[_], Throwable], G[_]: Defer: Applicative, A, B](f: F ~> G)(pq: PreparedQuery[F, A, B]): PreparedQuery[G, A, B] = new PreparedQuery[G, A, B] {
    override def check: G[Unit] = f(pq.check)
    override def cursor(args: A): Resource[G, Cursor[G, B]] = pq.cursor(args).map(_.mapK(f)).mapK(f)
    override def stream(args: A, chunkSize: Int): Stream[G, B] = pq.stream(args, chunkSize).translate(f)
    override def option(args: A): G[Option[B]] = f(pq.option(args))
    override def unique(args: A): G[B] = f(pq.unique(args))
  }

  /**
   * `PreparedQuery[F, ?, B]` is a covariant functor when `F` is a monad.
   * @group Typeclass Instances
   */
  implicit def functorPreparedQuery[F[_]: Monad, A]: Functor[PreparedQuery[F, A, ?]] =
    new Functor[PreparedQuery[F, A, ?]] {
      def map[T, U](fa: PreparedQuery[F, A, T])(f: T => U) =
        new PreparedQuery[F, A, U] {
            def check = fa.check
            def cursor(args: A) = fa.cursor(args).map(_.map(f))
            def stream(args: A, chunkSize: Int) = fa.stream(args, chunkSize).map(f)
            def option(args: A) = fa.option(args).map(_.map(f))
            def unique(args: A) = fa.unique(args).map(f)
        }
    }

  /**
   * `PreparedQuery[F, ?, B]` is a contravariant functor for all `F`.
   * @group Typeclass Instances
   */
  implicit def contravariantPreparedQuery[F[_], B]: Contravariant[PreparedQuery[F, ?, B]] =
    new Contravariant[PreparedQuery[F, ?, B]] {
      def contramap[T, U](fa: PreparedQuery[F, T, B])(f: U => T) =
        new PreparedQuery[F, U, B] {
            def check = fa.check
            def cursor(args: U) = fa.cursor(f(args))
            def stream(args: U, chunkSize: Int) = fa.stream(f(args), chunkSize)
            def option(args: U) = fa.option(f(args))
            def unique(args: U) = fa.unique(f(args))
        }
    }

  /**
   * `PreparedQuery[F, ?, ?]` is a profunctor if `F` is a monad.
   * @group Typeclass Instances
   */
  implicit def profunctorPreparedQuery[F[_]: Monad]: Profunctor[PreparedQuery[F, ?, ?]] =
    new Profunctor[PreparedQuery[F, ?, ?]] {
      def dimap[A, B, C, D](fab: PreparedQuery[F, A, B])(f: (C) ⇒ A)(g: (B) ⇒ D) =
        contravariantPreparedQuery[F, B].contramap(fab)(f).map(g) // y u no work contravariant syntax
      override def lmap[A, B, C](fab: PreparedQuery[F, A, B])(f: (C) ⇒ A) =
        contravariantPreparedQuery[F, B].contramap(fab)(f)
      override def rmap[A, B, C](fab: PreparedQuery[F, A, B])(f: (B) ⇒ C) = fab.map(f)
    }

  def fromProto[F[_]: Bracket[?[_], Throwable], A, B](proto: Protocol.PreparedQuery[F, A, B]) =
    new PreparedQuery[F, A, B] {

      def check =
        proto.check

      def cursor(args: A) =
        Resource.make(proto.bind(args))(_.close).map { p =>
          new Cursor[F, B] {
            def fetch(maxRows: Int) =
              p.execute(maxRows)
          }
        }

      def stream(args: A, chunkSize: Int) = {
        val rsrc = Resource.make(proto.bind(args))(_.close)
        Stream.resource(rsrc).flatMap { cursor =>
          def chunks: Stream[F, B] =
            Stream.eval(cursor.execute(chunkSize)).flatMap { case (bs, more) =>
              val s = Stream.chunk(Chunk.seq(bs))
              if (more) s ++ chunks
              else s
            }
          chunks
        }
      }

      // We have a few operations that only want the first row. In order to do this AND
      // know if there are more we need to ask for 2 rows.
      private def fetch2(args: A): F[(List[B], Boolean)] =
        cursor(args).use(_.fetch(2))

      def option(args: A) =
        fetch2(args).flatMap { case (bs, _) =>
          bs match {
            case b :: Nil => b.some.pure[F]
            case Nil      => none[B].pure[F]
            case _        => MonadError[F, Throwable].raiseError(new RuntimeException("Expected exactly one result, more returned."))
          }
        }

      def unique(args: A) =
        fetch2(args).flatMap { case (bs, _) =>
          bs match {
            case b :: Nil => b.pure[F]
            case Nil      => MonadError[F, Throwable].raiseError(new RuntimeException("Expected exactly one result, none returned."))
            case _        => MonadError[F, Throwable].raiseError(new RuntimeException("Expected exactly one result, more returned."))
          }
        }

    }

}

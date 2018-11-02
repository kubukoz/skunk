package example

import cats.effect._
import skunk._
import skunk.implicits._
import skunk.codec.numeric._

object Minimal extends IOApp {

  val session: Resource[IO, Session[IO]] =
    Session.single(
      host     = "localhost",
      port     = 5432,
      user     = "postgres",
      database = "world",
    )

  def run(args: List[String]): IO[ExitCode] =
    session.use { s =>
      for {
        n <- s.execute(sql"select 42".query(int4))
        _ <- IO(println(s"The answer is $n."))
      } yield ExitCode.Success
    }

}
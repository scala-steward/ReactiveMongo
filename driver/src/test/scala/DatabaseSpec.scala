import scala.concurrent.Await
import scala.concurrent.duration.FiniteDuration

import reactivemongo.core.errors.DatabaseException

import reactivemongo.api.{ FailoverStrategy, MongoConnection }
import reactivemongo.api.commands.CommandException

import org.specs2.concurrent.ExecutionEnv

final class DatabaseSpec(
    implicit
    protected val ee: ExecutionEnv)
    extends org.specs2.mutable.Specification
    with DBSessionSpec {

  "Database".title

  sequential
  stopOnFail

  import tests.Common
  import Common._

  "Database" should {
    "be resolved from connection according the failover strategy" >> {
      "successfully" in {
        val fos = FailoverStrategy(FiniteDuration(50, "ms"), 20, _ * 2D)

        Common.connection
          .database(Common.commonDb, fos)
          .map(_ => {}) must beTypedEqualTo({}).await(1, estTimeout(fos))

      }

      "with failure" in {
        lazy val con = Common.driver.connect(List("unavailable:27017"))
        val ws = scala.collection.mutable.ListBuffer.empty[Int]
        val expected = List(2, 4, 6, 8, 10, 12, 14, 16, 18, 20, 22, 24, 26, 28,
          30, 32, 34, 36, 38, 40)
        val fos1 = FailoverStrategy(
          FiniteDuration(50, "ms"),
          20,
          { n =>
            val w = n * 2; ws += w; w.toDouble
          }
        )
        val fos2 = FailoverStrategy(
          FiniteDuration(50, "ms"),
          20,
          _ * 2D
        ) // without accumulator

        val before = System.currentTimeMillis()
        val estmout = estTimeout(fos2)

        con
          .flatMap(_.database("foo", fos1))
          .map(_ => List.empty[Int])
          .recover({ case _ => ws.result() }) must beTypedEqualTo(expected)
          .await(0, estmout * 2) and {
          val duration = System.currentTimeMillis() - before

          duration must be_<((estmout * 2).toMillis + 1500 /* ms */ )
        }
      } tag "unit"
    }

    sessionSpecs

    "admin" >> {
      "rename successfully collection if target doesn't exist" in {
        eventually(2, timeout) {
          (for {
            admin <- connection.database("admin", failoverStrategy)
            name1 <- {
              val name =
                s"foo${System.identityHashCode(admin)}-${System.currentTimeMillis()}"

              db.collection(name).create().map(_ => name)
            }
            name = s"renamed_${System.identityHashCode(name1)}"
            c2 <- admin.renameCollection(db.name, name1, name)
          } yield name -> c2.name) must beLike[(String, String)] {
            case (expected, name) => name aka "new name" must_=== expected
          }.awaitFor(timeout)
        }
      }

      "fail to rename collection if target exists" in eventually(2, timeout) {
        val colName =
          s"mv_fail_${System.identityHashCode(ee)}-${System.currentTimeMillis()}"

        val c1 = {
          val c = db.collection(colName)
          c.create(failsIfExists = false).map(_ => c)
        }

        (for {
          _ <- c1
          name = s"renamed_${System.identityHashCode(c1)}"
          c2 = db.collection(name)
          _ <- c2.create(failsIfExists = false)
        } yield name) must beLike[String] {
          case name =>
            name must not(beEqualTo(colName)) and {
              db.collectionNames.map(_.contains(name)) must beTrue.awaitFor(
                timeout
              ) and {
                Await.result(
                  for {
                    admin <- connection.database("admin", failoverStrategy)
                    _ <- admin.renameCollection(db.name, colName, name)
                  } yield {},
                  timeout
                ) must throwA[DatabaseException].like {
                  case err @ CommandException.Code(48) =>
                    err.getMessage.indexOf("target namespace exists") must not(
                      beEqualTo(-1)
                    )
                }
              }
            }
        }.await(1, timeout)
      }
    }

    {
      def dropSpec(
          con: MongoConnection,
          dbName: String,
          timeout: FiniteDuration
        ) =
        con
          .database(dbName)
          .flatMap(_.drop())
          .aka("drop") must beTypedEqualTo({}).awaitFor(timeout * 4L)

      "be dropped with the default connection" in {
        val dbName = s"databasespec-${System.identityHashCode(ee)}"

        dropSpec(connection, dbName, timeout)
      }

      "be dropped with the slow connection" in {
        val dbName = s"slowdatabasespec-${System.identityHashCode(ee)}"

        dropSpec(slowConnection, dbName, slowTimeout)
      }
    }
  }
}

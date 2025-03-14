import reactivemongo.api.{ tests => apiTests, DB, WriteConcern }
import reactivemongo.api.bson.BSONDocument

trait DBSessionSpec { specs: DatabaseSpec =>
  import tests.Common
  import Common._

  @inline private def _db =
    Common.connection.database(s"dbsession-${System.identityHashCode(this)}")

  def sessionSpecs = "manage session" >> {
    section("gt_mongo32")

    "start & end" in {
      (for {
        db <- _db.flatMap(_.startSession())

        // NoOp startSession
        _ <- db.startSession()
        _ <- db.startSession(failIfAlreadyStarted = false)
        _ <- db.startSession(failIfAlreadyStarted = true).failed

        after <- db.endSession()

        // NoOp endSession
        _ <- after.endSession()
        _ <- after.endSession(failIfNotStarted = false)
        _ <- after.endSession(failIfNotStarted = true).failed
      } yield {
        System.identityHashCode(db) -> System.identityHashCode(after)
      }) must beLike[(Int, Int)] {
        case (hash1, hash2) => hash1 must not(beEqualTo(hash2))
      }.awaitFor(timeout)
    }

    "not kill without start" in {
      _db.flatMap(_.killSession()) must beAnInstanceOf[DB].await
    }

    "start & kill" in {
      (for {
        db1 <- _db.flatMap(_.startSession())
        db2 <- db1.killSession()
      } yield db2) must beAnInstanceOf[DB].awaitFor(timeout)
    }

    if (replSetOn) {
      section("ge_mongo4")
      "start & abort transaction" in {
        val colName = s"tx1_${System.identityHashCode(this)}"
        @volatile var database = Option.empty[DB]

        eventually(2, timeout) {
          (for {
            db <- _db
            _ <- db.collection(colName).create(failsIfExists = false)
            _ <- db.collectionNames.filter(_.contains(colName))
          } yield {}) must beTypedEqualTo({}).await(0, timeout)
        } and {
          _db.flatMap(_.startSession()).flatMap { _db =>
            for {
              _ <- _db.startTransaction(None)

              // NoOp
              _ <- _db.startTransaction(None)
              _ <- _db.startTransaction(None, false)
              _ <- _db.startTransaction(None, true).failed
            } yield {
              database = Some(_db)
              database
            }
          } must beSome[DB].await(0, timeout)
        } and (database must beSome[DB].which { db =>
          lazy val coll = db.collection(colName)

          def find() = coll
            .find(
              selector = BSONDocument.empty,
              projection = Option.empty[BSONDocument]
            )
            .one[BSONDocument]

          apiTests
            .session(db)
            .flatMap(_.transaction.toOption.map(_.txnNumber)) must beSome(
            1L
          ) and {
            (for {
              n <- find().map(_.size)

              // See recover(code=251) in endTransaction
              s <- db.abortTransaction().map(apiTests.session)

              // NoOp abort
              _ <- db.abortTransaction()
              _ <- db.abortTransaction(failIfNotStarted = false)
              _ <- db.abortTransaction(failIfNotStarted = true).failed
            } yield s.map(n -> _.transaction.toOption.map(_.txnNumber))).aka(
              "session after tx"
            ) must beSome(0 -> Option.empty[Long]).awaitFor(timeout)

          } and {
            // Start a new transaction with the same session
            db.startTransaction(None, failIfAlreadyStarted = false)
              .map(apiTests.session)
              .map {
                _.flatMap(_.transaction.toOption).map(_.txnNumber)
              } must beSome(2L).await(1, timeout)
          } and {
            // Insert a doc in the transaction,
            // and check the count before & after

            val inserted = BSONDocument("_id" -> 1)

            (for {
              n1 <- find().map(_.size) // before insert
              _ <- coll.insert.one(inserted)

              n2 <- coll
                .find(
                  selector = inserted,
                  projection = Option.empty[BSONDocument]
                )
                .one[BSONDocument]
                .map(_.size)

            } yield n1 -> n2) must beTypedEqualTo(0 -> 1).awaitFor(timeout)
          } and {
            // 0 document found outside the transaction
            _db.flatMap {
              _.collection(colName)
                .find(
                  selector = BSONDocument.empty,
                  projection = Option.empty[BSONDocument]
                )
                .one[BSONDocument]
                .map(_.size)
            } must beTypedEqualTo(0).awaitFor(timeout)
          } and {
            // 0 document found in session after transaction is aborted

            db.abortTransaction().map { aborted =>
              val session = apiTests.session(aborted)

              session.map { s =>
                s.lsid.toString -> s.transaction.toOption.map(_.txnNumber)
              }
            } must beSome[(String, Option[Long])].like {
              case (_ /*lsid*/, None /*transaction*/ ) =>
                find().map(_.size) must beTypedEqualTo(0).awaitFor(timeout)

            }.awaitFor(timeout)
          }
        })
      }

      "cannot abort transaction after session is killed" in {
        val colName = s"abort-after-killed${System.identityHashCode(this)}"

        (for {
          db <- _db
          _ <- db.collection(colName).create(failsIfExists = false)

          sdb <- db.startSession()
          tdb <- sdb.startTransaction(None)

          c = sdb.collection(colName)
          _ <- c.insert.one(BSONDocument("foo" -> 1))
          _ <- c.insert.many(
            Seq(BSONDocument("foo" -> 2), BSONDocument("bar" -> 3))
          )

          kdb <- tdb.killSession()
          _ <- kdb.abortTransaction(failIfNotStarted = true).failed
        } yield ()) must beTypedEqualTo({}).awaitFor(timeout)
      }

      "cannot commit transaction after session is killed" in {
        (for {
          sdb <- _db.flatMap(_.startSession())
          tdb <- sdb.startTransaction(None)

          kdb <- tdb.killSession()
          _ <- kdb.commitTransaction(failIfNotStarted = true).failed
        } yield ()) must beTypedEqualTo({}).awaitFor(timeout)
      }

      "start & commit transaction" in {
        val colName = s"tx2_${System.identityHashCode(this)}"
        @volatile var database = Option.empty[DB]

        _db.flatMap(_.startSession()).flatMap { sdb =>
          for {
            _ <- sdb.collection(colName).create()
            tdb <- sdb.startTransaction(
              Some(WriteConcern.Default.copy(w = WriteConcern.Majority))
            )
          } yield {
            database = Some(tdb)
            database
          }
        } must beSome[DB]
          .awaitFor(timeout) and (database must beSome[DB].which { db =>
          lazy val coll = db.collection(colName)

          def find() = coll
            .find(
              selector = BSONDocument.empty,
              projection = Option.empty[BSONDocument]
            )
            .cursor[BSONDocument]()

          find().headOption must beNone.awaitFor(timeout) and {
            coll.insert
              .many(Seq(BSONDocument("_id" -> 1)))
              .
              // one(BSONDocument("_id" -> 1)).
              map(_ => {}) must beTypedEqualTo({}).awaitFor(timeout)
          } and {
            // 1 document found in transaction after insert
            find().collect[List]().map(_.size) must beTypedEqualTo(1).awaitFor(
              timeout
            )

          } and {
            // 0 document found outside transaction
            _db.flatMap {
              _.collection(colName)
                .find(
                  selector = BSONDocument("_id" -> 1),
                  projection = Option.empty[BSONDocument]
                )
                .one[BSONDocument]
                .map(_.size)
            } must beTypedEqualTo(0).awaitFor(timeout)
          } and {
            coll.insert
              .many(Seq(BSONDocument("_id" -> 2), BSONDocument("_id" -> 3)))
              .map(_.n) must beTypedEqualTo(2).awaitFor(timeout)
          } and {
            find().collect[List]().map(_.size) must beTypedEqualTo(3).awaitFor(
              timeout
            )
          } and {
            db.commitTransaction().aka("commited") must beAnInstanceOf[DB]
              .awaitFor(timeout)

          } and {
            // 1 document found outside transaction after commit

            _db.flatMap {
              _.collection(colName)
                .find(
                  selector = BSONDocument("_id" -> 1),
                  projection = Option.empty[BSONDocument]
                )
                .one[BSONDocument]
                .map(_.size)
            } must beTypedEqualTo(1).awaitFor(timeout)
          }
        })
      }

      section("ge_mongo4")
    } // end: replSetOn

    section("gt_mongo32")
  }
}

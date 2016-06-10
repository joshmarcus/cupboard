package com.meetup.cupboard.tests

import java.time.{ZoneOffset, ZonedDateTime}

import org.scalatest._
import com.meetup.cupboard.models.{Bar, Subscription, _}
import cats.data.Xor
import com.google.cloud.datastore.Datastore
import com.meetup.cupboard.{AdHocDatastore, Cupboard, DatastoreFormats}
import shapeless.Typeable

import scala.reflect.ClassTag

class DatastoreSpec extends FunSpec with Matchers with AdHocDatastore {
  import DatastoreFormats._

  describe("DatastoreFormats") {
    val z = Foo("hi", 3)

    it("should serialize and deserialize simple case classes") {
      withDatastore() { ds =>

        val z1Result = Cupboard.save(ds, z, "customKind")

        if (z1Result.isLeft) {
          println("z1Result is left")
        }
        val z1P = z1Result.getOrElse(fail())
        val z1R = Cupboard.loadKind[Foo](ds, z1P.id, "customKind")
        z1Result shouldBe z1R
        z1R.map(_.entity) shouldBe Xor.Right(z)
      }
    }

    it("should support custom kinds") {
      withDatastore() { ds =>

        val z2Result = Cupboard.save(ds, z)

        val z2P = z2Result.getOrElse(fail())

        val z2R = Cupboard.loadKind[Foo](ds, z2P.id, "Foo")
        z2Result shouldBe z2R
        z2R.map(_.entity) shouldBe Xor.Right(z)

        val bar = Bar(1, Foo("hi", 4))
        val barResult = Cupboard.save(ds, bar)
        val barP = barResult.getOrElse(fail())

        val barR = Cupboard.load[Bar](ds, barP.id)
        barResult shouldBe barR
        barR.map(_.entity) shouldBe Xor.Right(bar)
      }
    }

    it("should support sealed families of case classes") {
      withDatastore() { ds =>
        val status = SubscriptionStatus.Active

        val statusResult = Cupboard.save[SubscriptionStatus](ds, status, "SubscriptionStatus")
        val statusP = statusResult.getOrElse(fail())
        val statusR = Cupboard.loadKind[SubscriptionStatus](ds, statusP.id, "SubscriptionStatus")
        statusP.entity.id shouldBe SubscriptionStatus.Active.id

        statusR shouldBe statusResult
      }
    }

    it("should support sealed families as individual properties") {
      withDatastore() { ds =>
        val zdt = ZonedDateTime.now()
        val now = ZonedDateTime.from(zdt.toInstant().atOffset(ZoneOffset.UTC))

        val subscription = Subscription.empty.copy(
          startDate = Some(now),
          renewDate = Some(now),
          status = SubscriptionStatus.Active)
        val subscriptionResult = Cupboard.save[Subscription](ds, subscription)
        val subscriptionP = subscriptionResult.getOrElse(fail())
        val subscriptionR = Cupboard.load[Subscription](ds, subscriptionP.id)
        subscriptionR shouldBe subscriptionResult

      }

    }

    it("should support classes w/ sequences of a case class as a property") {
      withDatastore() { ds =>
        val many = Many(List(Simple("hello"), Simple("world"), Simple("foo")))
        testSaveAndLoad(ds, many)
      }
    }
  }

  /**
   * Utility function that persists and then loads a case class.
   *
   * It asserts that the persisted and restored classes are the same.
   * @param ds Datastore
   * @param c  case class to be persisted
   * @param cf implicit DatastoreFormat
   * @tparam C type of case class
   */
  def testSaveAndLoad[C](ds: Datastore, c: C)(implicit cf: DatastoreFormat[C], tag: ClassTag[C], typeable: Typeable[C]) = {
    val cResult = Cupboard.save[C](ds, c)
    val cPersisted = cResult.getOrElse(fail())
    val cRestored = Cupboard.load[C](ds, cPersisted.id)
    println(cRestored)
    cRestored shouldBe cResult
    cPersisted.entity shouldBe c
  }
}

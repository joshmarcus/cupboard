package com.meetup.cupboard.datastore

import java.time.{Instant, Period, ZoneOffset, ZonedDateTime}

import cats.data.Xor
import com.google.cloud.datastore.{DateTime => GDateTime, _}
import com.meetup.cupboard.DatastoreFormats.DatastoreFormat
import com.meetup.cupboard.Result

import scala.collection.JavaConversions._

/**
 *   DatastoreProperties define how to translate individual values from the case class type to the datastore type.
 *
 *   We extend LowPriorityProperties as a way of setting the priority order of typeclass instances where more than
 *   one can apply.
 */
trait DatastoreProperties extends LowPriorityProperties {

  implicit def optionDatastoreProperty[A, B](implicit datastoreProperty: DatastoreProperty[A, _]): DatastoreProperty[Option[A], FullEntity[_]] =
    new DatastoreProperty[Option[A], FullEntity[_]] {
      def getValueFromEntity(name: String, e: FullEntity[_]): Xor[Throwable, Option[A]] = {
        Xor.catchNonFatal {
          val internalEntity: FullEntity[_] = e.getEntity(name)
          internalEntity.getString("type") match {
            case "Some" => {
              val value = datastoreProperty.getValueFromEntity("value", internalEntity)
              value.toOption
            }
            case "None" => None
          }
        }
      }

      def setEntityProperty(v: Option[A], name: String, e: Entity.Builder): Entity.Builder = {
        val emptyEntity = Entity.builder(e.build().key())
        v match {
          case Some(v1) =>
            emptyEntity.set("type", "Some")
            datastoreProperty.setEntityProperty(v1, "value", emptyEntity)
          case None =>
            emptyEntity.set("type", "None")
        }
        e.set(name, emptyEntity.build())
      }
    }

  implicit def SeqEntityProperty[E](implicit entityDatastoreFormat: DatastoreFormat[E]): DatastoreProperty[List[E], java.util.List[FullEntity[_]]] = {
    new DatastoreProperty[List[E], java.util.List[FullEntity[_]]] {
      def getValueFromEntity(name: String, e: FullEntity[_]) = {
        val internalEntities: java.util.List[EntityValue] = e.getList[EntityValue](name)
        val entities = internalEntities.map(e => entityDatastoreFormat.fromEntity(e.get())).toList
        sequence(entities)
      }

      def setEntityProperty(v: List[E], name: String, e: Entity.Builder): Entity.Builder = {
        val entities = v.map { x =>
          val emptyEntity = FullEntity.builder()
          val newEntity = entityDatastoreFormat.buildEntity(x, e).build()
          new EntityValue(newEntity)
        }
        //com.google.cloud.datastore.Value.
        import scala.collection.JavaConversions._

        val entities2 = entities.toBuffer
        e.set(name, entities2)
      }

      // turn a list of Xors into an Xor of a list
      def sequence(input: List[Xor[Throwable, E]]): Xor[Throwable, List[E]] = {
        input.foldRight[Xor[Throwable, List[E]]](Xor.Right(Nil)) { (o, ol) =>
          (o, ol) match {
            case (Xor.Right(x), Xor.Right(xs)) => Xor.Right(x :: xs)
            case (Xor.Left(y), _) => Xor.Left(y)
            case (_, Xor.Left(z)) => Xor.Left(z)
          }
        }
      }
    }

  }
}
trait LowPriorityProperties {

  trait DatastoreProperty[V, D] {
    def getValueFromEntity(fieldName: String, e: FullEntity[_]): Xor[Throwable, V]

    def setEntityProperty(v: V, fieldName: String, e: Entity.Builder): Entity.Builder
  }

  implicit object StringDatastoreProperty extends DatastoreProperty[String, String] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal(e.getString(name))
    }

    def setEntityProperty(v: String, name: String, e: Entity.Builder): Entity.Builder = {
      e.set(name, v)
    }
  }

  implicit object IntDatastoreProperty extends DatastoreProperty[Int, Int] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal(e.getLong(name).toInt)
    }

    def setEntityProperty(v: Int, name: String, e: Entity.Builder) = {
      e.set(name, v)
    }
  }

  implicit object LongDatastoreProperty extends DatastoreProperty[Long, Long] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal(e.getLong(name))
    }

    def setEntityProperty(v: Long, name: String, e: Entity.Builder) = {
      e.set(name, v)
    }
  }

  implicit object ZonedDateTimeDatastoreProperty extends DatastoreProperty[ZonedDateTime, GDateTime] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal {
        val millis: Long = e.getDateTime(name).timestampMillis()
        ZonedDateTime.from(Instant.ofEpochMilli(millis).atOffset(ZoneOffset.UTC))
      }
    }

    def setEntityProperty(v: ZonedDateTime, name: String, e: Entity.Builder) = {
      e.set(name, GDateTime.copyFrom(java.util.Date.from(v.toInstant())))
    }
  }

  implicit object InstantDatastoreProperty extends DatastoreProperty[Instant, GDateTime] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal {
        val millis: Long = e.getDateTime(name).timestampMillis()
        Instant.ofEpochMilli(millis)
      }
    }

    def setEntityProperty(v: Instant, name: String, e: Entity.Builder) = {
      e.set(name, GDateTime.copyFrom(java.util.Date.from(v)))
    }
  }

  implicit def EntityDatastoreProperty[E](implicit entityDatastoreFormat: DatastoreFormat[E]): DatastoreProperty[E, FullEntity[_]] =
    new DatastoreProperty[E, FullEntity[_]] {
      def getValueFromEntity(name: String, e: FullEntity[_]) = {
        val internalEntity: FullEntity[_] = e.getEntity(name)
        entityDatastoreFormat.fromEntity(internalEntity)
      }

      def setEntityProperty(v: E, name: String, e: Entity.Builder): Entity.Builder = {
        val emptyEntity = Entity.builder(e.build().key())
        val newEntity = entityDatastoreFormat.buildEntity(v, emptyEntity)
        e.set(name, newEntity.build())
      }
    }

  implicit object BooleanDatastoreProperty extends DatastoreProperty[Boolean, Boolean] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal(e.getBoolean(name))
    }

    def setEntityProperty(v: Boolean, name: String, e: Entity.Builder) = {
      e.set(name, v)
    }
  }

  implicit object periodDatastoreProperty extends DatastoreProperty[Period, FullEntity[_]] {
    def getValueFromEntity(name: String, e: FullEntity[_]): Xor[Throwable, Period] = {
      Xor.catchNonFatal {
        val internalEntity: FullEntity[_] = e.getEntity(name)
        val years = internalEntity.getLong("y").toInt
        val months = internalEntity.getLong("m").toInt
        val days = internalEntity.getLong("d").toInt
        Period.of(years, months, days)
      }
    }

    def setEntityProperty(v: Period, name: String, e: Entity.Builder): Entity.Builder = {
      val newEntity = Entity.builder(e.build().key())
      newEntity.set("y", v.getYears)
      newEntity.set("m", v.getMonths)
      newEntity.set("d", v.getDays)
      e.set(name, newEntity.build())
    }
  }

  implicit object BigDecimalDatastoreProperty extends DatastoreProperty[BigDecimal, Double] {
    def getValueFromEntity(name: String, e: FullEntity[_]) = {
      Xor.catchNonFatal(BigDecimal.apply(e.getDouble(name)))
    }

    def setEntityProperty(v: BigDecimal, name: String, e: Entity.Builder) = {
      e.set(name, v.toDouble)
    }
  }
}
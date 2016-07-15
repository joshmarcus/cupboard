package com.meetup.cupboard.datastore.shapeless

import cats.data.Xor
import com.google.cloud.datastore.Entity.Builder
import com.google.cloud.datastore.{Entity, FullEntity}
import com.meetup.cupboard.DatastoreFormat
import com.meetup.cupboard.datastore.{DatastoreProperties, DatastoreProperty}
import shapeless.labelled._
import shapeless.{:+:, ::, CNil, Coproduct, HList, HNil, Inl, Inr, LabelledGeneric, Lazy, Witness}

object DatastoreFormats extends DatastoreProperties {

  /// The following section uses the Shapeless library to allow us to work with case classes in a generic way.
  /// You may need to refer to the Shapeless documentation to get a good sense of what this is doing.

  implicit object hNilFormat extends DatastoreFormat[HNil] {
    def fromEntity(j: FullEntity[_]) = Xor.Right(HNil)

    def buildEntity(h: HNil, e: Entity.Builder): Entity.Builder = e
  }

  implicit def hListFormat[FieldKey <: Symbol, Value, Remaining <: HList, DatastoreValue](
    implicit
    key: Witness.Aux[FieldKey],
    propertyConverter: DatastoreProperty[Value, DatastoreValue],
    tailFormat: Lazy[DatastoreFormat[Remaining]]
  ): DatastoreFormat[FieldType[FieldKey, Value] :: Remaining] =
    new DatastoreFormat[FieldType[FieldKey, Value] :: Remaining] {

      def buildEntity(hlist: FieldType[FieldKey, Value] :: Remaining, e: Entity.Builder): Entity.Builder = {

        val tailEntity = tailFormat.value.buildEntity(hlist.tail, e)
        val fieldName = key.value.name // the name was part of the tagged type
        propertyConverter.setEntityProperty(hlist.head, fieldName, tailEntity)
        e
      }

      def fromEntity(e: FullEntity[_]): Xor[Throwable, FieldType[FieldKey, Value] :: Remaining] = {
        val fieldName = key.value.name
        val v = propertyConverter.getValueFromEntity(fieldName, e)
        val tail = tailFormat.value.fromEntity(e)
        tail.flatMap { tail2 =>
          v.map(v2 =>
            field[FieldKey](v2) :: tail2
          )
        }
      }

    }

  /**
   * The following code is what allows us to make the leap from case classes
   * to HLists of FieldType[Key, Value].
   *
   * If you want to know more, look at LabelledGeneric in shapeless (as well
   * as the idea of Singleton types).
   */

  implicit def datastoreFormat[T, Repr](
    implicit
    gen: LabelledGeneric.Aux[T, Repr],
    lazySg: Lazy[DatastoreFormat[Repr]]
  ): DatastoreFormat[T] = new DatastoreFormat[T] {
    val sg = lazySg.value

    def fromEntity(j: FullEntity[_]): Xor[Throwable, T] = {
      sg.fromEntity(j)
        .map(gen.from(_))
    }

    def buildEntity(t: T, e: Entity.Builder): Entity.Builder = sg.buildEntity(gen.to(t), e)
  }

  /**
   * CNil needs to exist, but should never actually be used in normal execution.
   *
   * The typeclass instance below iterates through each possible subtype -- if we've
   * reached here, it means we've found something unexpected.
   */
  implicit object CNilDatastoreFormat extends DatastoreFormat[CNil] {
    // If this executes, it means that the "type" saved with the entity doesn't
    // match one of our known subtypes -- e.g. one of the subtypes was removed from the
    // code but instances are still persisted.
    override def fromEntity(e: FullEntity[_]): Xor[Throwable, CNil] = {
      val typ = e.getString("type")
      Xor.Left(
        new Exception(s"$typ is not a known subclass of this abstract trait.")
      )
    }

    // This should never execute.
    override def buildEntity(a: CNil, e: Builder): Builder = e
  }

  implicit def coproductDatastoreFormat[Name <: Symbol, Head, Tail <: Coproduct](implicit
    key: Witness.Aux[Name],
    lazyHeadFormat: Lazy[DatastoreFormat[Head]],
    lazyTailFormat: Lazy[DatastoreFormat[Tail]]): DatastoreFormat[FieldType[Name, Head] :+: Tail] = new DatastoreFormat[FieldType[Name, Head] :+: Tail] {
    override def fromEntity(e: FullEntity[_]): Xor[Throwable, :+:[FieldType[Name, Head], Tail]] = {
      if (e.getString("type") == key.value.name) {
        lazyHeadFormat.value.fromEntity(e).map(headResult =>
          Inl(field[Name](headResult))
        )
      } else {
        lazyTailFormat.value.fromEntity(e).map(tailResult =>
          Inr(tailResult)
        )
      }
    }

    override def buildEntity(a: :+:[FieldType[Name, Head], Tail], e: Builder): Builder = {
      a match {
        case Inl(head) =>
          val newE: Builder = lazyHeadFormat.value.buildEntity(head, e)
          newE.set("type", key.value.name)
          e
        case Inr(tail) =>
          lazyTailFormat.value.buildEntity(tail, e)
      }

    }
  }
}
package com.gensler.scalavro
package types

import com.gensler.scalavro
import com.gensler.scalavro.error.{AvroSerializationException, AvroDeserializationException}
import scala.util.{Try, Success, Failure}
import scala.language.existentials
import scala.reflect.runtime.universe._
import spray.json._

trait AvroType[T] extends DefaultJsonProtocol {

  /**
    * The corresponding Scala type for this Avro type.
    */
  type scalaType = T

  /**
    * Returns the Avro type name for this schema.
    */
  def typeName(): String

  /**
    * Returns true if this represents a primitive Avro type.
    */
  def isPrimitive(): Boolean

  /**
    * Returns a serialized representation of the supplied object.  Throws a
    * SerializationException if writing is unsuccessful. 
    */
  @throws[AvroSerializationException[_]]
  def write(obj: T): Seq[Byte]

  /**
    * Attempts to create an object of type T by reading the required data from
    * the supplied bytes.
    */
  def read(bytes: Seq[Byte]): Try[T]

  /**
    * Returns the canonical JSON representation of this Avro type.
    */
  def schema(): spray.json.JsValue = typeName.toJson

  /**
    * == Internal API ==
    *
    * Returns the canonical JSON representation of the supplied Avro type, or
    * the JSON representation of [[AvroNull]] if no corresponding AvroType
    * can be found for the supplied type.
    */
  private[scalavro] def typeSchemaOrNull[A: TypeTag] =
    AvroType.fromType[A] match {
      case Success(avroType) => if (avroType.isPrimitive) avroType.typeName.toJson
                                else avroType.schema
      case Failure(_) => com.gensler.scalavro.types.primitive.AvroNull.typeName.toJson
    }

}

object AvroType {

  import com.gensler.scalavro.types.primitive._
  import com.gensler.scalavro.types.complex._

  // primitive type cache table
  private val primitiveTags: Map[TypeTag[_], AvroType[_]] = Map(
    typeTag[Unit]      -> AvroNull,
    typeTag[Boolean]   -> AvroBoolean,
    typeTag[Seq[Byte]] -> AvroBytes,
    typeTag[Double]    -> AvroDouble,
    typeTag[Float]     -> AvroFloat,
    typeTag[Int]       -> AvroInt,
    typeTag[Long]      -> AvroLong,
    typeTag[String]    -> AvroString
  )

  // complex type cache table
  private var complexTags = Map[TypeTag[_], AvroType[_]]()

  /**
    * Returns a `Success[AvroType[T]]` if an analogous AvroType is available
    * for the supplied type.
    */
  def fromType[T](implicit tt: TypeTag[T]): Try[AvroType[T]] = Try {

    val avroType = primitiveTags.collectFirst { case (tag, at) if tt.tpe =:= tag.tpe => at } match {
      case Some(primitive) => primitive
      case None => complexTags.collectFirst { case (tag, at) if tt.tpe =:= tag.tpe => at } match {
        case Some(complex) => complex
        case None => {

          val newComplexType = {
            // lists, sequences, etc
            if (tt.tpe <:< typeOf[Seq[_]]) tt.tpe match {
              case TypeRef(_, _, List(itemType)) => fromSeqType(ruTagFor(itemType))
            }

            // string-keyed maps
            else if (tt.tpe <:< typeOf[Map[String, _]]) tt.tpe match {
              case TypeRef(_, _, List(stringType, itemType)) => fromMapType(ruTagFor(itemType))
            }

            else if (tt.tpe <:< typeOf[Either[_, _]]) tt.tpe match {
              case TypeRef(_, _, List(left, right)) => fromEitherType(ruTagFor(left), ruTagFor(right))
            }

            else ??? // more complex types not handled yet
          }

          complexTags += tt -> newComplexType
          newComplexType
        }
      }
    }

    avroType.asInstanceOf[AvroType[T]]
  }

  private def fromSeqType[A](itemType: TypeTag[_ <: A]) = 
    new AvroArray()(itemType)

  private def fromMapType[A](itemType: TypeTag[_ <: A]) =
    new AvroMap()(itemType)

  private def fromEitherType[A, B](left: TypeTag[_ <: A], right: TypeTag[_ <: B]) =
    new AvroUnion()(left, right)

  private def ruTagFor(tpe: Type): TypeTag[_] = {
    import scala.reflect.api._
    TypeTag(
      runtimeMirror(getClass.getClassLoader),
      new TypeCreator {
        def apply[U <: Universe with Singleton](m: Mirror[U]) = tpe.asInstanceOf[U#Type]
      }
    )
  }

}
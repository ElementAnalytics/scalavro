package com.gensler.scalavro.io.complex

import com.gensler.scalavro.io.AvroTypeIO
import com.gensler.scalavro.io.primitive.AvroLongIO
import com.gensler.scalavro.types.AvroType
import com.gensler.scalavro.types.complex.AvroUnion
import com.gensler.scalavro.error.{ AvroSerializationException, AvroDeserializationException }
import com.gensler.scalavro.io.AvroTypeIO.Implicits._
import com.gensler.scalavro.util.ReflectionHelpers

import com.gensler.scalavro.util.Union
import com.gensler.scalavro.util.Union._

import org.apache.avro.io.{ BinaryEncoder, BinaryDecoder }

import scala.util.{ Try, Success, Failure }
import scala.reflect.runtime.universe._

import java.io.{ InputStream, OutputStream }

private[scalavro] case class AvroClassUnionIO[U <: Union.not[_]: TypeTag, T: TypeTag](
    avroType: AvroUnion[U, T]) extends AvroUnionIO[U, T] {

  protected[scalavro] def asGeneric[X <: T: TypeTag](obj: X) = {
    val typeOfObj = ReflectionHelpers.classLoaderMirror.staticClass(obj.getClass.getName).toType
    val objTypeTag = ReflectionHelpers.tagForType(typeOfObj)

    avroType.memberAvroTypes.find { at => typeOfObj <:< at.tag.tpe } match {
      case Some(memberType) => memberType.asInstanceOf[AvroType[T]].asGeneric(obj)(memberType.tag.asInstanceOf[TypeTag[X]])
      case None             => throw new AvroSerializationException(obj)
    }
  }

  def write[X <: T: TypeTag](obj: X, encoder: BinaryEncoder) = {
    val staticTypeOfObj = typeOf[X]
    val typeOfObj = ReflectionHelpers.classLoaderMirror.staticClass(obj.getClass.getName).toType
    val objTypeTag = ReflectionHelpers.tagForType(typeOfObj)

    avroType.memberAvroTypes.indexWhere { at => staticTypeOfObj <:< at.tag.tpe || typeOfObj <:< at.tag.tpe } match {
      case -1 => throw new AvroSerializationException(obj)
      case index: Int => {
        AvroLongIO.write(index.toLong, encoder)
        val memberType = avroType.memberAvroTypes(index).asInstanceOf[AvroType[X]]
        memberType.write(obj, encoder)(objTypeTag.asInstanceOf[TypeTag[X]])
        encoder.flush
      }
    }
  }

  def read(decoder: BinaryDecoder) = Try {
    val index = AvroLongIO.read(decoder).get
    val memberType = avroType.memberAvroTypes(index.toInt).asInstanceOf[AvroType[T]]
    memberType.read(decoder).get
  }

}
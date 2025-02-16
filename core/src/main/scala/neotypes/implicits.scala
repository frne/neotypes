package neotypes

import java.time.{LocalDate, LocalDateTime, LocalTime, OffsetDateTime, OffsetTime, ZonedDateTime}
import java.util.UUID

import neotypes.exceptions.{ConversionException, PropertyNotFoundException, UncoercibleException}
import neotypes.mappers.{ExecutionMapper, ResultMapper, TypeHint, ValueMapper}
import neotypes.types.Path
import neotypes.utils.traverse.{traverseAsList, traverseAsMap, traverseAsSet}
import org.neo4j.driver.internal.types.InternalTypeSystem
import org.neo4j.driver.internal.value.{IntegerValue, MapValue, NodeValue, RelationshipValue}
import org.neo4j.driver.v1.{Value, Session => NSession, Driver => NDriver}
import org.neo4j.driver.v1.exceptions.value.Uncoercible
import org.neo4j.driver.v1.summary.ResultSummary
import org.neo4j.driver.v1.types.{IsoDuration, Node, Path => NPath, Point, Relationship}
import shapeless.labelled.FieldType
import shapeless.{:: => :!:, HList, HNil, LabelledGeneric, Lazy, Witness, labelled}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

object implicits {
  /**
    * ValueMappers
    */

  private[implicits] def valueMapperFromCast[T](f: Value => T): ValueMapper[T] =
    new ValueMapper[T] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, T] =
        extract(fieldName, value, f)
    }

  private[implicits] def extract[T](fieldName: String, value: Option[Value], f: Value => T): Either[Throwable, T] =
    value match {
      case None    => Left(PropertyNotFoundException(s"Property $fieldName not found"))
      case Some(v) => coerce(v, f)
    }

  private[implicits] def coerce[T](value: Value, f: Value => T): Either[Throwable, T] =
    try {
      Right(f(value))
    } catch {
      case ex: Uncoercible => Left(UncoercibleException(ex.getLocalizedMessage, ex))
      case ex: Throwable   => Left(ex)
    }

  implicit val BooleanValueMapper: ValueMapper[Boolean] =
    valueMapperFromCast(_.asBoolean)

  implicit val ByteArrayValueMapper: ValueMapper[Array[Byte]] =
    valueMapperFromCast(_.asByteArray)

  implicit val DoubleValueMapper: ValueMapper[Double] =
    valueMapperFromCast(_.asDouble)

  implicit val FloatValueMapper: ValueMapper[Float] =
    valueMapperFromCast(_.asFloat)

  implicit val IntValueMapper: ValueMapper[Int] =
    valueMapperFromCast(_.asInt)

  implicit val IsoDurationValueMapper: ValueMapper[IsoDuration] =
    valueMapperFromCast(_.asIsoDuration)

  implicit val LocalDateValueMapper: ValueMapper[LocalDate] =
    valueMapperFromCast(_.asLocalDate)

  implicit val LocalDateTimeValueMapper: ValueMapper[LocalDateTime] =
    valueMapperFromCast(_.asLocalDateTime)

  implicit val LocalTimeValueMapper: ValueMapper[LocalTime] =
    valueMapperFromCast(_.asLocalTime)

  implicit val LongValueMapper: ValueMapper[Long] =
    valueMapperFromCast(_.asLong)

  implicit val NodeValueMapper: ValueMapper[Node] =
    valueMapperFromCast(_.asNode)

  implicit val OffsetDateTimeValueMapper: ValueMapper[OffsetDateTime] =
    valueMapperFromCast(_.asOffsetDateTime)

  implicit val OffsetTimeValueMapper: ValueMapper[OffsetTime] =
    valueMapperFromCast(_.asOffsetTime)

  implicit val PathValueMapper: ValueMapper[NPath] =
    valueMapperFromCast(_.asPath)

  implicit val PointValueMapper: ValueMapper[Point] =
    valueMapperFromCast(_.asPoint)

  implicit val RelationshipValueMapper: ValueMapper[Relationship] =
    valueMapperFromCast(_.asRelationship)

  implicit val StringValueMapper: ValueMapper[String] =
    valueMapperFromCast(_.asString)

  implicit val ZonedDateTimeValueMapper: ValueMapper[ZonedDateTime] =
    valueMapperFromCast(_.asZonedDateTime)

  implicit val ValueValueMapper: ValueMapper[Value] =
    valueMapperFromCast(identity)

  implicit val HNilMapper: ValueMapper[HNil] =
    new ValueMapper[HNil] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, HNil] =
        Right(HNil)
    }

  implicit val UUIDValueMapper: ValueMapper[UUID] =
    valueMapperFromCast(s => UUID.fromString(s.asString()))

  implicit def mapValueMapper[T](implicit mapper: ValueMapper[T]): ValueMapper[Map[String, T]] =
    new ValueMapper[Map[String, T]] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, Map[String, T]] =
        value match {
          case None =>
            Right(Map.empty)

          case Some(value) =>
            traverseAsMap(value.keys.asScala.iterator) { key: String =>
              key -> mapper.to(key, Option(value.get(key)))
            }
        }
    }

  implicit def listValueMapper[T](implicit mapper: ValueMapper[T]): ValueMapper[List[T]] =
    new ValueMapper[List[T]] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, List[T]] =
        value match {
          case None =>
            Right(List.empty)

          case Some(value) =>
            traverseAsList(value.values.asScala.iterator) { value: Value =>
              mapper.to("", Option(value))
            }
        }
    }

  implicit def setValueMapper[T](implicit mapper: ValueMapper[T]): ValueMapper[Set[T]] =
    new ValueMapper[Set[T]] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, Set[T]] =
        value match {
          case None =>
            Right(Set.empty)

          case Some(value) =>
            traverseAsSet(value.values.asScala.iterator) { value: Value =>
              mapper.to("", Option(value))
            }
        }
    }

  implicit def optionValueMapper[T](implicit mapper: ValueMapper[T]): ValueMapper[Option[T]] =
    new ValueMapper[Option[T]] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, Option[T]] =
        value match {
          case None =>
            Right(None)

          case Some(value) =>
            mapper
              .to(fieldName, Some(value))
              .right
              .map(Option(_))
        }
    }

  implicit def ccValueMarshallable[T](implicit resultMapper: ResultMapper[T], ct: ClassTag[T]): ValueMapper[T] =
    new ValueMapper[T] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, T] =
        value match {
          case Some(value: MapValue) =>
            resultMapper.to(
              value
                .keys
                .asScala
                .iterator
                .map(key => key -> value.get(key))
                .toList,
              Some(TypeHint(ct))
            )

          case Some(value) =>
            resultMapper.to(Seq(fieldName -> value), Some(TypeHint(ct)))

          case None =>
            Left(ConversionException(s"Cannot convert $fieldName [$value]"))
        }
    }

  implicit def pathMarshallable[N, R](implicit nm: ResultMapper[N], rm: ResultMapper[R]): ValueMapper[Path[N, R]] =
    new ValueMapper[Path[N, R]] {
      override def to(fieldName: String, value: Option[Value]): Either[Throwable, Path[N, R]] =
        value match {
          case None =>
            Left(PropertyNotFoundException(s"Property $fieldName not found"))

          case Some(value) =>
            if (value.`type` == InternalTypeSystem.TYPE_SYSTEM.PATH) {
              val path = value.asPath

              val nodes = traverseAsList(path.nodes.asScala.iterator.zipWithIndex) {
                case (node, index) => nm.to(Seq(s"node $index" -> new NodeValue(node)), None)
              }

              val relationships = traverseAsList(path.relationships.asScala.iterator.zipWithIndex) {
                case (relationship, index) => rm.to(Seq(s"relationship $index" -> new RelationshipValue(relationship)), None)
              }

              for {
                nodes <- nodes.right
                relationships <- relationships.right
              } yield Path(nodes, relationships, path)
            } else {
              Left(ConversionException(s"$fieldName of type ${value.`type`} cannot be converted into a Path"))
            }
        }
    }

  /**
    * ResultMapper
    */

  private[implicits] def resultMapperFromValueMapper[T](implicit marshallable: ValueMapper[T]): ResultMapper[T] =
    new ResultMapper[T] {
      override def to(fields: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, T] =
        fields
          .headOption
          .fold(ifEmpty = marshallable.to("", None)) {
            case (name, value) => marshallable.to(name, Some(value))
          }
    }

  implicit val BooleanResultMapper: ResultMapper[Boolean] =
    resultMapperFromValueMapper

  implicit val ByteArrayResultMapper: ResultMapper[Array[Byte]] =
    resultMapperFromValueMapper

  implicit val DoubleResultMapper: ResultMapper[Double] =
    resultMapperFromValueMapper

  implicit val FloatResultMapper: ResultMapper[Float] =
    resultMapperFromValueMapper

  implicit val IntResultMapper: ResultMapper[Int] =
    resultMapperFromValueMapper

  implicit val IsoDurationResultMapper: ResultMapper[IsoDuration] =
    resultMapperFromValueMapper

  implicit val LocalDateResultMapper: ResultMapper[LocalDate] =
    resultMapperFromValueMapper

  implicit val LocalDateTimeResultMapper: ResultMapper[LocalDateTime] =
    resultMapperFromValueMapper

  implicit val LocalTimeResultMapper: ResultMapper[LocalTime] =
    resultMapperFromValueMapper

  implicit val LongResultMapper: ResultMapper[Long] =
    resultMapperFromValueMapper

  implicit val NodeResultMapper: ResultMapper[Node] =
    resultMapperFromValueMapper

  implicit val OffsetDateTimeResultMapper: ResultMapper[OffsetDateTime] =
    resultMapperFromValueMapper

  implicit val OffsetTimeResultMapper: ResultMapper[OffsetTime] =
    resultMapperFromValueMapper

  implicit val PathResultMapper: ResultMapper[NPath] =
    resultMapperFromValueMapper

  implicit val PointResultMapper: ResultMapper[Point] =
    resultMapperFromValueMapper

  implicit val RelationshipResultMapper: ResultMapper[Relationship] =
    resultMapperFromValueMapper

  implicit val StringResultMapper: ResultMapper[String] =
    resultMapperFromValueMapper

  implicit val ZonedDateTimeResultMapper: ResultMapper[ZonedDateTime] =
    resultMapperFromValueMapper

  implicit val ValueResultMapper: ResultMapper[Value] =
    resultMapperFromValueMapper

  implicit val HNilResultMapper: ResultMapper[HNil] =
    resultMapperFromValueMapper

  implicit val UUIDResultMapper: ResultMapper[UUID] =
    resultMapperFromValueMapper

  implicit val UnitResultMapper: ResultMapper[Unit] =
    new ResultMapper[Unit] {
      override def to(value: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, Unit] =
        Right(())
    }

  implicit def mapResultMapper[T: ValueMapper]: ResultMapper[Map[String, T]] =
    resultMapperFromValueMapper

  implicit def listResultMapper[T: ValueMapper]: ResultMapper[List[T]] =
    resultMapperFromValueMapper

  implicit def setResultMapper[T: ValueMapper]: ResultMapper[Set[T]] =
    resultMapperFromValueMapper

  implicit def pathRecordMarshallable[N: ResultMapper, R: ResultMapper]: ResultMapper[Path[N, R]] =
    resultMapperFromValueMapper

  implicit def optionResultMapper[T](implicit mapper: ResultMapper[T]): ResultMapper[Option[T]] =
    new ResultMapper[Option[T]] {
      override def to(fields: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, Option[T]] =
        if (fields.isEmpty)
          Right(None)
        else
          mapper
            .to(fields, typeHint)
            .right
            .map(Option(_))
    }

  implicit def hlistMarshallable[H, T <: HList, LR <: HList](implicit fieldDecoder: ValueMapper[H],
                                                             tailDecoder: ResultMapper[T]): ResultMapper[H :!: T] =
    new ResultMapper[H :!: T] {
      override def to(value: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, H :!: T] = {
        val (headName, headValue) = value.head
        val head = fieldDecoder.to(headName, Some(headValue))
        val tail = tailDecoder.to(value.tail, None)

        head.right.flatMap(h => tail.right.map(t => h :: t))
      }
    }

  implicit def keyedHconsMarshallable[K <: Symbol, H, T <: HList](implicit key: Witness.Aux[K],
                                                                  head: ValueMapper[H],
                                                                  tail: ResultMapper[T]): ResultMapper[FieldType[K, H] :!: T] =
    new ResultMapper[FieldType[K, H] :!: T] {
      override def to(value: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, FieldType[K, H] :!: T] = {
        val fieldName = key.value.name

        typeHint match {
          case Some(TypeHint(true)) =>
            val index = fieldName.substring(1).toInt - 1
            val decodedHead = head.to(fieldName, if (value.size <= index) None else Some(value(index)._2))
            decodedHead.right.flatMap(v => tail.to(value, typeHint).right.map(t => labelled.field[K](v) :: t))

          case _ =>
            val convertedValue =
              if (value.size == 1 && value.head._2.`type` == InternalTypeSystem.TYPE_SYSTEM.NODE) {
                val node = value.head._2.asNode
                val nodes =
                  node
                    .keys
                    .asScala
                    .iterator
                    .map(key => key -> node.get(key))
                    .toList
                (Constants.ID_FIELD_NAME -> new IntegerValue(node.id)) :: nodes
              } else {
                value
              }

            val decodedHead = head.to(fieldName, convertedValue.find(_._1 == fieldName).map(_._2))
            decodedHead.right.flatMap(v => tail.to(convertedValue, typeHint).right.map(t => labelled.field[K](v) :: t))
        }
      }
    }

  implicit def ccMarshallable[A, R <: HList](implicit gen: LabelledGeneric.Aux[A, R],
                                             reprDecoder: Lazy[ResultMapper[R]],
                                             ct: ClassTag[A]): ResultMapper[A] =
    new ResultMapper[A] {
      override def to(value: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, A] =
        reprDecoder.value.to(value, Some(TypeHint(ct))).right.map(gen.from)
    }

  /**
    * ExecutionMappers
    */

  implicit val ResultSummaryExecutionMapper: ExecutionMapper[ResultSummary] =
    new ExecutionMapper[ResultSummary] {
      override def to(resultSummary: ResultSummary): Either[Throwable, ResultSummary] =
        Right(resultSummary)
    }

  implicit val UnitExecutionMapper: ExecutionMapper[Unit] =
    new ExecutionMapper[Unit] {
      override def to(resultSummary: ResultSummary): Either[Throwable, Unit] =
        Right(())
    }

  /**
    * Extras
    */

  implicit class SessionExt(val session: NSession) extends AnyVal {
    def asScala[F[_]: Async]: Session[F] =
      new Session[F](session)
  }

  implicit class DriverExt(val driver: NDriver) extends AnyVal {
    def asScala[F[_]: Async]: Driver[F] =
      new Driver[F](driver)
  }

  implicit class StringOps(val s: String) extends AnyVal {
    def query[T]: DeferredQuery[T] =
      DeferredQuery(query = s)
  }

  implicit class CypherString(val sc: StringContext) extends AnyVal {
    def c(args: Any*): DeferredQueryBuilder = {
      val queries = sc.parts.iterator.map(DeferredQueryBuilder.Query)
      val params = args.iterator.map(DeferredQueryBuilder.Param)

      val parts = new Iterator[DeferredQueryBuilder.Part] {
        private var paramNext: Boolean = false
        override def hasNext: Boolean = queries.hasNext
        override def next(): DeferredQueryBuilder.Part =
          if (paramNext && params.hasNext) {
            paramNext = false
            params.next()
          } else {
            paramNext = true
            queries.next()
          }
      }

      new DeferredQueryBuilder(parts.toList)
    }
  }

  /**
    * Async
    */

  implicit class AsyncExt[F[_], T](val m: F[T]) extends AnyVal {
    def map[U](f: T => U)(implicit F: Async[F]): F[U] =
      F.map(m)(f)

    def flatMap[U](f: T => F[U])(implicit F: Async[F]): F[U] =
      F.flatMap(m)(f)

    def recoverWith[U >: T](f: PartialFunction[Throwable, F[U]])(implicit F: Async[F]): F[U] =
      F.recoverWith[T, U](m)(f)
  }

  implicit def futureAsync(implicit ec: ExecutionContext): Async[Future] =
    new Async[Future] {
      override def async[A](cb: (Either[Throwable, A] => Unit) => Unit): Future[A] = {
        val p = Promise[A]()
        cb {
          case Right(res) => p.complete(Success(res))
          case Left(ex)   => p.complete(Failure(ex))
        }
        p.future
      }

      override def flatMap[T, U](m: Future[T])(f: T => Future[U]): Future[U] =
        m.flatMap(f)

      override def map[T, U](m: Future[T])(f: T => U): Future[U] =
        m.map(f)

      override def recoverWith[T, U >: T](m: Future[T])(f: PartialFunction[Throwable, Future[U]]): Future[U] =
        m.recoverWith(f)

      override def failed[T](e: Throwable): Future[T] =
        Future.failed(e)

      override def success[T](t: => T): Future[T] =
        Future.successful(t)
    }
}

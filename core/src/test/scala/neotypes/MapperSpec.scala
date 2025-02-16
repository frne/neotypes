package neotypes

import neotypes.implicits.{IntResultMapper, IntValueMapper, StringResultMapper, StringValueMapper}
import neotypes.mappers.{ResultMapper, TypeHint, ValueMapper}
import org.neo4j.driver.internal.value.{IntegerValue, StringValue}
import org.neo4j.driver.v1.Value
import org.scalatest.FreeSpec

class MapperSpec extends FreeSpec {

  case class MyCaseClass(value: String)

  "ResultMapper" - {
    "constructors" - {
      "should summon implicit ResultMapper instances" in {
        val strMapper = ResultMapper[String]
        assert(strMapper == StringResultMapper)
      }
      "should create an instance based on a function" in {
        def myParsingFunction(vals: Seq[(String, Value)], typeHint: Option[TypeHint]) = Right("function works")
        val myInstanceMapper = ResultMapper.instance(myParsingFunction)
        val result = myInstanceMapper.to(Seq(("value", new StringValue("function doesn't work"))), None)
        assert(result == Right("function works"))
      }
      "should return a constant result value" in {
        val constMapper = ResultMapper.const[String]("const")
        val result = constMapper.to(Seq(("value", new StringValue("not const"))), None)
        assert(result == Right("const"))
      }
      "should return a constant failure" in {
        val myException = new Exception("Example Exception")
        val failureMapper = ResultMapper.failed[String](myException)
        val result = failureMapper.to(Seq(("value", new StringValue("value"))), None)
        assert(result == Left(myException))
      }
    }
    "should allow defining a secondary mapper" in {
      val failingMapper = new ResultMapper[String] {
        override def to(value: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, String] =
          Left(new Exception)
      }
      val result = failingMapper.or(StringResultMapper).to(Seq(("value", new StringValue("string"))), None)
      assert(result == Right("string"))
    }

    "should be mappable" in {
      val caseClassMapper = StringResultMapper.map(x => MyCaseClass(x + "2"))
      val result = caseClassMapper.to(Seq(("value", new StringValue("1"))), None)
      assert(result == Right(MyCaseClass("12")))
    }

    "should be flatmappable" in {
      val flatMappedMapper = StringResultMapper.flatMap { str =>
        new ResultMapper[Int] {
          override def to(value: Seq[(String, Value)], typeHint: Option[TypeHint]): Either[Throwable, Int] =
            Right(str.length)
        }
      }
      val result = flatMappedMapper.to(Seq(("value", new StringValue("twelve chars"))), None)
      assert(result == Right(12))
    }

    "should derive a product mapper" in {
      val lengthOfStringMapper = StringResultMapper.map(_.length)
      val productMapper = StringResultMapper.product(lengthOfStringMapper)
      val result = productMapper.to(Seq(("value", new StringValue("twelve chars"))), None)
      assert(result == Right("twelve chars", 12))
    }

    "should derive an either mapper" in {
      val eitherIntOrStringMapper = IntResultMapper.either(StringResultMapper)
      val resultInt = eitherIntOrStringMapper.to(Seq(("value", new IntegerValue(1))), None)
      val resultString = eitherIntOrStringMapper.to(Seq(("value", new StringValue("string"))), None)
      assert(resultInt == Right(Left(1)))
      assert(resultString == Right(Right("string")))
    }
  }

  "ValueMapper" - {
    "constructors" - {
      "should summon implicit ValueMapper instances" in {
        val strMapper = ValueMapper[String]
        assert(strMapper == StringValueMapper)
      }
      "should create an instance based on a function" in {
        def myParsingFunction(name: String, value: Option[Value]) = Right("function works")
        val myInstanceMapper = ValueMapper.instance(myParsingFunction)
        val result = myInstanceMapper.to("value", Some(new StringValue("function doesn't work")))
        assert(result == Right("function works"))
      }
      "should return a constant result value" in {
        val constMapper = ValueMapper.const[String]("const")
        val result = constMapper.to("value", Some(new StringValue("not const")))
        assert(result == Right("const"))
      }
      "should return a constant failure" in {
        val myException = new Exception("Example Exception")
        val failureMapper = ValueMapper.failed[String](myException)
        val result = failureMapper.to("value", Some(new StringValue("value")))
        assert(result == Left(myException))
      }
    }
    "should allow defining a secondary mapper" in {
      val failingMapper = new ValueMapper[String] {
        override def to(fieldName: String, value: Option[Value]): Either[Throwable, String] =
          Left(new Exception)
      }
      val result = failingMapper.or(StringValueMapper).to("value", Some(new StringValue("string")))
      assert(result == Right("string"))
    }

    "should be mappable" in {
      val caseClassMapper = StringValueMapper.map(x => MyCaseClass(x + "2"))
      val result = caseClassMapper.to("value", Some(new StringValue("1")))
      assert(result == Right(MyCaseClass("12")))
    }

    "should be flatmappable" in {
      val flatMappedMapper = StringValueMapper.flatMap { str =>
        new ValueMapper[Int] {
          override def to(fieldName: String, value: Option[Value]): Either[Throwable, Int] =
            Right(str.length)
        }
      }
      val result = flatMappedMapper.to("value", Some(new StringValue("twelve chars")))
      assert(result == Right(12))
    }

    "should be able to derive a product mapper" in {
      val lengthOfStringMapper = StringValueMapper.map(_.length)
      val productMapper = StringValueMapper.product(lengthOfStringMapper)
      val result = productMapper.to("value", Some(new StringValue("twelve chars")))
      assert(result == Right("twelve chars", 12))
    }

    "should derive an either mapper" in {
      val eitherIntOrStringMapper = IntValueMapper.either(StringValueMapper)
      val resultInt = eitherIntOrStringMapper.to("value", Some(new IntegerValue(1)))
      val resultString = eitherIntOrStringMapper.to("value", Some(new StringValue("string")))
      assert(resultInt == Right(Left(1)))
      assert(resultString == Right(Right("string")))
    }
  }

}

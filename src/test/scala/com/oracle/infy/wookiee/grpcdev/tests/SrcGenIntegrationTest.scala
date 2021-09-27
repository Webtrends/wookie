package com.oracle.infy.wookiee.grpcdev.tests

import utest.{Tests, test}

object SrcGenIntegrationTest {

  val scalaSource: String =
    """
        sealed trait TestMaybeResponse
      
        case class TestCaseClass(testString: String, testInt: Int)
      
        case class TestOptionString(maybeString: Option[String])
      
        case class TestOptionCaseClass(maybeCaseClass: Option[TestCaseClass])
      
        case class TestOptionOptionString(maybeMaybeString: Option[Option[String]])
      
        case class TestOptionOptionCaseClass(maybeMaybeCaseClass: Option[Option[TestCaseClass]])
      
        final case class TestRequest(
            testString: String,
            testInt: Int,
            testOptionString: TestOptionString,
            testOptionCaseClass: TestOptionCaseClass,
            testOptionOptionString: TestOptionOptionString,
            testOptionOptionCaseClass: TestOptionOptionCaseClass
        )
      
        final case class TestMaybeResponseCreated(createdObject: TestCaseClass) extends TestMaybeResponse
        final case class TestMaybeResponseFailed(code: Int, msg: String, detail: String) extends TestMaybeResponse
        final case class TestMaybeResponseInvalid(errors: List[String]) extends TestMaybeResponse
    """.stripMargin

  val expectedProto: String = ""

  val expectedScala: String = ""

  def tests(): Tests =
    Tests {
      test("genService (proto file) integration test") {
        assert(GrpcDevTest.genScalaTest(scalaSource, expectedScala))
      }
      test("genScala (implicits) integration test") {
        assert(GrpcDevTest.genProtoTest(scalaSource, expectedProto))
      }
    }
}

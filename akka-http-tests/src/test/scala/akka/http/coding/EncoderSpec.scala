/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.util.ByteString
import org.scalatest.WordSpec
import akka.http.model._
import headers._
import HttpMethods.POST
import scala.concurrent.duration._
import akka.http.util._

class EncoderSpec extends WordSpec with CodecSpecSupport {

  "An Encoder" should {
    "not transform the message if messageFilter returns false" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText.getBytes("UTF8")))
      DummyEncoder.encode(request) shouldEqual request
    }
    "correctly transform the HttpMessage if messageFilter returns true" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText.getBytes("UTF8")))
      val encoded = DummyEncoder.encode(request)
      encoded.headers shouldEqual List(`Content-Encoding`(DummyEncoder.encoding))
      encoded.entity.toStrict(1.second).awaitResult(1.second) shouldEqual HttpEntity(DummyEncoder.encodeString(smallText))
    }
  }

  case object DummyEncoder extends Encoder {
    override val encoding = HttpEncodings.compress
    override def encoder = appendingFlow("compressed")
  }
}

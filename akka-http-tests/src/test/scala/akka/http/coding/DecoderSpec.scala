/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model.HttpMethods.POST
import akka.http.model._
import akka.http.model.headers._
import akka.http.util._
import akka.util.ByteString
import org.scalatest.WordSpec

import scala.concurrent.duration._

class DecoderSpec extends WordSpec with CodecSpecSupport {

  "A Decoder" should {
    "not transform the message if it doesn't contain a Content-Encoding header" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText))
      DummyDecoder.decode(request, maxBytesPerChunk) shouldEqual request
    }
    "correctly transform the message if it contains a Content-Encoding header" in {
      val request = HttpRequest(POST,
        entity = HttpEntity(smallText),
        headers = List(`Content-Encoding`(DummyDecoder.encoding)))
      val decoded = DummyDecoder.decode(request, maxBytesPerChunk)
      decoded.headers shouldEqual Nil
      decoded.entity.toStrict(1.second).awaitResult(1.second) shouldEqual
        HttpEntity(DummyDecoder.decodeString(ByteString(smallText)))
    }
  }

  case object DummyDecoder extends Decoder {
    override val encoding = HttpEncodings.compress
    override def decoder(maxBytesPerChunk: Int) = appendingFlow("compressed")
  }
}

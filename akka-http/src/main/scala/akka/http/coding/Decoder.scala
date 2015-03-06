/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.http.model.headers.{ HttpEncoding, `Content-Encoding` }
import akka.http.util._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Sink, Source, Flow }
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

trait Decoder {
  val encoding: HttpEncoding

  def decoder(maxBytesPerChunk: Int): Flow[ByteString, ByteString, Unit]

  protected def removeEncoding(headers: immutable.Seq[HttpHeader]) = headers flatMap {
    case `Content-Encoding`(Seq(`encoding`)) ⇒ None
    case `Content-Encoding`(encodings :+ `encoding`) ⇒ Some(`Content-Encoding`(encodings))
    case x ⇒ Some(x)
  }

  def decode[T <: HttpMessage](message: T, maxBytesPerChunk: Int): T = {
    val transform = decoder(maxBytesPerChunk) via StreamUtils.mapErrorTransformer {
      case NonFatal(e) ⇒
        IllegalRequestException(
          StatusCodes.BadRequest,
          ErrorInfo("The request's encoding is corrupt", e.getMessage))
    }
    (message.header[`Content-Encoding`] match {
      case Some(`Content-Encoding`(_ :+ `encoding`)) ⇒
        message
          .mapEntity(_.transformDataBytes(transform).asInstanceOf[MessageEntity]) // FIXME
          .mapHeaders(removeEncoding)
      case _ ⇒
        message // noop
    }).asInstanceOf[T]
  }

  def decodeBytes(bytes: ByteString, timeout: Duration = 1.second)(implicit materializer: FlowMaterializer): ByteString =
    Source.single(bytes).via(decoder(bytes.length)).runWith(Sink.head()).awaitResult(timeout)

  def decodeString(bytes: ByteString, charset: String = "UTF8")(implicit materializer: FlowMaterializer): String =
    decodeBytes(bytes).decodeString(charset)
}

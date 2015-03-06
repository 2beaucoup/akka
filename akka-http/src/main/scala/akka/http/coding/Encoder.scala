/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.http.model.headers.{ HttpEncoding, `Content-Encoding`, HttpEncodings }
import akka.http.util._
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Sink, Source, Flow }
import akka.util.ByteString

import scala.collection.immutable
import scala.concurrent.duration._

trait Encoder {
  val encoding: HttpEncoding

  def encoder: Flow[ByteString, ByteString, Unit]

  protected def addEncoding(headers: immutable.Seq[HttpHeader]) =
    if (encoding == HttpEncodings.identity) headers
    else if (headers.exists(_.isInstanceOf[`Content-Encoding`]))
      headers.map {
        case `Content-Encoding`(encodings) ⇒ `Content-Encoding`(encodings :+ encoding)
        case x                             ⇒ x
      }
    else `Content-Encoding`(encoding) +: headers

  def encode[T <: HttpMessage](message: T): T =
    (message.header[`Content-Encoding`] match {
      case Some(`Content-Encoding`(encodings)) if encodings.contains(encoding) ⇒
        message // noop
      case None ⇒
        message
          .mapEntity(_.transformDataBytes(encoder).asInstanceOf[MessageEntity]) // FIXME
          .mapHeaders(addEncoding)
    }).asInstanceOf[T]

  def encodeBytes(bytes: ByteString, timeout: Duration = 1.second)(implicit materializer: FlowMaterializer): ByteString =
    Source.single(bytes).via(encoder).runWith(Sink.head()).awaitResult(timeout)

  def encodeString(s: String, charset: String = "UTF8")(implicit materializer: FlowMaterializer): ByteString =
    encodeBytes(ByteString(s, charset))
}
